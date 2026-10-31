/*
 * In-process lifecycle for shairport-sync (see android_start.h).
 *
 * The receiver is a program, not a library: main() initialises everything and never
 * returns, and every way out of it is exit().  Running it inside an app therefore
 * needs three substitutions, all of them here:
 *
 *   1. main() runs on a thread of its own instead of the process' main thread.
 *   2. exit() (and die()'s last resort) terminate that thread, never the process.
 *   3. shutdown is cooperative: the RTSP listening loop polls a stop flag, and a
 *      throwaway TCP connection wakes it out of select() so the poll happens promptly.
 *
 * Cleanup is deliberately partial.  shairport's own exit_function() cancels its
 * threads with pthread_cancel(), which bionic does not have (see sps_android_compat.h),
 * so calling it would block forever in pthread_join().  What is done instead is the part
 * that is both safe and observable from outside: unregister mDNS and leave the listening
 * loop.  The audio backend and the metadata FIFO belong to the embedder and are closed
 * by it.
 *
 * A second start() in the same process is refused.  main() creates helper threads
 * (metadata pipe, activity monitor, exit manager) that shairport-sync cannot join, and
 * starting again would re-initialise the queues they are blocked on -- on a desktop that
 * segfaults within a second of the second start.  The app restarts its process instead.
 * The run number below still exists because a stopped run's threads can linger: it stops
 * them from touching the next run's flags, and it would make a restart possible later if
 * the helper threads ever become stoppable.
 */

#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <sys/types.h>

#include "android_start.h"
#include "mdns.h"

#ifdef __ANDROID__
#include <android/log.h>
#define SPS_TAG "shairport"
#define SPS_LOG(...) __android_log_print(ANDROID_LOG_INFO, SPS_TAG, __VA_ARGS__)
#define SPS_ERR(...) __android_log_print(ANDROID_LOG_ERROR, SPS_TAG, __VA_ARGS__)
#else
#define SPS_LOG(...)                                                                              \
  do {                                                                                            \
    fprintf(stderr, "sps: " __VA_ARGS__);                                                         \
    fprintf(stderr, "\n");                                                                        \
  } while (0)
#define SPS_ERR(...) SPS_LOG(__VA_ARGS__)
#endif

/* main() lives in shairport.c; it is not static, so calling it from here links. */
extern int main(int argc, char **argv);

/* The Android build always uses the app's own audio backend.  A test build on a
 * desktop can point at a different one (SPS_AUDIO_BACKEND='"stdout"') so that the
 * start/stop/metadata behaviour can be exercised without an Android device. */
#ifndef SPS_AUDIO_BACKEND
#define SPS_AUDIO_BACKEND "android"
#endif

static pthread_mutex_t state_lock = PTHREAD_MUTEX_INITIALIZER;

static volatile unsigned run_number;    // incremented by every start
static volatile unsigned stopped_run;   // highest run that has been asked to stop
static volatile int running;            // a run is active
static volatile int listener_stopped;   // the current run's RTSP loop has left
static volatile int main_exited;        // the current run's main() has returned
static volatile int cleaned_up;         // mdns_unregister() done for the current run
static int starts_so_far;

static int port_number;
static int verbosity_level;
static char service_name[160];
static char metadata_pipe[512];
static char log_file_path[512];

static sps_android_exit_cb exit_callback;

void sps_android_set_exit_callback(sps_android_exit_cb cb) { exit_callback = cb; }

int sps_android_is_running(void) { return running; }

/* True for the lifetime of a run once its stop has been requested. */
int sps_android_stop_requested(void) { return stopped_run != 0 && stopped_run >= run_number; }

void sps_android_note_listener_stopped(void) {
  listener_stopped = 1;
  SPS_LOG("RTSP listening loop has stopped");
}

/* Send the daemon's stdout/stderr to a file so the app can show and share it. */
static void redirect_output(void) {
  if (log_file_path[0] == '\0')
    return;
  int fd = open(log_file_path, O_WRONLY | O_CREAT | O_APPEND, 0600);
  if (fd < 0) {
    SPS_ERR("cannot open log file \"%s\": %s", log_file_path, strerror(errno));
    return;
  }
  dup2(fd, STDOUT_FILENO);
  dup2(fd, STDERR_FILENO);
  close(fd);
  /* stderr is unbuffered already; keep stdout line buffered so the two interleave */
  setvbuf(stdout, NULL, _IOLBF, 0);
}

/* select() in the listening loop has a 60 s timeout; a connection to our own port
 * makes it return at once, and the loop sees the stop flag on the next iteration. */
static void wake_listener(void) {
  struct sockaddr_in addr;
  memset(&addr, 0, sizeof(addr));
  addr.sin_family = AF_INET;
  addr.sin_port = htons((uint16_t)port_number);
  addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

  int fd = socket(AF_INET, SOCK_STREAM, 0);
  if (fd < 0)
    return;
  if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) != 0)
    SPS_LOG("listener wakeup was not accepted (%s) -- it may not be listening yet",
            strerror(errno));
  close(fd);
}

static void note_main_exited(unsigned my_run, int status) {
  if (my_run == run_number) {
    main_exited = 1;
    running = 0;
  }
  if (exit_callback)
    exit_callback(status);
}

