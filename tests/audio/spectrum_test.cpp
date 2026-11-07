#include "audio_spectrum.h"
#include <cassert>
#include <vector>
int main() {
 AudioSpectrum s;float result[48]{};std::vector<int16_t> pcm(4096*2);
 s.enabled(true);s.push(pcm.data(),4096,2);s.read(result,44100);
 for(float v:result)assert(v==0);
 for(int frame=0;frame<4096;++frame){int16_t v=(int16_t)(20000*std::sin(6.28318530718*440*frame/44100));pcm[frame*2]=pcm[frame*2+1]=v;}
 s.push(pcm.data(),4096,2);s.read(result,44100);
 int peak=std::max_element(result,result+48)-result;
 assert(peak>=16&&peak<=21);assert(result[peak]>.8f);
 s.enabled(false);s.read(result,44100);for(float v:result)assert(v==0);
}
