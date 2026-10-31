/*
 * Parser for shairport-sync's metadata pipe (see sps_metadata_parse.h).
 *
 * No JNI, no Android, no allocation beyond the parser object: this is the part that
 * has to be right, and keeping it standalone is what lets it be tested against a
 * captured pipe on a desktop.
 */

#include "sps_metadata_parse.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

namespace {

constexpr size_t kPipeBufferSize = 1024 * 1024;
constexpr size_t kItemBufferSize = 1024 * 1024;
constexpr size_t kDataBufferSize = 1024 * 1024;
constexpr size_t kDmapBufferSize = 8192;
/* a burst that ends without an explicit end marker is flushed after this long */
constexpr int64_t kBurstFlushMs = 200;

constexpr uint32_t fourcc(char a, char b, char c, char d) {
    return ((uint32_t)(uint8_t)a << 24) | ((uint32_t)(uint8_t)b << 16) |
           ((uint32_t)(uint8_t)c << 8) | (uint32_t)(uint8_t)d;
}

// tags whose payload is a number and therefore must stay binary in the DMAP record
bool isIntegerDmapTag(uint32_t code) {
    static const uint32_t tags[] = {
        fourcc('a', 's', 't', 'm'),  // duration, ms
        fourcc('a', 's', 't', 'n'),  // track number
        fourcc('a', 's', 'd', 'k'),  // data kind
        fourcc('a', 's', 't', 's'),
        fourcc('m', 'i', 'i', 'd'),  // item id
        fourcc('m', 'c', 't', 'i'),
        fourcc('m', 'p', 'e', 'r'),  // persistent id
        fourcc('a', 's', 'a', 'i'),
        fourcc('a', 's', 'r', 'i'),
        fourcc('a', 's', 'c', 'i'),
        fourcc('a', 's', 'g', 'i'),
    };
    for (uint32_t t : tags)
        if (t == code) return true;
    return false;
}

size_t base64Decode(const char *in, size_t inLen, unsigned char *out, size_t outCap) {
    auto value = [](char c) -> int {
        if (c >= 'A' && c <= 'Z') return c - 'A';
        if (c >= 'a' && c <= 'z') return c - 'a' + 26;
        if (c >= '0' && c <= '9') return c - '0' + 52;
        if (c == '+') return 62;
        if (c == '/') return 63;
        return -1;
    };
    size_t written = 0;
    uint32_t acc = 0;
    int bits = 0;
    for (size_t i = 0; i < inLen; i++) {
        const int v = value(in[i]);
        if (v < 0) continue;  // '=' padding, newlines and stray whitespace
        acc = (acc << 6) | (uint32_t)v;
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            if (written >= outCap) return written;
            out[written++] = (unsigned char)((acc >> bits) & 0xFF);
        }
    }
    return written;
}

int64_t nowMs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

}  // namespace

struct SpsMetadataParser {
    sps_metadata_sink_t sink;

    char pipe[kPipeBufferSize];
    size_t pipeLen;

    char item[kItemBufferSize];
    unsigned char data[kDataBufferSize];

    unsigned char dmap[kDmapBufferSize];
    size_t dmapLen;
    int64_t dmapFirstAtMs;

    char dacpId[128];
    char activeRemote[128];

    void handleItem();
    void appendDmap(uint32_t code, const unsigned char *payload, size_t len);
    void flushDmap();
    void emitDacpIfComplete();
};

void SpsMetadataParser::appendDmap(uint32_t code, const unsigned char *payload, size_t len) {
    if (dmapLen + 8 + len > sizeof(dmap)) return;
    unsigned char *p = dmap + dmapLen;
    p[0] = (unsigned char)(code >> 24);
    p[1] = (unsigned char)(code >> 16);
    p[2] = (unsigned char)(code >> 8);
    p[3] = (unsigned char)code;
    p[4] = (unsigned char)(len >> 24);
    p[5] = (unsigned char)(len >> 16);
    p[6] = (unsigned char)(len >> 8);
    p[7] = (unsigned char)len;
    if (len) memcpy(p + 8, payload, len);
    dmapLen += 8 + len;
    if (dmapFirstAtMs == 0) dmapFirstAtMs = nowMs();
}

void SpsMetadataParser::flushDmap() {
    if (dmapLen == 0) return;
    if (sink.metadata) sink.metadata(sink.ctx, dmap, dmapLen);
    dmapLen = 0;
    dmapFirstAtMs = 0;
}

void SpsMetadataParser::emitDacpIfComplete() {
    if (dacpId[0] == 0 || activeRemote[0] == 0) return;
    if (sink.dacp) sink.dacp(sink.ctx, dacpId, activeRemote);
}

