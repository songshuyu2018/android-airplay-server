#ifndef AUDIO_SPECTRUM_H
#define AUDIO_SPECTRUM_H
#include <array>
#include <atomic>
#include <algorithm>
#include <cmath>
#include <complex>
#include "audio_time.h"
// Single callback writer. Atomic samples avoid locks and allocation on audio thread.
class AudioSpectrum {
public:
 static constexpr size_t N=1024;
 static constexpr int BANDS=48;
 void enabled(bool v) { mEnabled.store(v); }
 void push(const int16_t *pcm,size_t frames,int channels) {
  if(!mEnabled.load())return;
  size_t cursor=mCursor.load();mWriting.store(true);
  for(size_t i=0;i<frames;++i){int32_t mono=0;for(int c=0;c<channels;++c)mono+=pcm[i*channels+c];mSamples[(cursor++)%N].store((int16_t)(mono/channels));}
  mCursor.store(cursor);mLastPush.store(monoNs());mWriting.store(false);
 }
 void read(float *bands,int sampleRate) const {
  std::fill(bands,bands+BANDS,0.f);
  if(!mEnabled.load()||monoNs()-mLastPush.load()>200000000LL||mWriting.load())return;
  std::array<std::complex<float>,N> values;size_t cursor=mCursor.load();if(cursor<N)return;
  for(size_t i=0;i<N;++i){float window=.5f-.5f*std::cos(6.28318530718f*i/(N-1));values[i]=mSamples[(cursor+i)%N].load()/32768.f*window;}
  if(mWriting.load()||cursor!=mCursor.load())return;
  for(size_t i=1,j=0;i<N;++i){size_t bit=N>>1;for(;j&bit;bit>>=1)j^=bit;j^=bit;if(i<j)std::swap(values[i],values[j]);}
  for(size_t len=2;len<=N;len<<=1){float angle=-6.28318530718f/len;std::complex<float>step(std::cos(angle),std::sin(angle));for(size_t i=0;i<N;i+=len){std::complex<float>w(1,0);for(size_t j=0;j<len/2;++j){auto u=values[i+j];auto v=values[i+j+len/2]*w;values[i+j]=u+v;values[i+j+len/2]=u-v;w*=step;}}}
  float upper=std::min(16000.f,sampleRate*.45f);
  for(int b=0;b<BANDS;++b){int lo=std::max(1,(int)(45.f*std::pow(upper/45.f,b/(float)BANDS)*N/sampleRate));int hi=std::min((int)N/2,std::max(lo+1,(int)(45.f*std::pow(upper/45.f,(b+1.f)/BANDS)*N/sampleRate)));float magnitude=0;for(int k=lo;k<hi;++k)magnitude=std::max(magnitude,std::abs(values[k])*4.f/N);float db=20.f*std::log10(std::max(magnitude,1e-8f));bands[b]=std::clamp((db+70.f)/60.f,0.f,1.f);}
 }
private:
 std::array<std::atomic<int16_t>,N>mSamples{};
 std::atomic<size_t>mCursor{0};std::atomic<bool>mEnabled{false},mWriting{false};std::atomic<int64_t>mLastPush{0};
};
#endif
