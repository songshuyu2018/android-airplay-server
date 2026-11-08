# PCM-driven ambient light

The left glow responds to percussive attacks; it does not extrapolate a BPM clock.
Its original continuous radial gradients, cover palette, random drift and zoom are
retained. Strong attacks can still expand the left glow across the screen.

## References and implementation choices

- [LedFx audio analysis](https://github.com/LedFx/LedFx/blob/main/ledfx/effects/audio.py)
  separates aubio onset detection, tempo and bass-volume beat detection. Its
  frequency-power filter also distinguishes fast rise from slow decay.
- [aubio onset documentation](https://github.com/aubio/aubio/blob/master/doc/aubio.txt)
  describes spectral-flux onset detection, silence gating, hop size and minimum
  inter-onset interval.

This is an original small C++ implementation of those general DSP techniques,
without copying their code or adding a Python / aubio runtime dependency.

The previous implementation compared RMS values of eight already compressed
visualization bands every 50ms. The 1024-sample snapshot covered only 23ms at
44.1kHz, so a short attack could fall between snapshots. Two attack filters then
further delayed the light.

The audio callback now writes to a bounded 8192-sample atomic history. The existing
playout tap remains after timeline buffering and fade-in, rather than at RTP
arrival. The serialized JNI reader processes every 1024-sample Hann window at a
256-sample hop (5.8ms at 44.1kHz), including windows between display frames.
Positive changes in log magnitudes are measured independently in 40-250Hz,
250-2200Hz and 2200-8000Hz. Rolling mean / variance adapt the trigger threshold;
a silence floor and 95ms refractory interval limit noise and double triggers.
High-frequency-only attacks receive less weight. A running level reference retains
weak / strong attack differences. The first response is not delayed for peak search;
the strength may increase during the following 25ms of the same attack.

JNI returns 48 display bands plus a timestamp-decayed onset envelope. The screen
samples it once per display frame. The glow uses the envelope directly: immediate
attack, 200ms exponential release, no second attack smoothing. Display-spectrum
smoothing remains separate and uses elapsed time.

The callback does no FFT, allocation or blocking lock. Analysis uses a coherent
atomic snapshot; a concurrent write causes the snapshot to be discarded. Work per
read is capped at 12 hops. Longer UI stalls reset the detector at current audio,
rather than replaying stale beats. Disable / re-enable and sample-rate changes
reset the detector. Short end-of-stream gaps retain a soft envelope release.

This removes analysis and animation delay; it does not measure downstream speaker
or Bluetooth presentation latency. Device listening remains necessary to assess
end-to-end synchronization. Vocal attacks can also trigger an onset: this is a
music-reactive light rather than an isolated-drum classifier.

## Validation

`tests/audio/onset_test.cpp` drives the complete PCM / FFT / onset / envelope path:
44.1kHz and 48kHz, polling at 1/3/9 hops, 16ms noise strikes, decaying bass drums,
90/120/180 BPM, alternating weak/strong hits, silence, sustained tones, soft release,
disable/re-enable and bounded catch-up after a stall. Onset detection time is checked
against input onset plus the analysis-window / polling allowance. This is not a
measurement of physical speaker-to-display latency.

Build with C++17 and run alongside `tests/audio/spectrum_test.cpp`; CI runs both.