void SpsMetadataParser::handleItem() {
    // <item><type>hex</type><code>hex</code><length>N</length>[<data ...>base64</data>]</item>
    const char *typeTag = strstr(item, "<type>");
    const char *codeTag = strstr(item, "<code>");
    if (!typeTag || !codeTag) return;
    const uint32_t type = (uint32_t)strtoul(typeTag + 6, nullptr, 16);
    const uint32_t code = (uint32_t)strtoul(codeTag + 6, nullptr, 16);

    size_t dataLen = 0;
    const char *dataTag = strstr(item, "<data encoding=\"base64\">");
    if (dataTag) {
        const char *from = strchr(dataTag, '>');
        const char *to = strstr(item, "</data>");
        if (from && to && to > from)
            dataLen = base64Decode(from + 1, (size_t)(to - from - 1), data, sizeof(data));
    }

    // shairport-sync's own codes ('ssnc') are notifications, not DMAP tags
    if (type == fourcc('s', 's', 'n', 'c')) {
        switch (code) {
            case fourcc('m', 'd', 'e', 'n'):  // end of a metadata burst
                flushDmap();
                break;
            case fourcc('m', 'd', 's', 't'):  // start of one; nothing to do
                break;
            case fourcc('P', 'I', 'C', 'T'):
                if (sink.cover_art) sink.cover_art(sink.ctx, data, dataLen);
                break;
            case fourcc('p', 'r', 'g', 'r'): {
                unsigned start = 0, current = 0, end = 0;
                char text[128];
                const size_t n = dataLen < sizeof(text) - 1 ? dataLen : sizeof(text) - 1;
                memcpy(text, data, n);
                text[n] = 0;
                if (sscanf(text, "%u/%u/%u", &start, &current, &end) == 3) {
                    if (sink.progress) sink.progress(sink.ctx, start, current, end);
                }
                break;
            }
            case fourcc('p', 'a', 'u', 's'):
                if (sink.playback) sink.playback(sink.ctx, 0);
                break;
            case fourcc('p', 'r', 'e', 's'):
            case fourcc('p', 'b', 'e', 'g'):
                if (sink.playback) sink.playback(sink.ctx, 1);
                break;
            /* active mode: the app has no other way to tell that a sender is
             * connected, because shairport-sync does not report RTSP connections */
            case fourcc('a', 'b', 'e', 'g'):
                if (sink.playback) sink.playback(sink.ctx, 2);
                break;
            case fourcc('a', 'e', 'n', 'd'):
                if (sink.playback) sink.playback(sink.ctx, 3);
                break;
            case fourcc('d', 'a', 'i', 'd'):
            case fourcc('a', 'c', 'r', 'e'): {
                char *slot = code == fourcc('d', 'a', 'i', 'd') ? dacpId : activeRemote;
                size_t n = dataLen < sizeof(dacpId) - 1 ? dataLen : sizeof(dacpId) - 1;
                while (n > 0 && data[n - 1] == 0) n--;
                memcpy(slot, data, n);
                slot[n] = 0;
                emitDacpIfComplete();
                break;
            }
            default:
                break;
        }
        return;
    }

    if (type == fourcc('c', 'o', 'r', 'e')) {
        if (!isIntegerDmapTag(code)) {
            // shairport-sync writes NUL-terminated values into a fixed-size buffer
            while (dataLen > 0 && data[dataLen - 1] == 0) dataLen--;
        }
        if (dataLen > 0) appendDmap(code, data, dataLen);
    }
}

extern "C" {

SpsMetadataParser *sps_metadata_parser_new(const sps_metadata_sink_t *sink) {
    auto *parser = new SpsMetadataParser();
    memset(parser->pipe, 0, sizeof(parser->pipe));
    memset(parser->item, 0, sizeof(parser->item));
    memset(parser->data, 0, sizeof(parser->data));
    memset(parser->dacpId, 0, sizeof(parser->dacpId));
    memset(parser->activeRemote, 0, sizeof(parser->activeRemote));
    parser->pipeLen = 0;
    parser->dmapLen = 0;
    parser->dmapFirstAtMs = 0;
    if (sink) parser->sink = *sink;
    else memset(&parser->sink, 0, sizeof(parser->sink));
    return parser;
}

void sps_metadata_parser_feed(SpsMetadataParser *parser, const char *bytes, size_t len) {
    if (!parser || !bytes || len == 0) return;

    size_t offset = 0;
    while (offset < len) {
        if (parser->pipeLen == sizeof(parser->pipe)) {
            // a frame bigger than the buffer: drop what we have and start again
            parser->pipeLen = 0;
        }
        const size_t room = sizeof(parser->pipe) - parser->pipeLen;
        const size_t chunk = (len - offset) < room ? (len - offset) : room;
        memcpy(parser->pipe + parser->pipeLen, bytes + offset, chunk);
        parser->pipeLen += chunk;
        offset += chunk;

        // consume every complete <item>...</item>
        size_t consumed = 0;
        for (;;) {
            const char *start = parser->pipe + consumed;
            if (parser->pipeLen - consumed < 6) break;
            const char *itemStart = strstr(start, "<item>");
            if (!itemStart) {
                consumed = parser->pipeLen;
                break;
            }
            const char *itemEnd = strstr(itemStart, "</item>");
            if (!itemEnd) {
                // keep the partial item: the rest is still in the pipe
                consumed = (size_t)(itemStart - parser->pipe);
                break;
            }
            const size_t itemLen = (size_t)(itemEnd + 7 - itemStart);
            if (itemLen < sizeof(parser->item)) {
                memcpy(parser->item, itemStart, itemLen);
                parser->item[itemLen] = 0;
                parser->handleItem();
            }
            consumed = (size_t)(itemEnd + 7 - parser->pipe);
        }
        if (consumed > 0) {
            memmove(parser->pipe, parser->pipe + consumed, parser->pipeLen - consumed);
            parser->pipeLen -= consumed;
        }
    }
}

void sps_metadata_parser_tick(SpsMetadataParser *parser) {
    if (!parser) return;
    if (parser->dmapLen > 0 && nowMs() - parser->dmapFirstAtMs > kBurstFlushMs) parser->flushDmap();
}

void sps_metadata_parser_free(SpsMetadataParser *parser) {
    if (!parser) return;
    parser->flushDmap();
    delete parser;
}

}  // extern "C"
