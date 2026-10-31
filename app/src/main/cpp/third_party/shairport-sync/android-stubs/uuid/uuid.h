/* Minimal libuuid stand-in for Android: shairport-sync uses uuid_t,
 * uuid_parse(), uuid_generate_sha1() and uuid_unparse_lower(). */
#ifndef SPS_UUID_STUB_H
#define SPS_UUID_STUB_H

#include <stdio.h>
#include <string.h>
#include <openssl/sha.h>

typedef unsigned char uuid_t[16];

static inline int uuid_parse(const char *in, uuid_t uu) {
  int i, j = 0;
  if (!in || strlen(in) < 32) return -1;
  for (i = 0; i < 36 && j < 16; i++) {
    char c = in[i];
    int v;
    if (c == '-') continue;
    v = (c >= '0' && c <= '9') ? c - '0'
      : (c >= 'a' && c <= 'f') ? c - 'a' + 10
      : (c >= 'A' && c <= 'F') ? c - 'A' + 10 : -1;
    if (v < 0) return -1;
    if ((j & 1) == 0) uu[j / 2] = (unsigned char) (v << 4);
    else uu[j / 2] |= (unsigned char) v;
    j++;
  }
  return (j == 16) ? 0 : -1;
}

static inline void uuid_generate_sha1(uuid_t out, const uuid_t ns,
                                      const void *name, size_t len) {
  SHA_CTX ctx;
  unsigned char digest[SHA_DIGEST_LENGTH];
  SHA1_Init(&ctx);
  SHA1_Update(&ctx, ns, 16);
  SHA1_Update(&ctx, name, len);
  SHA1_Final(digest, &ctx);
  memcpy(out, digest, 16);
  out[6] = (unsigned char) ((out[6] & 0x0F) | 0x50);   /* version 5 */
  out[8] = (unsigned char) ((out[8] & 0x3F) | 0x80);   /* variant */
}

static inline void uuid_unparse_lower(const uuid_t uu, char *out) {
  snprintf(out, 37,
           "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
           uu[0], uu[1], uu[2], uu[3], uu[4], uu[5], uu[6], uu[7],
           uu[8], uu[9], uu[10], uu[11], uu[12], uu[13], uu[14], uu[15]);
}

#endif
