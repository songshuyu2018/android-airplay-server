/* Minimal libgcrypt stand-in for the Android build: shairport-sync only uses it
 * to initialise the library (version check + control calls); the hashing it
 * needs is provided by OpenSSL in this configuration. */
#ifndef SPS_GCRYPT_STUB_H
#define SPS_GCRYPT_STUB_H

#define NEED_LIBGCRYPT_VERSION "1.6.0"

#define GCRYCTL_DISABLE_SECMEM 0
#define GCRYCTL_INITIALIZATION_FINISHED 0

static inline const char *gcry_check_version(const char *req) {
  (void) req;
  return "1.10.0-stub";
}

static inline void gcry_control(int cmd, ...) { (void) cmd; }

#endif
