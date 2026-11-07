package io.github.jqssun.airplay.service

/** An explicit request, distinct from retained playback state and ordinary activity resumes. */
data class AudioPageRequest(
    val id: Long = 0,
    val open: Boolean = false,
    val forceAutomatic: Boolean = false,
    val forceManual: Boolean = false,
)
