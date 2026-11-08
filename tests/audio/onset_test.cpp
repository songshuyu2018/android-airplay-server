#include "audio_spectrum.h"
#include <cassert>
#include <iostream>
#include <vector>
#include <random>
#include <chrono>
#include <thread>

// Exercise real PCM -> overlapping FFT -> adaptive onset -> timestamped envelope.
struct Hit { double time; float strength; };
std::vector<Hit> run(int rate, int pollHops, int kind, double period = .5) {
    AudioSpectrum s;
    s.enabled(true);
    float bands[48], pulse = 0, previous = 0;
    std::vector<Hit> hits;
    std::mt19937 random(7);
    std::uniform_real_distribution<float> noise(-1.f, 1.f);
    std::array<int16_t, 256> pcm;
    for (int frame = 0, block = 0; frame < rate * (1.5 + 9 * period); frame += 256, ++block) {
        for (int i = 0; i < 256; ++i) {
            const double t = (frame + i) / double(rate);
            double sample = 0;
            if (kind == 1) sample = .3 * std::sin(6.28318530718 * 83 * t);
            if (kind == 2 || kind == 3) {
                for (int beat = 0; beat < 9; ++beat) {
                    const double age = t - (1. + beat * period);
                    const double duration = kind == 3 ? .016 : .12;
                    if (age >= 0 && age < duration) {
                        const double amplitude = beat % 2 ? .12 : .8;
                        sample += amplitude * std::exp(-age / .035) *
                            (kind == 3 ? noise(random) : std::sin(6.28318530718 * 65 * age));
                    }
                }
            }
            pcm[i] = int16_t(std::clamp(sample, -1., 1.) * 32767);
        }
        const int64_t now = 1000000000LL + int64_t((frame + 256) * (1e9 / rate));
        s.push(pcm.data(), pcm.size(), 1, now);
        if (block % pollHops) continue;
        s.read(bands, rate, &pulse, now);
        const double t = (frame + 256) / double(rate);
        assert(std::isfinite(pulse) && pulse >= 0 && pulse <= 1);
        if (pulse > previous + .09f && (hits.empty() || t - hits.back().time > .15))
            hits.push_back({t, pulse});
        else if (!hits.empty() && t - hits.back().time < .06)
            hits.back().strength = std::max(hits.back().strength, pulse);
        previous = pulse;
    }
    return hits;
}
int main() {
    for (int rate : {44100, 48000}) for (int poll : {1, 3, 9}) {
        assert(run(rate, poll, 0).empty()); // silence
        auto steady = run(rate, poll, 1);
        for (auto hit : steady) assert(hit.time < .10); // sustained tone isn't a drum
        for (int kind : {2, 3}) {
            auto hits = run(rate, poll, kind);
            std::cout << rate << " Hz, poll " << poll << ", kind " << kind << ": " << hits.size() << " hits" << std::endl;
            assert(hits.size() == 9);
            for (int i = 0; i < 9; ++i) {
                const double delay = hits[i].time - (1. + i * .5);
                assert(delay >= 0 && delay < .025 + poll * 256. / rate);
                if (i % 2) assert(hits[i-1].strength > hits[i].strength * 1.15f);
            }
        }
    }
    for (double period : {.333333, .666667}) {
        auto hits = run(44100, 3, 2, period);
        assert(hits.size() == 9); // 180 / 90 BPM, no fixed 120 BPM clock
        for (int i = 0; i < 9; ++i)
            assert(std::abs(hits[i].time - (1. + i * period)) < .045);
    }
    // Disabling and restarting must not replay the previous PCM or pulse.
    AudioSpectrum s;
    std::vector<int16_t> data(1024, 0);
    float bands[48], pulse;
    s.enabled(true); s.push(data.data(), data.size(), 1); s.read(bands, 44100, &pulse);
    for (size_t i = 0; i < data.size(); ++i)
        data[i] = int16_t(20000 * std::sin(6.28318530718 * 65 * i / 44100.));
    s.push(data.data(), data.size(), 1); s.read(bands, 44100, &pulse);
    assert(pulse > .5f);
    const float attack = pulse;
    std::this_thread::sleep_for(std::chrono::milliseconds(230));
    s.read(bands, 44100, &pulse);
    assert(pulse > 0 && pulse < attack * .4f); // soft release after PCM stops
    s.enabled(false); s.read(bands, 44100, &pulse); assert(pulse == 0);
    s.enabled(true); s.read(bands, 44100, &pulse); assert(pulse == 0);
    // Catch-up work is bounded, stale buffered transients must not flash on resume.
    data.resize(16000, 0);
    s.push(data.data(), data.size(), 1); s.read(bands, 44100, &pulse);
    assert(pulse == 0);
    std::cout << "PCM onset tests passed" << std::endl;
}
