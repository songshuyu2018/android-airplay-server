/*
 * Android audio backend for shairport-sync.
 *
 * Hands decoded PCM to the app's existing Oboe engine instead of writing to a
 * pipe or a system sound server: shairport-sync runs in-process, so the
 * callback is a plain C function implemented by the JNI bridge.
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

static unsigned int bytes_per_frame = 0;

static int play(void *buf, int samples, __attribute__((unused)) int sample_type,
                uint32_t timestamp, uint64_t playtime) {
  if (bytes_per_frame == 0) {
    debug(1, "android: bytes per frame not initialised before play()!");
    return -1;
  }
  return airplay_android_audio_write(buf, samples, (int) bytes_per_frame, timestamp, playtime);
}

static int init(__attribute__((unused)) int argc, __attribute__((unused)) char **argv) {
  config.audio_backend_buffer_desired_length = 0.2;
  config.audio_backend_latency_offset = 0;
#ifdef CONFIG_AIRPLAY_2
  parse_audio_options("android", (1 << SPS_FORMAT_S32_LE), (1 << SPS_RATE_48000), (1 << 2));
#else
  parse_audio_options("android", (1 << SPS_FORMAT_S16_LE), (1 << SPS_RATE_44100), (1 << 2));
#endif
  return 0;
}

static int32_t get_configuration(unsigned int channels, unsigned int rate, unsigned int format) {
  return search_for_suitable_configuration(channels, rate, format, NULL);
}

static int configure(int32_t requested_encoded_format, __attribute__((unused)) char **channel_map) {
  unsigned int bytes_per_sample =
      sps_format_sample_size(FORMAT_FROM_ENCODED_FORMAT(requested_encoded_format));
  if (bytes_per_sample == 0) {
    debug(1, "android: unknown output format.");
    bytes_per_sample = 4;
  }
  bytes_per_frame = bytes_per_sample * CHANNELS_FROM_ENCODED_FORMAT(requested_encoded_format);
  return 0;
}

audio_output audio_android = {.name = "android",
                              .help = NULL,
                              .init = &init,
                              .deinit = NULL,
                              .get_configuration = &get_configuration,
                              .configure = &configure,
                              .stop = NULL,
                              .is_running = NULL,
                              .flush = NULL,
                              .delay = NULL,
                              .stats = NULL,
                              .play = &play,
                              .volume = NULL,
                              .parameters = NULL,
                              .mute = NULL};
