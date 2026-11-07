package io.github.jqssun.airplay

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Build
import android.provider.Settings
import java.util.Locale

/** Huawei's background popup switch is separate from Android's overlay permission. */
object BackgroundLaunchAccess {
    private const val PROMPT_SHOWN = "huawei_background_popup_prompt_v1"
    val isHuawei: Boolean get() = listOf(Build.MANUFACTURER, Build.BRAND).any {
        it.lowercase(Locale.ROOT) in setOf("huawei", "honor")
    }

    fun showFirstLaunchPrompt(activity: Activity) {
        if (!isHuawei || activity.isFinishing || activity.isDestroyed) return
        val prefs = activity.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(PROMPT_SHOWN, false)) return
        // There is no public runtime grant/result API for this vendor-specific switch.
        // Record only that we offered settings, never that permission was granted.
        prefs.edit().putBoolean(PROMPT_SHOWN, true).apply()
        AlertDialog.Builder(activity)
            .setTitle(R.string.background_launch_title)
            .setMessage(R.string.background_launch_message)
            .setPositiveButton(R.string.background_launch_settings) { _, _ -> openSettings(activity) }
            .setNegativeButton(R.string.background_launch_continue, null)
            .show()
    }

    fun openSettings(context: Context) {
        context.startActivity(context.appSettingsIntent().addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // Huawei provides no public read API: the switch below represents the user's
    // auto-open preference, not a claim that the vendor permission was granted.
    fun canEnableAutoLaunch(context: Context): Boolean =
        isHuawei || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(context)
}
