package io.github.jqssun.airplay.audio

import android.content.SharedPreferences
import kotlin.math.pow

/** Percentages stay integral so values can be read back and reproduced exactly. */
data class LightTuning(
    val sensitivity: Int = 200,
    val gain: Int = 150,
    val threshold: Int = 0,
    val release: Int = 200,
    val mid: Int = 50,
    val high: Int = 0,
    val interval: Int = 60,
    val motionBoost: Int = 300,
    val curve: Int = 200,
) {
    fun impact(raw: Float): Float = (raw * gain / 100f).coerceIn(0f, 1f)
    fun response(raw: Float): Float {
        val knee = threshold / 100f
        val value = impact(raw)
        // Continuous soft knee: weak attacks have a small motion response instead
        // of a dead zone. The upper segment reserves most travel for heavy attacks.
        val response = when {
            knee == 0f -> value
            value <= knee -> .15f * value / knee
            else -> .15f + .85f * (value - knee) / (1f - knee)
        }
        return response.coerceIn(0f, 1f).pow(curve / 100f)
    }
    fun summary() = "灵敏度=$sensitivity%, 增益=$gain%, 重拍分界=$threshold%, " +
        "鼓点加速=$motionBoost%, 回落=${release}ms, 中频=$mid%, 高频=$high%, 间隔=${interval}ms, 响应曲线=$curve%"
    fun save(prefs: SharedPreferences) {
        prefs.edit().putInt("light_sensitivity", sensitivity).putInt("light_gain", gain)
            .putInt("light_threshold", threshold).putInt("light_release", release)
            .putInt("light_mid", mid).putInt("light_high", high)
            .putInt("light_interval", interval).putInt("light_motion_boost", motionBoost)
            .remove("light_extent")
            .putInt("light_curve", curve).putInt("light_profile_version", 2).apply()
    }
    companion object {
        fun load(prefs: SharedPreferences): LightTuning {
            // Adopt the user-approved audio preset once on upgrade, then
            // retain any subsequent adjustments made through the optional panel.
            val defaults = LightTuning()
            if (prefs.getInt("light_profile_version", 0) != 2)
                return defaults.also { it.save(prefs) }
            return LightTuning(
                sensitivity = prefs.getInt("light_sensitivity", defaults.sensitivity).coerceIn(50, 400),
                gain = prefs.getInt("light_gain", defaults.gain).coerceIn(50, 800),
                threshold = prefs.getInt("light_threshold", defaults.threshold).coerceIn(0, 90),
                release = prefs.getInt("light_release", defaults.release).coerceIn(80, 600),
                mid = prefs.getInt("light_mid", defaults.mid).coerceIn(0, 100),
                high = prefs.getInt("light_high", defaults.high).coerceIn(0, 100),
                interval = prefs.getInt("light_interval", defaults.interval).coerceIn(60, 300),
                motionBoost = prefs.getInt("light_motion_boost", defaults.motionBoost).coerceIn(0, 600),
                curve = prefs.getInt("light_curve", defaults.curve).coerceIn(50, 250),
            )
        }
    }
}
