package io.github.jqssun.airplay.audio

import android.content.SharedPreferences

/** Percentages stay integral so values can be read back and reproduced exactly. */
data class LightTuning(
    val sensitivity: Int = 150,
    val gain: Int = 100,
    val threshold: Int = 25,
    val release: Int = 240,
    val mid: Int = 60,
    val high: Int = 20,
    val interval: Int = 140,
    val extent: Int = 100,
) {
    fun impact(raw: Float): Float = (raw * gain / 100f).coerceIn(0f, 1f)
    fun expansion(raw: Float): Float {
        val knee = threshold / 100f
        val value = ((impact(raw) - knee) / (1f - knee)).coerceIn(0f, 1f)
        return value * value
    }
    fun summary() = "灵敏度=$sensitivity%, 增益=$gain%, 扩张门槛=$threshold%, " +
        "最大扩张范围=$extent%, 回落=${release}ms, 中频=$mid%, 高频=$high%, 间隔=${interval}ms"
    fun save(prefs: SharedPreferences) {
        prefs.edit().putInt("light_sensitivity", sensitivity).putInt("light_gain", gain)
            .putInt("light_threshold", threshold).putInt("light_release", release)
            .putInt("light_mid", mid).putInt("light_high", high)
            .putInt("light_interval", interval).putInt("light_extent", extent).apply()
    }
    companion object {
        fun load(prefs: SharedPreferences) = LightTuning(
            sensitivity = prefs.getInt("light_sensitivity", 150).coerceIn(50, 400),
            gain = prefs.getInt("light_gain", 100).coerceIn(50, 800),
            threshold = prefs.getInt("light_threshold", 25).coerceIn(0, 90),
            release = prefs.getInt("light_release", 240).coerceIn(80, 600),
            mid = prefs.getInt("light_mid", 60).coerceIn(0, 100),
            high = prefs.getInt("light_high", 20).coerceIn(0, 100),
            interval = prefs.getInt("light_interval", 140).coerceIn(60, 300),
            extent = prefs.getInt("light_extent", 100).coerceIn(0, 200),
        )
    }
}
