/*
 * shm_open() for the Android receiver.
 *
 * shairport-sync uses a POSIX shared-memory segment to talk to NQPTP, and it is
 * declared in bionic only from API 26 while this app supports API 24.  There is no
 * NQPTP on Android yet, so the honest behaviour is "no shared memory here": the
 * PTP interface open fails and shairport-sync falls back to classic AirPlay.
 *
 * On API 26+ the real implementation is looked up at first use, so nothing has to
 * change when NQPTP is eventually added.
 */

#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/types.h>

typedef int (*shm_open_fn)(const char *name, int oflag, mode_t mode);

int shm_open(const char *name, int oflag, mode_t mode) {
  static shm_open_fn real = 0;
  static int looked_up = 0;

  if (!looked_up) {
    looked_up = 1;
    void *libc = dlopen("libc.so", RTLD_NOW);
    if (libc)
      real = (shm_open_fn)dlsym(libc, "shm_open");
  }
  if (!real) {
    errno = ENOSYS;
    return -1;
  }
  return real(name, oflag, mode);
}
