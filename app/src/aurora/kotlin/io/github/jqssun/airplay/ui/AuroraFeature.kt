package io.github.jqssun.airplay.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.jqssun.airplay.BuildConfig
import io.github.jqssun.airplay.Prefs
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.viewmodel.MainViewModel

@Composable
internal fun auroraThemeEnabled(viewModel: MainViewModel): Boolean {
    val enabled by viewModel.pref(Prefs.PLAYER_AURORA).collectAsState()
    return BuildConfig.AURORA_ENABLED && enabled
}

@Composable
internal fun AuroraThemeBackdrop(viewModel: MainViewModel, playing: Boolean,
    energy: () -> Float, beat: () -> Float, spectrum: () -> FloatArray,
    modifier: Modifier = Modifier, onFailure: () -> Unit) {
    val preRenderScale by viewModel.pref(Prefs.PLAYER_AURORA_PRERENDER_SCALE).collectAsState()
    val preRendered by viewModel.pref(Prefs.PLAYER_AURORA_PRERENDER).collectAsState()
    val auroraBeats by viewModel.pref(Prefs.PLAYER_AURORA_BEATS).collectAsState()
    val materialCache by viewModel.pref(Prefs.PLAYER_AURORA_MATERIAL_CACHE).collectAsState()
    val auroraSamples by viewModel.pref(Prefs.PLAYER_AURORA_SAMPLES).collectAsState()
    val auroraFps by viewModel.pref(Prefs.PLAYER_AURORA_FPS).collectAsState()
    val auroraQuality by viewModel.pref(Prefs.PLAYER_AURORA_QUALITY).collectAsState()
    AuroraBackdrop(playing, energy, beat, spectrum, quality = auroraQuality,
        frameRate = auroraFps, sampleCount = auroraSamples, materialCache = materialCache,
        preRendered = preRendered, preRenderScale = preRenderScale, beatThreshold = auroraBeats,
        modifier = modifier, onFailure = onFailure)
}

@Composable
internal fun AuroraSettings(viewModel: MainViewModel) {
    val themePreview by viewModel.pref(Prefs.PLAYER_THEME_PREVIEW).collectAsState()
    val preRenderScale by viewModel.pref(Prefs.PLAYER_AURORA_PRERENDER_SCALE).collectAsState()
    val preRendered by viewModel.pref(Prefs.PLAYER_AURORA_PRERENDER).collectAsState()
    val auroraBeats by viewModel.pref(Prefs.PLAYER_AURORA_BEATS).collectAsState()
    val materialCache by viewModel.pref(Prefs.PLAYER_AURORA_MATERIAL_CACHE).collectAsState()
    val auroraSamples by viewModel.pref(Prefs.PLAYER_AURORA_SAMPLES).collectAsState()
    val auroraFps by viewModel.pref(Prefs.PLAYER_AURORA_FPS).collectAsState()
    val auroraQuality by viewModel.pref(Prefs.PLAYER_AURORA_QUALITY).collectAsState()
    val playerAurora by viewModel.pref(Prefs.PLAYER_AURORA).collectAsState()
    SettingSwitch(
        title = stringResource(R.string.setting_player_aurora),
        description = stringResource(R.string.setting_player_aurora_desc),
        checked = playerAurora,
        onCheckedChange = { viewModel.set(Prefs.PLAYER_AURORA, it) }
    )

    SettingSwitch(
        title = stringResource(R.string.setting_aurora_prerender),
        description = stringResource(R.string.setting_aurora_prerender_desc),
        checked = preRendered,
        onCheckedChange = { viewModel.set(Prefs.PLAYER_AURORA_PRERENDER, it) }
    )

    SettingTextField(
        label = stringResource(R.string.setting_aurora_prerender_scale),
        description = stringResource(R.string.setting_aurora_prerender_scale_desc),
        value = preRenderScale.toString(),
        onCommit = { viewModel.set(Prefs.PLAYER_AURORA_PRERENDER_SCALE, it.toInt()) },
        range = Prefs.PLAYER_AURORA_PRERENDER_SCALE.range
    )

    SettingChips(
        title = stringResource(R.string.setting_aurora_quality),
        description = stringResource(R.string.setting_aurora_quality_desc),
        value = auroraQuality.toString(),
        options = listOf(
            "4" to stringResource(R.string.aurora_quality_ultra_saver),
            "0" to stringResource(R.string.aurora_quality_saver),
            "1" to stringResource(R.string.aurora_quality_smooth),
            "2" to stringResource(R.string.aurora_quality_balanced),
            "3" to stringResource(R.string.aurora_quality_high),
        ),
        onValueChange = { viewModel.set(Prefs.PLAYER_AURORA_QUALITY, it.toInt()) }
    )

    SettingSwitch(
        title = stringResource(R.string.setting_aurora_material_cache),
        description = stringResource(R.string.setting_aurora_material_cache_desc),
        checked = materialCache,
        onCheckedChange = { viewModel.set(Prefs.PLAYER_AURORA_MATERIAL_CACHE, it) }
    )

    SettingSegmented(
        title = stringResource(R.string.setting_aurora_samples),
        summary = stringResource(R.string.setting_aurora_samples_desc),
        value = auroraSamples,
        options = AuroraSampling.options.map { it to it.toString() },
        onValueChange = { viewModel.set(Prefs.PLAYER_AURORA_SAMPLES, it) }
    )

    SettingSegmented(
        title = stringResource(R.string.setting_aurora_fps),
        summary = stringResource(R.string.setting_aurora_fps_desc),
        value = auroraFps,
        options = AuroraFrameRate.options.map { it to stringResource(R.string.aurora_fps_value, it) },
        onValueChange = { viewModel.set(Prefs.PLAYER_AURORA_FPS, it) }
    )

    SettingTextField(
        label = stringResource(R.string.setting_aurora_beats),
        description = stringResource(R.string.setting_aurora_beats_desc),
        value = auroraBeats.toString(),
        onCommit = { viewModel.set(Prefs.PLAYER_AURORA_BEATS, it.toInt()) },
        range = Prefs.PLAYER_AURORA_BEATS.range
    )

    SettingSwitch(
        title = stringResource(R.string.setting_player_theme_preview),
        description = stringResource(R.string.setting_player_theme_preview_desc),
        checked = themePreview,
        onCheckedChange = { viewModel.set(Prefs.PLAYER_THEME_PREVIEW, it) }
    )

}
