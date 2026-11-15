package io.github.jqssun.airplay.audio

import android.content.Context
import java.util.Locale

/** Persist endpoint hints only. Active-Remote always comes from the live RTSP session. */
internal class DacpEndpointCache(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("dacp_endpoints", Context.MODE_PRIVATE)
    private fun key(id: String, peer: String) = "${id.lowercase(Locale.ROOT)}@$peer"
    private fun read(value: String?): Pair<Int, Long>? {
        val parts = value?.split(',') ?: return null
        if (parts.size != 2) return null
        val port = parts[0].toIntOrNull() ?: return null
        val time = parts[1].toLongOrNull() ?: return null
        val age = System.currentTimeMillis() - time
        return if (port in 1..65535 && age in 0..MAX_AGE_MS) port to time else null
    }
    fun port(id: String, peer: String): Int? = read(prefs.getString(key(id, peer), null))?.first
    fun remember(id: String, peer: String, port: Int) {
        if (id.isBlank() || peer.isBlank() || port !in 1..65535) return
        val entry = key(id, peer)
        val editor = prefs.edit()
        val others = prefs.all.mapNotNull { (k, v) ->
            if (k == entry) null else read(v as? String)?.let { k to it.second }
        }.sortedByDescending { it.second }.take(MAX_ENTRIES - 1).map { it.first }.toSet()
        prefs.all.keys.filter { it != entry && it !in others }.forEach { editor.remove(it) }
        editor.putString(entry, "$port,${System.currentTimeMillis()}").apply()
    }
    fun forget(id: String, peer: String, port: Int) {
        if (port(id, peer) == port) prefs.edit().remove(key(id, peer)).apply()
    }
    companion object {
        private const val MAX_ENTRIES = 16
        private const val MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L
    }
}
