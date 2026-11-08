#ifndef AUDIO_SPECTRUM_H
#define AUDIO_SPECTRUM_H
#include <array>
#include <atomic>
#include <algorithm>
#include <cmath>
#include <complex>
#include "audio_time.h"
#include "audio_onset.h"

// Single callback writer; one serialized reader (AudioEngine rebuild lock).
// Audio callback only copies PCM: no FFT, allocation or blocking lock.
class AudioSpectrum {
public:
    static constexpr size_t N = 1024, HOP = 256, CAPACITY = 8192, MAX_HOPS = 12;
    static constexpr int BANDS = 48, VALUES = BANDS + 1; // final value: onset envelope
    void configure(const AudioLightConfig& config) {
        mOnset.configure(config);
        mRelease = std::clamp(config.releaseMs, 80, 600) / 1000.f;
    }
    void enabled(bool value) {
        if (mEnabled.exchange(value) != value) {
            mStart = mReadEnd = mCursor.load();
            mRate = 0;
            mPulseTime = 0;
            mPulseStrength = 0;
            mOnset.reset();
            mBands.fill(0);
        }
    }
    // Timestamp is the monotonic time of this playout block; injectable for PCM tests.
    void push(const int16_t* pcm, size_t frames, int channels, int64_t timestamp = monoNs()) {
        if (!mEnabled.load() || channels <= 0 || frames == 0) return;
        size_t cursor = mCursor.load();
        mWriting.store(true);
        for (size_t i = 0; i < frames; ++i) {
            int32_t mono = 0;
            for (int c = 0; c < channels; ++c) mono += pcm[i * channels + c];
            mSamples[(cursor++) % CAPACITY].store(int16_t(mono / channels), std::memory_order_relaxed);
        }
        mLastPush.store(timestamp);
        mCursor.store(cursor);
        mWriting.store(false);
    }
    void read(float* bands, int sampleRate, float* pulse = nullptr, int64_t now = monoNs()) {
        std::fill(bands, bands + BANDS, 0.f);
        if (pulse) *pulse = 0;
        if (!mEnabled.load() || sampleRate <= 0) return;
        const size_t cursor = mCursor.load();
        const int64_t pushed = mLastPush.load();
        if (now - pushed > 200000000LL) {
            mStart = mReadEnd = cursor;
            mOnset.reset();
            mBands.fill(0);
            if (pulse) *pulse = envelope(now);
            return;
        }
        if (mRate != sampleRate) {
            if (mRate != 0) {
                mStart = mReadEnd = cursor;
                mPulseTime = 0;
            }
            mOnset.reset(); mRate = sampleRate;
        }
        const size_t end = cursor - (cursor - mStart) % HOP;
        size_t first = mReadEnd + HOP;
        if (first < mStart + N) first = mStart + N;
        if (end >= first && !mWriting.load()) {
            if (end - first >= MAX_HOPS * HOP) {
                // UI stall: prime at current audio, never replay old beats.
                first = end;
                mOnset.reset();
                mPulseTime = 0;
            }
            const size_t begin = first - N;
            const size_t count = end - begin;
            std::array<int16_t, N + MAX_HOPS * HOP> snapshot{};
            for (size_t i = 0; i < count; ++i)
                snapshot[i] = mSamples[(begin + i) % CAPACITY].load(std::memory_order_relaxed);
            // Drop a concurrent copy rather than block the audio thread.
            if (!mWriting.load() && cursor == mCursor.load()) {
                for (size_t at = first; at <= end; at += HOP)
                    analyse(snapshot.data() + at - N - begin, at, sampleRate);
                mReadEnd = end;
            }
        }
        std::copy(mBands.begin(), mBands.end(), bands);
        if (mOnset.hasEvent) {
            if (mPulseTime == 0 || mPulseFrame != mOnset.eventFrame) {
                mPulseFrame = mOnset.eventFrame;
                mPulseTime = pushed - int64_t((cursor - mOnset.eventFrame) * (1e9 / sampleRate));
            }
            mPulseStrength = mOnset.eventStrength;
        }
        if (pulse) *pulse = envelope(now);
    }
private:
    float envelope(int64_t now) const {
        if (!mPulseTime) return 0;
        const float age = std::max(0.f, float((now - mPulseTime) / 1e9));
        // Timestamped release continues across short underflows/end of PCM.
        return age > 1.5f ? 0.f : mPulseStrength * std::exp(-age / mRelease);
    }
    void analyse(const int16_t* pcm, size_t frame, int rate) {
        std::array<std::complex<float>, N> values;
        float power = 0;
        for (size_t i = 0; i < N; ++i) {
            const float sample = pcm[i] / 32768.f;
            power += sample * sample;
            values[i] = sample * window()[i];
        }
        for (size_t i = 1, j = 0; i < N; ++i) {
            size_t bit = N >> 1;
            for (; j & bit; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) std::swap(values[i], values[j]);
        }
        for (size_t len = 2; len <= N; len <<= 1) {
            const float angle = -6.28318530718f / len;
            const std::complex<float> step(std::cos(angle), std::sin(angle));
            for (size_t i = 0; i < N; i += len) {
                std::complex<float> w(1, 0);
                for (size_t j = 0; j < len / 2; ++j) {
                    const auto u = values[i + j], v = values[i + j + len / 2] * w;
                    values[i + j] = u + v; values[i + j + len / 2] = u - v; w *= step;
                }
            }
        }
        std::array<float, AudioOnset::BINS> magnitude{};
        for (size_t k = 0; k < magnitude.size(); ++k) magnitude[k] = std::abs(values[k]) * 4.f / N;
        mOnset.process(magnitude, std::sqrt(power / N), frame, rate, HOP);
        const float upper = std::min(16000.f, rate * .45f);
        for (int b = 0; b < BANDS; ++b) {
            const int lo = std::max(1, int(45.f * std::pow(upper / 45.f, b / float(BANDS)) * N / rate));
            const int hi = std::min(int(N / 2), std::max(lo + 1,
                int(45.f * std::pow(upper / 45.f, (b + 1.f) / BANDS) * N / rate)));
            float peak = 0;
            for (int k = lo; k < hi; ++k) peak = std::max(peak, magnitude[k]);
            mBands[b] = std::clamp((20.f * std::log10(std::max(peak, 1e-8f)) + 70.f) / 60.f, 0.f, 1.f);
        }
    }
    static const std::array<float, N>& window() {
        static const auto result = [] {
            std::array<float, N> values{};
            for (size_t i = 0; i < N; ++i) values[i] = .5f - .5f * std::cos(6.28318530718f * i / (N - 1));
            return values;
        }();
        return result;
    }
    std::array<std::atomic<int16_t>, CAPACITY> mSamples{};
    std::atomic<size_t> mCursor{0};
    std::atomic<bool> mEnabled{false}, mWriting{false};
    std::atomic<int64_t> mLastPush{0};
    size_t mStart = 0, mReadEnd = 0;
    int mRate = 0;
    int64_t mPulseTime = 0;
    uint64_t mPulseFrame = 0;
    float mPulseStrength = 0;
    float mRelease = .20f;
    AudioOnset mOnset;
    std::array<float, BANDS> mBands{};
};
#endif
