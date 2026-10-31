/*
 * PCM output stage for shairport-sync (see pcm_sink.h).
 *
 * Threading: shairport's player thread calls pcm_sink_write(); Oboe's real-time
 * callback calls the ring reader.  That is a single-producer/single-consumer ring
 * buffer with atomic indices, so the callback never takes a lock and never
 * allocates.  Control operations (start/stop/flush) take a mutex because they are
 * called from yet another thread and they replace the stream underneath.
 *
 * Format handling: shairport hands over S16_LE (classic AirPlay, 44.1 kHz) or
 * S32_LE (AirPlay 2, 48 kHz) and the Oboe stream is always I16, so samples are
 * narrowed here.  Narrowing is a shift, not a scaling factor: it keeps the sender's
 * already-applied software volume intact.
 */

#include "pcm_sink.h"

#include <android/log.h>
#include <oboe/Oboe.h>
#include <unistd.h>

#include <atomic>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <memory>
#include <mutex>
#include <vector>

#define TAG "AirPlayPcm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

/* three seconds of audio is plenty of slack for a producer that runs in bursts */
constexpr int kBufferSeconds = 3;
/* give up waiting for space after this long: a stalled writer must not hang teardown */
constexpr int kWriteTimeoutMs = 500;
constexpr int kWritePollUs = 2000;

struct Ring {
    std::vector<int16_t> data;
    std::atomic<uint64_t> head{0};  // samples written
    std::atomic<uint64_t> tail{0};  // samples read

    void reset(size_t samples) {
        data.assign(samples, 0);
        head.store(0, std::memory_order_relaxed);
        tail.store(0, std::memory_order_relaxed);
    }

    size_t capacity() const { return data.size(); }

    size_t availableToWrite() const {
        const uint64_t used = head.load(std::memory_order_relaxed) - tail.load(std::memory_order_acquire);
        return used >= capacity() ? 0 : capacity() - (size_t)used;
    }

    size_t availableToRead() const {
        const uint64_t used = head.load(std::memory_order_acquire) - tail.load(std::memory_order_relaxed);
        return used > capacity() ? capacity() : (size_t)used;
    }

    void write(const int16_t *src, size_t count) {
        const uint64_t h = head.load(std::memory_order_relaxed);
        const size_t cap = capacity();
        for (size_t i = 0; i < count; i++)
            data[(size_t)((h + i) % cap)] = src[i];
        head.store(h + count, std::memory_order_release);
    }

    /* returns how many samples were actually available */
    size_t read(int16_t *dst, size_t count) {
        const uint64_t t = tail.load(std::memory_order_relaxed);
        const size_t have = availableToRead();
        const size_t n = have < count ? have : count;
        const size_t cap = capacity();
        for (size_t i = 0; i < n; i++)
            dst[i] = data[(size_t)((t + i) % cap)];
        tail.store(t + n, std::memory_order_release);
        return n;
    }
};

}  // namespace

struct PcmSink {
    std::mutex control;  // guards start/stop/flush and everything below
    std::shared_ptr<oboe::AudioStream> stream;
    std::shared_ptr<oboe::AudioStreamDataCallback> dataCallback;
    std::shared_ptr<oboe::AudioStreamErrorCallback> errorCallback;
    Ring ring;

    int sampleRate = 0;
    int channels = 0;
    int bytesPerSample = 0;

    std::atomic<uint64_t> framesWritten{0};  // by pcm_sink_write
    std::atomic<uint64_t> framesFed{0};      // handed to the Oboe stream
    std::atomic<uint64_t> underruns{0};
    std::atomic<bool> playing{false};
    int32_t burstFrames = 0;

    // conversion scratch, only touched by the writer
    std::vector<int16_t> scratch;

    oboe::DataCallbackResult onAudioReady(void *audioData, int32_t numFrames);
    void onStreamError();
    int startLocked(int sampleRate, int channels, int bytesPerSample);
};

namespace {

class SinkCallbacks : public oboe::AudioStreamDataCallback,
                      public oboe::AudioStreamErrorCallback {
public:
    explicit SinkCallbacks(PcmSink *sink) : mSink(sink) {}

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *, void *audioData,
                                          int32_t numFrames) override {
        return mSink->onAudioReady(audioData, numFrames);
    }

