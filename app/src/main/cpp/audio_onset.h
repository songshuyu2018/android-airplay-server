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
        std::array<float, 3> power{};
        for (size_t k = 1; k < BINS; ++k) {
            const float hz = k * rate / 1024.f;
            const float value = std::log1p(64.f * magnitude[k]);
            const int group = hz < 250.f ? 0 : hz < 2200.f ? 1 : 2;
            if (hz >= 40.f && hz < 8000.f) {
                flux[group] += std::max(0.f, value - previous[k]);
                power[group] += magnitude[k] * magnitude[k];
                ++counts[group];
            }
            previous[k] = value;
        }
        const float dt = hop / float(rate);
        level = std::max(rms, level * std::exp(-dt / 2.f));
        const float bassShare = power[0] / std::max(1e-8f, power[0] + power[1] + power[2]);
        float strength = 0;
        for (int g = 0; g < 3; ++g) {
            flux[g] /= std::sqrt(float(std::max(1, counts[g])));
            const float threshold = .003f + mean[g] * 2.f + 1.2f * std::sqrt(variance[g]);
            const float score = flux[g] / threshold;
            const float energy = std::sqrt(power[g]);
            const float novelty = std::clamp((score - 1.1f) / 3.f, 0.f, 1.f);
            const float rise = std::clamp((energy - previousEnergy[g]) /
                std::max(.008f, energy * .25f), 0.f, 1.f);
            const float contrast = std::clamp((energy - energyMean[g] * 1.2f) /
                std::max(.02f, energyMean[g]), 0.f, 1.f);
            const float loudness = std::clamp(rms / std::max(.12f, level), 0.f, 1.f);
            // A loud sustained sound is not a heavy beat. Require spectral novelty,
            // a fast band-energy attack and contrast against its recent background.
            // Only a bass attack may drive full-screen expansion; vocals/high hats
            // remain small even when they are loud.
            const float weight = g == 0 ? std::clamp(bassShare / .45f, 0.f, 1.f) : g == 1 ? .32f : .08f;
            strength = std::max(strength, novelty * rise * contrast * loudness * weight);
            previousEnergy[g] = energy;
            energyMean[g] += (1.f - std::exp(-dt / .35f)) * (energy - energyMean[g]);
        }
        // A small consonant/hat immediately before a kick must not lock it out.
        const bool upgrade = hasEvent && eventStrength < .4f && strength > .6f &&
            frame - eventFrame >= uint64_t(rate * .035f);
        if (primed && rms > .0018f && strength > .025f &&
            (!hasEvent || frame - eventFrame >= uint64_t(rate * .14f) || upgrade)) {
            eventFrame = frame;
            eventStrength = strength;
            hasEvent = true;
            ++sequence;
        } else if (hasEvent && frame - eventFrame < uint64_t(rate * .035f)) {
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
    std::array<float, 3> mean{}, variance{}, previousEnergy{}, energyMean{};
    float level = .08f;
    bool primed = false;
};
#endif
