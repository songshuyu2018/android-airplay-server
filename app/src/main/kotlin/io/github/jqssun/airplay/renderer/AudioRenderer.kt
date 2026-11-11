package io.github.jqssun.airplay.renderer

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.jqssun.airplay.bridge.NativeBridge
import io.github.jqssun.airplay.viewmodel.AudioDebug

// wrapper around native audio engine
class AudioRenderer {

    @Volatile var config = AudioConfig(); private set

    @Volatile var codecLabel = ""; private set
    @Volatile var clientGain = 1f; private set

    private var serverHandle = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var temporarilySilent = false
    private val endSilence = Runnable {
        synchronized(this) {
            temporarilySilent = false
            Log.i("AudioRenderer", "Local mute ended at=${SystemClock.elapsedRealtime()}")
            pushGain()
        }
    }

    @Synchronized
    fun silenceForFiveSeconds() {
        if (temporarilySilent) return // A fixed window; repeated pauses still go to the sender.
        Log.i("AudioRenderer", "Local mute start until=${SystemClock.elapsedRealtime() + 5000L}")
        temporarilySilent = true
        pushGain()
        handler.removeCallbacks(endSilence)
        handler.postDelayed(endSilence, 5000L)
    }

    private fun pushGain() {
        if (serverHandle != 0L) NativeBridge.nativeServerAudioSetClientGain(
            serverHandle, if (temporarilySilent) 0f else clientGain)
    }

    @Synchronized
    fun attachEngine(server: Long) {
        serverHandle = server
        pushConfig()
        pushGain()
    }

    @Synchronized
    fun detachEngine() {
        handler.removeCallbacks(endSilence)
        temporarilySilent = false
        serverHandle = 0L
        codecLabel = ""
    }

    // open playout (first onAudioFormat, or resume after pause); idempotent natively
    @Synchronized
    fun start() {
        if (serverHandle == 0L) return
        NativeBridge.nativeServerAudioStart(serverHandle)
    }

    // releases oboe stream + audio device while idle; engine stays alive, freed on server destroy
    @Synchronized
    fun stop() {
        if (serverHandle == 0L) return
        NativeBridge.nativeServerAudioStop(serverHandle)
        codecLabel = ""
    }

    @Synchronized
    fun setClientGain(gain: Float) {
        clientGain = gain
        pushGain()
    }

    @Synchronized
    fun updateConfig(newConfig: AudioConfig) {
        config = newConfig
        pushConfig()
    }

    private fun pushConfig() {
        if (serverHandle == 0L) return
        NativeBridge.nativeServerAudioConfigure(
            serverHandle, config.cushionMs, config.percentilePct, config.oboeBufferFrames,
            config.forceSwAlac, config.forceSwAac, config.realtimePriority, config.lowLatency,
            config.benchmarkLog)
    }

    private val debugBuf = java.nio.ByteBuffer.allocateDirect(DEBUG_BUF_BYTES)
        .order(java.nio.ByteOrder.LITTLE_ENDIAN)

    // field order/width must mirror native packed AudioDebugData exactly
    @Synchronized
    fun audioDebug(): AudioDebug? {
        if (serverHandle == 0L) return null
        if (!NativeBridge.nativeServerAudioDebug(serverHandle, debugBuf)) return null
        val buf = debugBuf
        buf.rewind()
        val backlogMs = buf.short.toInt() and 0xFFFF
        val tunedCushionMs = buf.short.toInt() and 0xFFFF
        val trims = buf.int
        val drops = buf.int
        val silences = buf.int
        val underruns = buf.int
        val meanUs = buf.int
        val maxUs = buf.int
        val held = buf.short.toInt() and 0xFFFF
        val xrun = buf.int
        val decodeErrors = buf.int
        return AudioDebug(backlogMs, tunedCushionMs, trims, drops, silences, underruns, xrun,
                          meanUs, maxUs, held, decodeErrors)
    }

    @Synchronized
    fun setFormat(ct: Int, spf: Int) {
        codecLabel = when (ct) {
            CT_ALAC -> "ALAC"; CT_AAC_LC -> "AAC-LC"; CT_AAC_ELD -> "AAC-ELD"; else -> "?"
        }
        if (serverHandle != 0L) NativeBridge.nativeServerAudioFormat(serverHandle, ct, spf)
    }

    companion object {
        // must be >= native sizeof(AudioDebugData) (38 B)
        private const val DEBUG_BUF_BYTES = 64
        const val CT_ALAC = 2
        const val CT_AAC_LC = 4
        const val CT_AAC_ELD = 8
    }
}
