@file:Suppress("UNUSED_PARAMETER")

package io.github.jqssun.airplay.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.jqssun.airplay.viewmodel.MainViewModel

/** Compile-time bridge only: this source set has no renderer, shader or animation assets. */
@Composable
internal fun auroraThemeEnabled(viewModel: MainViewModel): Boolean = false

@Composable
internal fun AuroraThemeBackdrop(viewModel: MainViewModel, playing: Boolean,
    energy: () -> Float, beat: () -> Float, spectrum: () -> FloatArray,
    modifier: Modifier = Modifier, onFailure: () -> Unit) = Unit

@Composable
internal fun AuroraSettings(viewModel: MainViewModel) = Unit

internal object PlayerThemePreview {
    fun enabled(requested: Boolean, aurora: Boolean, connections: Int) = false
    fun sample(seconds: Float, output: FloatArray): FloatArray = output
}
