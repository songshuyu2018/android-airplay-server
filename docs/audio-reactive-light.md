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
a silence floor and 140ms refractory interval limit noise and double triggers.
High-frequency-only attacks receive less weight. A running level reference retains
weak / strong attack differences. The first response is not delayed for peak search;
the strength may increase during the following 35ms of the same attack.

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

## Conservative strength mapping

Onset presence and visual strength are distinct. Loudness relative to a rolling
peak alone promoted sustained vocals/chords to almost-full strength. Strength now
combines spectral novelty, fast band-energy rise, contrast above a 350ms band
background, and linear level weight. Bass strength is additionally weighted by
its share of the spectrum, so broadband noise does not masquerade as a kick.
Mid-frequency-only and high-frequency-only contributions are capped at 0.32 and
0.08. Sub-threshold weak attacks can be intentionally ignored. A strong bass
attack can upgrade a preceding small accent during the refractory interval.

The renderer no longer boosts 0.8 to full scale. Color response is squared; large
expansion begins only above 0.55 and follows a quadratic knee up to 1. This keeps
ordinary accents local while preserving full-screen reach for strong bass hits.
Additional PCM fixtures check steady noise, slow bass swells, mid-frequency
accents and a kick following an accent inside the refractory interval.

## On-screen tuning (temporary calibration UI)

The lower-right floating `光效调节` button opens a bounded, scrollable panel. It
never reserves space in the existing player layout. Sliders show their live integer
values; two meters remain visible above the scroll area (native envelope and mapped
visual strength). Changes apply without restarting playback. Values are saved after
dragging / closing / leaving the screen. Reset restores the calibration defaults;
Copy exports all eight exact values for later fixing in code.

| Slider | Range | Initial value |
| --- | --- | --- |
| Detection sensitivity | 50-400% | 150% |
| Visual gain | 50-800% | 250% |
| Expansion knee | 0-90% | 25% |
| Maximum expansion extent | 0-200% | 100% |
| Release time constant | 80-600ms | 240ms |
| Mid-frequency weight | 0-100% | 60% |
| High-frequency weight | 0-100% | 20% |
| Minimum trigger interval | 60-300ms | 140ms |

Sensitivity, frequency weights and interval reach the native onset detector through
JNI; release reaches the native timestamp envelope. Gain, knee and maximum extent
act only on drawing. Extent 100% is the existing screen-covering target radius;
0% retains the drifting rest-size glow, 200% doubles the target radius. It never
shrinks the rest-size glow. The release control is an exponential time constant,
not a fixed duration until fully dark.

The strict four-way product suppressed mixed/mastered tracks too much. Novelty
still gates attacks, but rise/contrast now form a blended quality weight, retaining
a modest response instead of multiplying every sub-maximal measure towards zero.
Native sensitivity still acts before rejection, so it can recover weak attacks that
post-detection visual gain cannot. Changing it preserves analysis history; resets
preserve tuning. PCM tests check this native sensitivity behavior and reset handling.
