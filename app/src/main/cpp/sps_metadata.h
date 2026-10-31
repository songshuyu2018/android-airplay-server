#ifndef SPS_METADATA_H
#define SPS_METADATA_H

#include "android_raop_callbacks.h"

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Reads shairport-sync's metadata pipe and forwards it to the app.
 *
 * shairport-sync writes one XML <item> per metadata message to a FIFO:
 *
 *   <item><type>73736e63</type><code>6d696e6d</code><length>9</length>
 *   <data encoding="base64">
 *   VGhlIFRpdGxl</data></item>
 *
 * The 4-character codes are the DMAP ones the app already understands (minm,
 * asar, asal, astm, PICT, prgr, ...).  The reader coalesces a burst of text items
 * into one DMAP record and hands it to the existing onMetadata() callback, so the
 * Kotlin side needs no new parsing.
 *
 * Returns 0 when the reader thread is running.
 */
int sps_metadata_start(const char *fifo_path, android_callback_ctx_t *ctx);

void sps_metadata_stop(void);

#ifdef __cplusplus
}
#endif

#endif /* SPS_METADATA_H */
