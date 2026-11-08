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
            if (kind == 4) sample = .12 * noise(random); // steady broadband texture
            if (kind == 5) sample = .4 * (.5 + .5 * std::sin(t * 1.8)) *
                std::sin(6.28318530718 * 83 * t); // slow bass swell
            if (kind == 2 || kind == 3 || kind == 6) {
                for (int beat = 0; beat < 9; ++beat) {
                    const double age = t - (1. + beat * period);
                    const double duration = kind == 3 ? .016 : .12;
                    if (age >= 0 && age < duration) {
                        const double amplitude = beat % 2 ? .12 : .8;
                        sample += amplitude * std::exp(-age / .035) *
                            (kind == 3 ? noise(random) : std::sin(6.28318530718 * (kind == 6 ? 800 : 65) * age));
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
        if (pulse > previous + .012f && (hits.empty() || t - hits.back().time > .15))
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
            // Deliberately allow weak strikes below the new visual gate to vanish.
            assert(hits.size() >= 5 && hits.size() <= 9);
            bool strong[5]{};
            for (const auto& hit : hits) {
                const int index = int(std::round((hit.time - 1.) / .5));
                assert(index >= 0 && index < 9);
                const double delay = hit.time - (1. + index * .5);
                assert(delay >= 0 && delay < .025 + poll * 256. / rate);
                if (index % 2 == 0) strong[index / 2] = true;
                else assert(hit.strength < .4f);
                if (kind == 3) assert(hit.strength <= .321f); // broadband != bass drum
            }
            for (bool detected : strong) assert(detected);
        }
    }
    for (double period : {.333333, .666667}) {
        auto hits = run(44100, 3, 2, period);
        bool strong[5]{};
        for (const auto& hit : hits) {
            const int index = int(std::round((hit.time - 1.) / period));
            assert(index >= 0 && index < 9);
            assert(std::abs(hit.time - (1. + index * period)) < .045);
            if (index % 2 == 0) strong[index / 2] = true;
        }
        for (bool detected : strong) assert(detected); // 180 / 90 BPM
    }
    for (int kind : {4, 5, 6}) {
        auto hits = run(44100, 3, kind);
        for (auto hit : hits) assert(hit.strength <= (kind == 5 ? .15f : .321f));
    }
    // A modest mid-frequency accent 60ms before a kick cannot suppress that kick.
    {
        AudioSpectrum detector; detector.enabled(true);
        std::array<int16_t, 256> pcm{};
        float bands[48], pulse = 0, kick = 0;
        for (int frame = 0; frame < 44100 * 1.3; frame += 256) {
            for (int i = 0; i < 256; ++i) {
                const double t = (frame + i) / 44100.;
                double sample = 0;
                if (t >= .94 && t < .97) sample = .2 * std::sin(6.28318530718 * 800 * t);
                if (t >= 1 && t < 1.12) sample += .8 * std::exp(-(t-1)/.035) * std::sin(6.28318530718 * 65 * (t-1));
                pcm[i] = int16_t(sample * 32767);
            }
            const int64_t now = 1000000000LL + int64_t((frame + 256) * (1e9 / 44100));
            detector.push(pcm.data(), pcm.size(), 1, now);
            detector.read(bands, 44100, &pulse, now);
            if (frame / 44100. >= 1.) kick = std::max(kick, pulse);
        }
        assert(kick > .55f);
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
