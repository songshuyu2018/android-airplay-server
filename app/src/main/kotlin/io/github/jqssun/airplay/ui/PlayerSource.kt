package io.github.jqssun.airplay.ui

import io.github.jqssun.airplay.audio.TrackInfo
import kotlinx.coroutines.flow.StateFlow

/** Shared player surface contract; neither an Activity nor a ViewModel is required. */
interface PlayerSource {
    val trackInfo: StateFlow<TrackInfo>
    val connectionCount: StateFlow<Int>
    val audioOnly: StateFlow<Boolean>
    val audioPlaying: StateFlow<Boolean>
    val audioControlsReady: StateFlow<Boolean>
    val audioPositionMs: StateFlow<Long>
    val audioDurationMs: StateFlow<Long>
    fun toggleAudioPlayback()
    fun previousAudioTrack()
    fun nextAudioTrack()
    fun audioSpectrum(enabled: Boolean): FloatArray
}
