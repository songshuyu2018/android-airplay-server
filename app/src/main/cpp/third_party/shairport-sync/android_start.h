#ifndef SPS_ANDROID_START_H
#define SPS_ANDROID_START_H

/*
 * In-process start/stop for shairport-sync.
 *
 * shairport-sync is written as a standalone program: its whole initialisation
 * sequence lives in main(), and the way it stops is exit().  Neither works inside
 * an Android app, so this file provides:
 *
 *   - sps_android_start(): runs main() on a private thread
 *   - sps_android_exit():  what exit() becomes -- terminates that thread, not the app
 *   - sps_android_stop():  cooperative shutdown (stop flag + listener wakeup)
 *
 * It is plain C plus pthreads/sockets, so the same code builds on Linux and can be
 * exercised outside Android before going anywhere near a phone.
 */

#ifdef __cplusplus
extern "C" {
#endif

/* Notified when the receiver terminates on its own (fatal error).  Runs on whatever
 * thread noticed; it must not block. */
typedef void (*sps_android_exit_cb)(int status);
void sps_android_set_exit_callback(sps_android_exit_cb cb);

/*
 * Start the receiver on its own thread.
 *   name       AirPlay service name
 *   meta_pipe  metadata FIFO path (must be an existing FIFO or creatable path)
 *   log_path   receives the daemon's stdout/stderr, NULL leaves them alone
 *   port       RTSP port, 0 means 7000
 *   verbosity  0..3, number of -v options
 * Returns 0 when the thread was created.  -1 means a receiver is already running, or
 * that one has already run in this process: shairport-sync's helper threads cannot be
 * stopped, so the app restarts its process to change settings rather than starting a
 * second receiver here.
 */
int sps_android_start(const char *name, const char *meta_pipe, const char *log_path, int port,
                      int verbosity);

/*
 * Stop the receiver: ask the RTSP listening loop to leave, wait for it, unregister
 * mDNS.  Returns 0 when it stopped (or was not running).  The audio backend and the
 * metadata FIFO are the embedder's to close.
 */
int sps_android_stop(void);

int sps_android_is_running(void);

/* --- used by the patched shairport-sync sources --- */

/* Replacement for exit(): never terminates the process. */
void sps_android_exit(int status) __attribute__((noreturn));

/* Polled by the RTSP listening loop each time round. */
int sps_android_stop_requested(void);

/* Called by the RTSP listening loop when it has left. */
void sps_android_note_listener_stopped(void);

/* Called by main() if it ever returns (it does for -V/-h and on early errors). */
void sps_android_note_main_exited(int status);

#ifdef __cplusplus
}
#endif

#endif /* SPS_ANDROID_START_H */
