#ifndef SPS_METADATA_PARSE_H
#define SPS_METADATA_PARSE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Parser for shairport-sync's metadata pipe.
 *
 * Deliberately free of JNI and of Android so the same code can be exercised on a
 * desktop against a captured pipe, which is where the framing and base64 details
 * get proven.  The reader in sps_metadata.cpp feeds it bytes and turns the
 * callbacks into Java calls.
 *
 * shairport-sync writes one item per message:
 *
 *   <item><type>73736e63</type><code>6d696e6d</code><length>9</length>
 *   <data encoding="base64">VGhlIFRpdGxl</data></item>
 *
 *   type == 'core'  the code is a DMAP tag and the payload its value
 *   type == 'ssnc'  shairport-sync's own notifications (PICT, prgr, paus, ...)
 */

typedef struct {
    void *ctx;
    /* a DMAP record built from the text items of one metadata burst */
    void (*metadata)(void *ctx, const unsigned char *dmap, size_t len);
    /* JPEG or PNG bytes */
    void (*cover_art)(void *ctx, const unsigned char *data, size_t len);
    /* RTP timestamps of start / current / end */
    void (*progress)(void *ctx, uint32_t start, uint32_t current, uint32_t end);
    /* the sender's remote-control identifiers, both known */
    void (*dacp)(void *ctx, const char *dacp_id, const char *active_remote);
    /* 0 = paused, 1 = resumed */
    void (*playback)(void *ctx, int playing);
} sps_metadata_sink_t;

typedef struct SpsMetadataParser SpsMetadataParser;

SpsMetadataParser *sps_metadata_parser_new(const sps_metadata_sink_t *sink);
void sps_metadata_parser_feed(SpsMetadataParser *parser, const char *data, size_t len);
/* flush a burst that ended without an explicit end marker */
void sps_metadata_parser_tick(SpsMetadataParser *parser);
void sps_metadata_parser_free(SpsMetadataParser *parser);

#ifdef __cplusplus
}
#endif

#endif /* SPS_METADATA_PARSE_H */