    void onErrorAfterClose(oboe::AudioStream *, oboe::Result error) override {
        LOGE("Oboe stream error: %s", oboe::convertToText(error));
        mSink->onStreamError();
    }

private:
    PcmSink *mSink;
};

}  // namespace

oboe::DataCallbackResult PcmSink::onAudioReady(void *audioData, int32_t numFrames) {
    auto *out = static_cast<int16_t *>(audioData);
    const size_t wanted = (size_t)numFrames * (size_t)channels;
    const size_t taken = ring.read(out, wanted);
    if (taken < wanted) {
        memset(out + taken, 0, (wanted - taken) * sizeof(int16_t));
        underruns.fetch_add(1, std::memory_order_relaxed);
    }
    framesFed.fetch_add((uint64_t)numFrames, std::memory_order_relaxed);
    return oboe::DataCallbackResult::Continue;
}

void PcmSink::onStreamError() {
    // device change (headset plugged in, route switched): the stream is gone, rebuild it
    std::lock_guard<std::mutex> lk(control);
    if (!playing.load(std::memory_order_relaxed)) return;
    LOGE("reopening the output after a device error");
    if (stream) {
        stream->close();
        stream.reset();
    }
    if (startLocked(sampleRate, channels, bytesPerSample) != 0)
        LOGE("reopen failed -- audio stops until the next session");
}

PcmSink *pcm_sink_create(void) { return new PcmSink(); }

void pcm_sink_destroy(PcmSink *sink) {
    if (!sink) return;
    pcm_sink_stop(sink);
    delete sink;
}

int pcm_sink_start(PcmSink *sink, int sample_rate, int channels, int bytes_per_sample) {
    if (!sink) return -1;
    std::lock_guard<std::mutex> lk(sink->control);
    return sink->startLocked(sample_rate, channels, bytes_per_sample);
}

int PcmSink::startLocked(int sample_rate, int channels, int bytes_per_sample) {
    if (sample_rate <= 0 || channels <= 0 || bytes_per_sample <= 0) return -1;

    // same format and a live stream: nothing to do
    if (stream && this->sampleRate == sample_rate && this->channels == channels &&
        this->bytesPerSample == bytes_per_sample && playing.load())
        return 0;

    if (stream) {
        playing.store(false);
        stream->stop();
        stream->close();
        stream.reset();
    }

    this->sampleRate = sample_rate;
    this->channels = channels;
    this->bytesPerSample = bytes_per_sample;
    const size_t samples = (size_t)sample_rate * kBufferSeconds * (size_t)channels;
    ring.reset(samples);
    scratch.resize((size_t)sample_rate * (size_t)channels / 4);
    framesWritten.store(0);
    framesFed.store(0);
    underruns.store(0);

    auto callbacks = std::make_shared<SinkCallbacks>(this);

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setSharingMode(oboe::SharingMode::Shared)
        ->setFormat(oboe::AudioFormat::I16)
        ->setChannelCount(channels)
        ->setSampleRate(sample_rate)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
        ->setContentType(oboe::ContentType::Music)
        ->setUsage(oboe::Usage::Media)
        ->setPerformanceMode(oboe::PerformanceMode::PowerSaving)
        ->setDataCallback(callbacks)
        ->setErrorCallback(callbacks);

    oboe::Result r = builder.openStream(stream);
    if (r != oboe::Result::OK || !stream) {
        LOGE("cannot open the output stream: %s", oboe::convertToText(r));
        stream.reset();
        return -1;
    }
    dataCallback = callbacks;
    errorCallback = callbacks;

    // a few bursts of buffer keeps latency low without provoking underruns
    burstFrames = stream->getFramesPerBurst();
    if (burstFrames > 0) stream->setBufferSizeInFrames(burstFrames * 4);

    r = stream->requestStart();
    if (r != oboe::Result::OK) {
        LOGE("cannot start the output stream: %s", oboe::convertToText(r));
        stream->close();
        stream.reset();
        return -1;
    }
    playing.store(true);

    LOGI("PCM out: %d Hz, %d ch, %d-byte samples, buffer %d frames, burst %d",
         sample_rate, channels, bytes_per_sample, stream->getBufferSizeInFrames(),
         burstFrames);
    return 0;
}

