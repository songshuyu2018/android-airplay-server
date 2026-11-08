#ifndef AUDIO_ATTACK_RANGE_H
#define AUDIO_ATTACK_RANGE_H
#include <algorithm>
#include <cmath>
#include <cstdint>

// Calibrate against accepted attacks, not whole-track RMS or per-frame silence.
// Separate instances per frequency band preserve the user-selected band weights.
class AudioAttackRange {
public:
    float accept(float raw, uint64_t frame, int rate) {
        const double seconds = initialized ? (frame - lastFrame) / double(rate) : 0.;
        const float reference = std::max(.18f, peak * float(std::exp(-seconds / 8.)));
        // 80% is a reference-strength hit; reserve headroom for exceptional hits.
        // The absolute floor prevents very small fluctuations becoming heavy beats.
        const float value = .8f * std::max(0.f, raw - .01f) / std::max(.17f, reference - .01f);
        peak = std::max(reference, raw);
        lastFrame = frame;
        initialized = true;
        return std::clamp(value, 0.f, 1.f);
    }
private:
    float peak = .18f;
    uint64_t lastFrame = 0;
    bool initialized = false;
};
#endif
