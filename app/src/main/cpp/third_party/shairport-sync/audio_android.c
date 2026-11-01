/*
 * Android audio backend for shairport-sync.
 *
 * shairport-sync hands this backend already-decoded PCM.  The app plays it through
 * its own Oboe stream (pcm_sink.cpp) instead of a system sound server, so the
 * callbacks below are plain C functions implemented by the JNI bridge.
 */
#include "audio.h"
#include "common.h"
#include "config.h"

#include <errno.h>
#include <memory.h>
#include <stdio.h>
#include <stdlib.h>

/* implemented by the JNI bridge (native_bridge.cpp) */
extern int airplay_android_audio_write(void *buf, int samples, int bytes_per_frame,
                                       uint32_t timestamp, uint64_t playtime);
extern int airplay_android_audio_config(unsigned int rate, unsigned int channels,
                                        unsigned int bytes_per_sample);
extern void airplay_android_audio_stop(void);
extern void airplay_android_audio_flush(void);

static unsigned int bytes_per_frame = 0;
static unsigned int output_rate = 0;
static unsigned int output_channels = 0;
static unsigned int output_bytes_per_sample = 0;
static int output_configured = 0;
static int output_running = 0;

static int play(void *buf, int samples, __attribute__((unused)) int sample_type,
                uint32_t timestamp, uint64_t playtime) {
  if (bytes_per_frame == 0) {
    debug(1, "android: bytes per frame not initialised before play()!");
    return -1;
  }
  /* configure() is only called when the output format changes, so a second session
   * inherits the format from the first one without being configured again -- while the
   * sink was stopped when the first session ended.  Re-arm it here rather than staying
   * silent. */
  if (!output_running && output_configured) {
    if (airplay_android_audio_config(output_rate, output_channels, output_bytes_per_sample) == 0)
      output_running = 1;
  }
  return airplay_android_audio_write(buf, samples, (int) bytes_per_frame, timestamp, playtime);
}

static int init(__attribute__((unused)) int argc, __attribute__((unused)) char **argv) {
  config.audio_backend_buffer_desired_length = 0.2;
  config.audio_backend_latency_offset = 0;
  /* Accept what the senders actually offer -- S16/44100 for classic AirPlay and
   * S32/48000 for AirPlay 2 -- so neither path has to be resampled. */
  parse_audio_options("android", (1 << SPS_FORMAT_S32_LE) | (1 << SPS_FORMAT_S16_LE),
                      (1 << SPS_RATE_48000) | (1 << SPS_RATE_44100), (1 << 2));
  return 0;
}

static void deinit(void) { airplay_android_audio_stop(); }

static int32_t get_configuration(unsigned int channels, unsigned int rate, unsigned int format) {
  return search_for_suitable_configuration(channels, rate, format, NULL);
}

static int configure(int32_t requested_encoded_format, __attribute__((unused)) char **channel_map) {
  unsigned int channels = CHANNELS_FROM_ENCODED_FORMAT(requested_encoded_format);
  unsigned int rate = RATE_FROM_ENCODED_FORMAT(requested_encoded_format);
  unsigned int bytes_per_sample =
      sps_format_sample_size(FORMAT_FROM_ENCODED_FORMAT(requested_encoded_format));
  if (bytes_per_sample == 0) {
    debug(1, "android: unknown output format.");
    bytes_per_sample = 2;
  }
  bytes_per_frame = bytes_per_sample * channels;
  output_rate = rate;
  output_channels = channels;
  output_bytes_per_sample = bytes_per_sample;
  output_configured = 1;
  debug(1, "android: output configuration %u Hz, %u channels, %u bytes per sample.", rate, channels,
        bytes_per_sample);
  int rc = airplay_android_audio_config(rate, channels, bytes_per_sample);
  output_running = (rc == 0);
  return rc;
}

static void stop(void) {
  output_running = 0;
  airplay_android_audio_stop();
}

static void flush(void) { airplay_android_audio_flush(); }

audio_output audio_android = {.name = "android",
                              .help = NULL,
                              .init = &init,
                              .deinit = &deinit,
                              .prepare = NULL,
                              .get_configuration = &get_configuration,
                              .configure = &configure,
                              .play = &play,
                              .stop = &stop,
                              .is_running = NULL,
                              .flush = &flush,
                              /* delay deliberately not implemented.  With a delay() shairport-sync
                               * only leaves its buffering state once the reported device delay
                               * converges on its target (player.c: the `if (config.output->delay)`
                               * branch never clears conn->ab_buffering on our callbacks; the
                               * `else` branch does).  A sink that cannot report a real device
                               * clock -- which Oboe's getTimestamp() only sometimes can -- is
                               * better off letting shairport pace itself, exactly as the pipe and
                               * stdout backends do.  Measured: with delay() the player sent 11 s
                               * of dither-only silence and no audio at all; without it the same
                               * session delivers real samples. */
                              .delay = NULL,
                              .stats = NULL,
                              .volume = NULL,
                              .parameters = NULL,
                              .mute = NULL};
