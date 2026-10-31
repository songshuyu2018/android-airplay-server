/* Minimal libuuid stand-in: shairport-sync only needs the 16-byte uuid_t type
 * and uuid_unparse_lower() to render it. */
#ifndef SPS_UUID_STUB_H
#define SPS_UUID_STUB_H

#include <stdio.h>

typedef unsigned char uuid_t[16];

static inline void uuid_unparse_lower(const uuid_t uu, char *out) {
  snprintf(out, 37,
           "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
           uu[0], uu[1], uu[2], uu[3], uu[4], uu[5], uu[6], uu[7],
           uu[8], uu[9], uu[10], uu[11], uu[12], uu[13], uu[14], uu[15]);
}

#endif
