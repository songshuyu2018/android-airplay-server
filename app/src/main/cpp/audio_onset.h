#ifndef AUDIO_ONSET_H
#define AUDIO_ONSET_H
#include <array>
#include <algorithm>
#include <cmath>
#include <cstdint>

// Original positive spectral flux / adaptive threshold implementation.
// Design references (no source copied): aubio specflux and LedFx's separate
// onset / volume-beat paths. Detects audible attacks, not a BPM metronome.
class AudioOnset {
public:
    static constexpr size_t BINS = 513;
    void reset() { *this = AudioOnset{}; }
    void process(const std::array<float, BINS>& magnitude, float rms,
                 uint64_t frame, int rate, int hop) {
        std::array<float, 3> flux{};
        std::array<int, 3> counts{};
        for (size_t k = 1; k < BINS; ++k) {
            const float hz = k * rate / 1024.f;
            const float value = std::log1p(64.f * magnitude[k]);
            const int group = hz < 250.f ? 0 : hz < 2200.f ? 1 : 2;
            if (hz >= 40.f && hz < 8000.f) {
                flux[group] += std::max(0.f, value - previous[k]);
                ++counts[group];
            }
            previous[k] = value;
        }
        const float dt = hop / float(rate);
        level = std::max(rms, level * std::exp(-dt / 2.f));
        float score = 0;
        for (int g = 0; g < 3; ++g) {
            flux[g] /= std::sqrt(float(std::max(1, counts[g])));
            const float threshold = .003f + mean[g] * 1.6f + .8f * std::sqrt(variance[g]);
            // Bass and snare drive the broad light; high hats are secondary.
            score = std::max(score, flux[g] / threshold * (g == 2 ? .45f : 1.f));
        }
        const float strength = std::clamp(std::sqrt(rms / std::max(.08f, level)), 0.f, 1.f);
        if (primed && rms > .0018f && score > 1.f &&
            (!hasEvent || frame - eventFrame >= uint64_t(rate * .095f))) {
            eventFrame = frame;
            eventStrength = strength;
            hasEvent = true;
            ++sequence;
        } else if (hasEvent && frame - eventFrame < uint64_t(rate * .025f)) {
            // Retain attack peak strength without delaying the first response.
            eventStrength = std::max(eventStrength, strength);
        }
        const float alpha = 1.f - std::exp(-dt / .45f);
        for (int g = 0; g < 3; ++g) {
            const float delta = flux[g] - mean[g];
            mean[g] += alpha * delta;
            variance[g] += alpha * (delta * delta - variance[g]);
        }
        primed = true;
    }
    bool hasEvent = false;
    uint64_t eventFrame = 0, sequence = 0;
    float eventStrength = 0;
private:
    std::array<float, BINS> previous{};
    std::array<float, 3> mean{}, variance{};
    float level = .08f;
    bool primed = false;
};
#endif
