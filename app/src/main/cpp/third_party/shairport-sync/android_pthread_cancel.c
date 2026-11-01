/*
 * A minimal emulation of thread cancellation for bionic.
 *
 * shairport-sync stops its threads with pthread_cancel() + pthread_join(), and each
 * thread is expected to notice at its next pthread_testcancel().  bionic has neither
 * call, so without this the joins block forever: the player thread never leaves its
 * main loop, which means player_stop() hangs, which means a TEARDOWN never completes
 * and a second sender can never take the receiver over.
 *
 * pthread_cancel() therefore only records the target here, and pthread_testcancel()
 * ends the calling thread when it finds itself recorded.  That is close enough to the
 * real thing for the way shairport-sync uses it: cancellation points are all at the top
 * of a loop, and cleanup handlers registered with pthread_cleanup_push still run.
 *
 * Limitation: a thread that never calls pthread_testcancel() cannot be stopped this way
 * (the metadata queue thread is one), and a recorded thread that is never tested leaves
 * an entry behind -- the table is small and overwrites its oldest entry when full.
 */

#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

/* this file provides the real join, so it must not be redirected to its own wrapper */
#undef pthread_join

#include "android_start.h"

#ifdef __ANDROID__
#include <android/log.h>
#define CANCEL_LOG(...) __android_log_print(ANDROID_LOG_WARN, "shairport", __VA_ARGS__)
#else
#define CANCEL_LOG(...)                                                                          \
  do {                                                                                            \
    fprintf(stderr, "sps: " __VA_ARGS__);                                                         \
    fprintf(stderr, "\n");                                                                        \
  } while (0)
#endif

#define SPS_CANCEL_SLOTS 64

static pthread_mutex_t cancel_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_t cancel_pending[SPS_CANCEL_SLOTS];
static int cancel_used[SPS_CANCEL_SLOTS];
static int64_t cancel_at_ms[SPS_CANCEL_SLOTS];
static int cancel_next;

/* A recorded pthread_t is only meaningful while that thread lives; bionic reuses thread
 * ids quickly, and a stale entry making a brand-new thread exit at its first
 * testcancel() is exactly how a second session lost its timing thread.  Entries are
 * dropped when their thread is joined, and expire on their own after this long. */
#define SPS_CANCEL_TTL_MS 10000

static int64_t now_ms(void) {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

int sps_pthread_cancel(pthread_t target) {
  const int64_t now = now_ms();
  pthread_mutex_lock(&cancel_lock);
  int slot = -1;
  for (int i = 0; i < SPS_CANCEL_SLOTS; i++) {
    if (cancel_used[i] && pthread_equal(cancel_pending[i], target)) {
      slot = i; /* already requested */
      break;
    }
  }
  if (slot < 0) {
    for (int i = 0; i < SPS_CANCEL_SLOTS; i++) {
      if (!cancel_used[i]) {
        slot = i;
        break;
      }
    }
  }
  if (slot < 0) {
    slot = cancel_next;
    cancel_next = (cancel_next + 1) % SPS_CANCEL_SLOTS;
    CANCEL_LOG("cancel table is full, reusing slot %d", slot);
  }
  cancel_pending[slot] = target;
  cancel_used[slot] = 1;
  cancel_at_ms[slot] = now;
  pthread_mutex_unlock(&cancel_lock);
  return 0;
}

void sps_pthread_cancel_forget(pthread_t target) {
  pthread_mutex_lock(&cancel_lock);
  for (int i = 0; i < SPS_CANCEL_SLOTS; i++)
    if (cancel_used[i] && pthread_equal(cancel_pending[i], target)) cancel_used[i] = 0;
  pthread_mutex_unlock(&cancel_lock);
}

int sps_pthread_join(pthread_t thread, void **retval) {
  int rc = pthread_join(thread, retval);
  /* the thread is gone, its id is free for reuse: drop any cancel recorded for it */
  sps_pthread_cancel_forget(thread);
  return rc;
}

void sps_pthread_testcancel(void) {
  const pthread_t self = pthread_self();
  const int64_t now = now_ms();
  int pending = 0;

  pthread_mutex_lock(&cancel_lock);
  for (int i = 0; i < SPS_CANCEL_SLOTS; i++) {
    if (cancel_used[i] && (now - cancel_at_ms[i]) > SPS_CANCEL_TTL_MS)
      cancel_used[i] = 0; /* stale: the thread it was meant for never checked */
    if (cancel_used[i] && pthread_equal(cancel_pending[i], self)) {
      cancel_used[i] = 0;
      pending = 1;
      break;
    }
  }
  pthread_mutex_unlock(&cancel_lock);

  if (pending)
    pthread_exit(NULL); /* runs the thread's cleanup handlers */
}
