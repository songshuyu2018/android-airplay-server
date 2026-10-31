/*
 * shairport-sync metadata pipe reader (see sps_metadata.h).
 *
 * Two halves, deliberately apart:
 *   sps_metadata_parse.cpp  the framing/base64/DMAP logic, no JNI, testable anywhere
 *   this file               a thread that reads the FIFO and forwards to Java
 *
 * The text items of one metadata burst are re-assembled into a DMAP record -- the
 * format UxPlay already produced -- and handed to onMetadata(), so the Kotlin side
 * needs no new parsing.  Cover art, progress and the DACP identifiers have their own
 * callbacks because they are not part of the record.
 */

#include "sps_metadata.h"
#include "sps_metadata_parse.h"

#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define TAG "AirPlaySpsMeta"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

constexpr size_t kReadBufferSize = 64 * 1024;

struct Reader {
    pthread_t thread;
    volatile int stop;
    int fd;
    char fifo[512];
    android_callback_ctx_t *ctx;
    SpsMetadataParser *parser;
};

Reader g_reader;

JNIEnv *attachEnv(JavaVM *vm) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv((void **)&env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
    }
    if (env && env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    return env;
}

/* --- parser callbacks: turn parsed items into Java calls --- */

void onMetadata(void *opaque, const unsigned char *dmap, size_t len) {
    auto *ctx = static_cast<android_callback_ctx_t *>(opaque);
    JNIEnv *env = attachEnv(ctx->jvm);
    if (!env || !ctx->on_metadata) return;
    jbyteArray array = env->NewByteArray((jsize)len);
    if (!array) return;
    env->SetByteArrayRegion(array, 0, (jsize)len, (const jbyte *)dmap);
    env->CallVoidMethod(ctx->callback_obj, ctx->on_metadata, array);
    env->DeleteLocalRef(array);
}

void onCoverArt(void *opaque, const unsigned char *data, size_t len) {
    auto *ctx = static_cast<android_callback_ctx_t *>(opaque);
    JNIEnv *env = attachEnv(ctx->jvm);
    if (!env || !ctx->on_coverart) return;
    jbyteArray array = env->NewByteArray((jsize)len);
    if (!array) return;
    env->SetByteArrayRegion(array, 0, (jsize)len, (const jbyte *)data);
    env->CallVoidMethod(ctx->callback_obj, ctx->on_coverart, array);
    env->DeleteLocalRef(array);
}

void onProgress(void *opaque, uint32_t start, uint32_t current, uint32_t end) {
    auto *ctx = static_cast<android_callback_ctx_t *>(opaque);
    JNIEnv *env = attachEnv(ctx->jvm);
    if (!env || !ctx->on_progress) return;
    env->CallVoidMethod(ctx->callback_obj, ctx->on_progress, (jlong)start, (jlong)current,
                        (jlong)end);
}

void onDacp(void *opaque, const char *dacpId, const char *activeRemote) {
    auto *ctx = static_cast<android_callback_ctx_t *>(opaque);
    JNIEnv *env = attachEnv(ctx->jvm);
    if (!env || !ctx->on_dacp_id) return;
    jstring id = env->NewStringUTF(dacpId);
    jstring remote = env->NewStringUTF(activeRemote);
    if (id && remote) env->CallVoidMethod(ctx->callback_obj, ctx->on_dacp_id, id, remote);
    if (id) env->DeleteLocalRef(id);
    if (remote) env->DeleteLocalRef(remote);
    LOGI("DACP ready, id %s", dacpId);
}

void onPlayback(void *opaque, int state) {
    auto *ctx = static_cast<android_callback_ctx_t *>(opaque);
    JNIEnv *env = attachEnv(ctx->jvm);
    if (!env) return;
    switch (state) {
        case 1:  // resumed
            if (ctx->on_audio_resume)
                env->CallVoidMethod(ctx->callback_obj, ctx->on_audio_resume);
            break;
        case 2:  // a sender became active
            if (ctx->on_conn_init) env->CallVoidMethod(ctx->callback_obj, ctx->on_conn_init);
            break;
        case 3:  // the session ended
            if (ctx->on_conn_destroy) env->CallVoidMethod(ctx->callback_obj, ctx->on_conn_destroy);
            break;
        default:  // paused
            if (ctx->on_audio_flush)
                env->CallVoidMethod(ctx->callback_obj, ctx->on_audio_flush);
            break;
    }
}

void *readerThread(void *arg) {
    Reader *r = static_cast<Reader *>(arg);
    LOGI("metadata reader started on %s", r->fifo);

    sps_metadata_sink_t sink;
    memset(&sink, 0, sizeof(sink));
    sink.ctx = r->ctx;
    sink.metadata = &onMetadata;
    sink.cover_art = &onCoverArt;
    sink.progress = &onProgress;
    sink.dacp = &onDacp;
    sink.playback = &onPlayback;
    r->parser = sps_metadata_parser_new(&sink);

    char buffer[kReadBufferSize];
    while (!r->stop) {
        if (r->fd < 0) {
            // O_RDONLY on a FIFO blocks until a writer appears, so open non-blocking and
            // let poll() report the writer instead
            r->fd = open(r->fifo, O_RDONLY | O_NONBLOCK);
            if (r->fd < 0) {
                if (errno != ENXIO) LOGE("cannot open %s: %s", r->fifo, strerror(errno));
                usleep(200000);
                continue;
            }
            // the first call attaches this thread to the JVM
            attachEnv(r->ctx->jvm);
        }

        struct pollfd pfd;
        pfd.fd = r->fd;
        pfd.events = POLLIN;
        pfd.revents = 0;
        const int ready = poll(&pfd, 1, 100);
        if (ready > 0 && (pfd.revents & POLLIN)) {
            const ssize_t n = read(r->fd, buffer, sizeof(buffer));
            if (n > 0) sps_metadata_parser_feed(r->parser, buffer, (size_t)n);
            else if (n == 0) usleep(100000);  // no writer at the moment
        }
        // a burst can end without an explicit end marker
        sps_metadata_parser_tick(r->parser);
    }

    if (r->fd >= 0) close(r->fd);
    r->fd = -1;
    sps_metadata_parser_free(r->parser);
    r->parser = nullptr;
    LOGI("metadata reader stopped");
    return nullptr;
}

}  // namespace

extern "C" int sps_metadata_start(const char *fifo_path, android_callback_ctx_t *ctx) {
    if (!fifo_path || !ctx) return -1;
    if (g_reader.thread) return 0;

    memset(&g_reader, 0, sizeof(g_reader));
    g_reader.fd = -1;
    snprintf(g_reader.fifo, sizeof(g_reader.fifo), "%s", fifo_path);
    g_reader.ctx = ctx;

    // the FIFO belongs to us: shairport-sync tolerates it already existing
    if (mkfifo(g_reader.fifo, 0600) != 0 && errno != EEXIST) {
        LOGE("cannot create %s: %s", g_reader.fifo, strerror(errno));
        return -1;
    }

    if (pthread_create(&g_reader.thread, nullptr, readerThread, &g_reader) != 0) {
        LOGE("cannot start the metadata reader thread");
        g_reader.thread = 0;
        return -1;
    }
    return 0;
}

extern "C" void sps_metadata_stop(void) {
    if (!g_reader.thread) return;
    g_reader.stop = 1;
    pthread_join(g_reader.thread, nullptr);
    g_reader.thread = 0;
    unlink(g_reader.fifo);
}
