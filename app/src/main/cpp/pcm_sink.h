#ifndef PCM_SINK_H
#define PCM_SINK_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * PCM output for shairport-sync.
 *
 * The engine in audio_engine.cpp takes compressed frames (ALAC/AAC) and owns the
 * timing of what it decodes; shairport-sync does its own decoding and hands over
 * finished PCM, so it gets its own output stage instead: a ring buffer written by
 * shairport's player thread and drained by an Oboe stream.  Keeping the two apart
 * means neither can disturb the other's timing.
 */

typedef struct PcmSink PcmSink;

PcmSink *pcm_sink_create(void);
void pcm_sink_destroy(PcmSink *sink);

/* (Re)start the output for this format.  Called when shairport configures the
 * backend; safe to call again with the same or a different format. */
int pcm_sink_start(PcmSink *sink, int sample_rate, int channels, int bytes_per_sample);

void pcm_sink_stop(PcmSink *sink);

/* Drop everything that has not been played yet (sender flush / seek). */
void pcm_sink_flush(PcmSink *sink);

/* Queue `frames` frames.  Blocks briefly while the buffer is full; returns the
 * number of frames accepted. */
int pcm_sink_write(PcmSink *sink, const void *buf, int frames);

/* Frames written but not yet played out -- shairport uses this as the backend
 * delay for its timing corrections.  Returns 0 and sets *frames, or -1. */
int pcm_sink_delay_frames(PcmSink *sink, long *frames);

#ifdef __cplusplus
}
#endif

#endif /* PCM_SINK_H */
