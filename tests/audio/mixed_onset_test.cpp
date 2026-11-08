#include "audio_spectrum.h"
#include <array>
#include <cassert>
#include <cmath>
#include <iostream>
#include <random>
#include <vector>

// Percussion embedded in an already-playing, louder harmonic/noise bed.
// Silence-separated drum fixtures cannot expose suppression by background levels.
int main() {
    // Once calibrated, light/medium/heavy attacks occupy comparable ranges even
    // when the source's attack amplitudes differ by 4x. Noise stays small.
    AudioAttackRange quiet, loud;
    quiet.accept(.2f, 44100, 44100); loud.accept(.8f, 44100, 44100);
    float previous = 0;
    for (float quality : {.05f, .1f, .2f}) {
        const float a = quiet.accept(quality, 44101, 44100);
        const float b = loud.accept(quality * 4.f, 44101, 44100);
        assert(std::abs(a-b) < .04f);
        assert(a > previous); previous = a;
    }
    AudioAttackRange noiseRange;
    for (int i=0;i<100;++i) assert(noiseRange.accept(.02f, i*256, 44100)<.05f);

    for (int rate : {44100, 48000}) for (int blockSize : {256, 4096, 6144, 8192}) {
        AudioSpectrum spectrum; spectrum.enabled(true);
        spectrum.configure({4.f, .6f, .2f, 140, 240});
        std::mt19937 random(19);
        std::uniform_real_distribution<double> noise(-1,1);
        std::vector<int16_t> pcm(blockSize);
        std::array<float,10> peaks{};
        float bands[48], pulse;
        for(int frame=0; frame<rate*7; frame+=blockSize) {
            for(int i=0;i<blockSize;++i) {
                const double t=(frame+i)/double(rate);
                double sample=.15*std::sin(6.28318530718*110*t)+
                    .16*std::sin(6.28318530718*330*t)+.08*std::sin(6.28318530718*660*t)+.018*noise(random);
                for(int beat=0;beat<10;++beat) {
                    const double age=t-(1.+beat*.5);
                    if(age>=0&&age<.1)
                        sample+=.24*std::exp(-age/.03)*std::sin(6.28318530718*65*age);
                }
                pcm[i]=int16_t(std::tanh(sample*1.8)*16000);
            }
            const int64_t now=1000000000LL+int64_t((frame+blockSize)*(1e9/rate));
            spectrum.push(pcm.data(),pcm.size(),1,now);
            spectrum.read(bands,rate,&pulse,now);
            const double t=(frame+blockSize)/double(rate);
            for(int beat=0;beat<10;++beat)
                if(t>=1.+beat*.5&&t<1.025+blockSize/double(rate)+beat*.5) peaks[beat]=std::max(peaks[beat],pulse);
        }
        int visible=0;
        for(float peak:peaks) { std::cout<<peak<<" "; if(peak>.20f)++visible; }
        std::cout<<rate<<" Hz block "<<blockSize<<": "<<visible<<"/10 visible mixed attacks"<<std::endl;
        assert(visible>=8);
    }
}
