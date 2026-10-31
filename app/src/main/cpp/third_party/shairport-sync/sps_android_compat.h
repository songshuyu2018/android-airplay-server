/* Bionic has no POSIX thread cancellation and no scheduler inheritance for
 * thread attributes. shairport-sync only uses them as belt-and-braces around
 * short critical sections, so neutralising them is safe on Android. */
#ifndef SPS_ANDROID_COMPAT_H
#define SPS_ANDROID_COMPAT_H

#ifdef __ANDROID__

#include <pthread.h>

#ifndef PTHREAD_CANCEL_DISABLE
#define PTHREAD_CANCEL_DISABLE 0
#endif
#ifndef PTHREAD_CANCEL_ENABLE
#define PTHREAD_CANCEL_ENABLE 0
#endif
#ifndef PTHREAD_EXPLICIT_SCHED
#define PTHREAD_EXPLICIT_SCHED 0
#endif
#ifndef PTHREAD_INHERIT_SCHED
#define PTHREAD_INHERIT_SCHED 0
#endif

static inline int sps_android_pthread_attr_setinheritsched(pthread_attr_t *a, int s) {
  (void) a; (void) s; return 0;
}

#undef pthread_cancel
#define pthread_cancel(t) 0
#undef pthread_setcancelstate
#define pthread_setcancelstate(s, o) 0
#undef pthread_setcanceltype
#define pthread_setcanceltype(t, o) 0
#undef pthread_attr_setinheritsched
#define pthread_attr_setinheritsched(a, s) sps_android_pthread_attr_setinheritsched((a), (s))


#undef pthread_testcancel
#define pthread_testcancel() ((void) 0)

/* shm_open() appears in bionic's headers from API 26; declare it so the NQPTP
 * shared-memory interface compiles on our API 24 baseline. At runtime it is
 * present on every device this app supports. */
#ifndef SHM_OPEN_DECLARED_BY_BIONIC
#include <sys/types.h>
extern int shm_open(const char *name, int oflag, mode_t mode);
#endif

#endif /* __ANDROID__ */

#endif /* SPS_ANDROID_COMPAT_H */