void pcm_sink_stop(PcmSink *sink) {
    if (!sink) return;
    std::lock_guard<std::mutex> lk(sink->control);
    sink->playing.store(false);
    if (sink->stream) {
        sink->stream->stop();
        sink->stream->close();
        sink->stream.reset();
    }
    sink->dataCallback.reset();
    sink->errorCallback.reset();
    LOGI("PCM out stopped");
}

void pcm_sink_flush(PcmSink *sink) {
    if (!sink) return;
    std::lock_guard<std::mutex> lk(sink->control);
    // the reader may be mid-callback; resetting both indices drops what is queued
    const uint64_t h = sink->ring.head.load(std::memory_order_relaxed);
    sink->ring.tail.store(h, std::memory_order_release);
}

int pcm_sink_write(PcmSink *sink, const void *buf, int frames) {
    if (!sink || !buf || frames <= 0) return 0;
    if (!sink->playing.load(std::memory_order_relaxed)) return frames;  // swallowed, not stalled

    const int channels = sink->channels;
    const int bytesPerSample = sink->bytesPerSample;
    if (channels <= 0 || bytesPerSample <= 0) return 0;

    const size_t samplesWanted = (size_t)frames * (size_t)channels;

    // convert to interleaved int16 first: a partially accepted frame set would drift
    if (sink->scratch.size() < samplesWanted) sink->scratch.resize(samplesWanted);
    int16_t *dst = sink->scratch.data();

    if (bytesPerSample == 2) {
        memcpy(dst, buf, samplesWanted * sizeof(int16_t));
    } else if (bytesPerSample == 4) {
        const auto *src = static_cast<const int32_t *>(buf);
        for (size_t i = 0; i < samplesWanted; i++) dst[i] = (int16_t)(src[i] >> 16);
    } else if (bytesPerSample == 3) {
        const auto *src = static_cast<const uint8_t *>(buf);
        for (size_t i = 0; i < samplesWanted; i++) {
            int32_t v = (int32_t)((uint32_t)src[i * 3 + 2] << 24 | (uint32_t)src[i * 3 + 1] << 16 |
                                  (uint32_t)src[i * 3] << 8);
            dst[i] = (int16_t)(v >> 16);
        }
    } else {
        return 0;
    }

    // wait for room: shairport's player thread is not real-time, and blocking here is
    // how a real backend behaves once its device buffer is full
    size_t offset = 0;
    int waitedUs = 0;
    while (offset < samplesWanted) {
        const size_t room = sink->ring.availableToWrite();
        if (room == 0) {
            if (waitedUs >= kWriteTimeoutMs * 1000) break;
            usleep(kWritePollUs);
            waitedUs += kWritePollUs;
            continue;
        }
        const size_t chunk = (samplesWanted - offset) < room ? (samplesWanted - offset) : room;
        sink->ring.write(dst + offset, chunk);
        offset += chunk;
    }

    const int accepted = (int)(offset / (size_t)channels);
    if (accepted < frames)
        LOGW("dropped %d of %d frames -- output buffer stayed full", frames - accepted, frames);
    sink->framesWritten.fetch_add((uint64_t)accepted, std::memory_order_relaxed);
    return accepted;
}

int pcm_sink_delay_frames(PcmSink *sink, long *frames) {
    if (!sink || !frames) return -1;
    const size_t queued = sink->ring.availableToRead() / (size_t)(sink->channels > 0 ? sink->channels : 1);

    std::lock_guard<std::mutex> lk(sink->control);
    int64_t deviceFrames = -1;
    if (sink->stream && sink->playing.load(std::memory_order_relaxed)) {
        int64_t position = 0;
        int64_t timestamp = 0;
        if (sink->stream->getTimestamp(CLOCK_MONOTONIC, &position, &timestamp) == oboe::Result::OK)
            deviceFrames = (int64_t)sink->framesFed.load(std::memory_order_relaxed) - position;
    }
    if (deviceFrames < 0) deviceFrames = sink->burstFrames > 0 ? sink->burstFrames : 0;

    *frames = (long)(queued + (size_t)deviceFrames);
    return 0;
}