static void *_sps_thread(void *arg) {
  unsigned my_run = (unsigned)(uintptr_t)arg;

  char port_text[16];
  snprintf(port_text, sizeof(port_text), "%d", port_number);

  const char *argv[24];
  int argc = 0;
  argv[argc++] = "shairport-sync";
  argv[argc++] = "-a";
  argv[argc++] = service_name;
  argv[argc++] = "--output=" SPS_AUDIO_BACKEND;
  argv[argc++] = "--port";
  argv[argc++] = port_text;
  if (metadata_pipe[0] != '\0') {
    argv[argc++] = "--metadata-pipename";
    argv[argc++] = metadata_pipe;
  }
  for (int i = 0; i < verbosity_level && i < 3; i++)
    argv[argc++] = "-v";
  argv[argc] = NULL;

  SPS_LOG("starting shairport-sync (run %u): name=\"%s\" port=%d metadata=\"%s\"", my_run,
          service_name, port_number, metadata_pipe);

  int status = main(argc, (char **)argv);

  /* reached only for -V/-h and early errors, see sps_android_exit() */
  SPS_ERR("shairport-sync main() returned %d", status);
  note_main_exited(my_run, status);
  return NULL;
}

void sps_android_note_main_exited(int status) { note_main_exited(run_number, status); }

void sps_android_exit(int status) {
  int do_cleanup = 0;

  pthread_mutex_lock(&state_lock);
  if (!cleaned_up) {
    cleaned_up = 1;
    do_cleanup = 1;
  }
  pthread_mutex_unlock(&state_lock);

  if (do_cleanup) {
    SPS_ERR("shairport-sync is terminating (status %d)", status);
    /* Take the service off the network; otherwise senders keep offering it. */
    mdns_unregister();
  }

  /* This run is over: let main()'s park loop notice and leave as well. */
  stopped_run = run_number;
  running = 0;
  if (exit_callback)
    exit_callback(status);

  pthread_exit(NULL);
}

int sps_android_start(const char *name, const char *meta_pipe, const char *log_path, int port,
                      int verbosity) {
  pthread_mutex_lock(&state_lock);
  if (running) {
    pthread_mutex_unlock(&state_lock);
    SPS_ERR("a receiver is already running");
    return -1;
  }
  if (starts_so_far > 0) {
    pthread_mutex_unlock(&state_lock);
    /* main() starts helper threads (metadata pipe, activity monitor, exit manager)
     * that shairport-sync offers no way to join or cancel -- bionic has no thread
     * cancellation at all -- and running the startup sequence again re-initialises
     * the queues those threads are blocked on.  One receiver per process is
     * therefore the rule; the app restarts its process to change settings. */
    SPS_ERR("shairport-sync has already run in this process and cannot be started again "
            "-- restart the application");
    return -1;
  }

  snprintf(service_name, sizeof(service_name), "%s", name ? name : "AirPlay");
  snprintf(metadata_pipe, sizeof(metadata_pipe), "%s", meta_pipe ? meta_pipe : "");
  snprintf(log_file_path, sizeof(log_file_path), "%s", log_path ? log_path : "");
  port_number = (port > 0 && port < 65536) ? port : 7000;
  verbosity_level = verbosity < 0 ? 0 : (verbosity > 3 ? 3 : verbosity);

  run_number++;
  listener_stopped = 0;
  main_exited = 0;
  cleaned_up = 0;
  running = 1;
  starts_so_far++;

  redirect_output();

  pthread_attr_t attr;
  pthread_attr_init(&attr);
  /* the player and rtsp threads recurse a fair way down with plists on the stack */
  pthread_attr_setstacksize(&attr, 1024 * 1024);

  pthread_t thread;
  int err = pthread_create(&thread, &attr, _sps_thread, (void *)(uintptr_t)run_number);
  pthread_attr_destroy(&attr);
  if (err == 0)
    pthread_detach(thread);
  pthread_mutex_unlock(&state_lock);

  if (err != 0) {
    running = 0;
    SPS_ERR("cannot create the receiver thread: %s", strerror(err));
    return -1;
  }
  return 0;
}

int sps_android_stop(void) {
  pthread_mutex_lock(&state_lock);
  if (!running) {
    pthread_mutex_unlock(&state_lock);
    return 0;
  }
  unsigned my_run = run_number;
  stopped_run = my_run;
  pthread_mutex_unlock(&state_lock);

  SPS_LOG("stop requested -- waiting for the listening loop and main()");
  wake_listener();

  /* The listening loop leaves on its own and main() follows within one poll interval. */
  for (int waited = 0; waited < 350 && !(listener_stopped && main_exited); waited++)
    usleep(10000);

  int clean = listener_stopped && main_exited;
  if (!clean)
    SPS_ERR("stop was not clean (listener stopped: %d, main exited: %d)", listener_stopped,
            main_exited);

  pthread_mutex_lock(&state_lock);
  if (!cleaned_up) {
    cleaned_up = 1;
    pthread_mutex_unlock(&state_lock);
    mdns_unregister();
  } else {
    pthread_mutex_unlock(&state_lock);
  }

  running = 0;
  if (clean)
    SPS_LOG("receiver stopped");
  return clean ? 0 : -1;
}
