package io.github.jqssun.airplay.ui

import kotlin.math.*

/** Visual test input only. Never changes service state or sends transport commands. */
internal object PlayerThemePreview {
    fun enabled(requested: Boolean, aurora: Boolean, connections: Int) =
        requested && aurora && connections == 0

    fun sample(seconds: Float, output: FloatArray): FloatArray {
        require(output.size >= 49)
        val time = if (seconds.isFinite()) max(0f, seconds) else 0f
        val phase = time * 1.38f
        val beat = exp(-(phase - floor(phase)) * 7.5f)
        val swell = 0.4f + 0.3f * sin(time * 0.17f) + 0.1f * sin(time * 0.61f)
        for (i in 0 until 48) {
            val profile = 0.9f * exp(-i / 21f) + 0.15f
            val transient = beat * exp(-i / 16f) * 0.7f
            val detail = 0.22f * sin(time * (1.2f + i * 0.057f) + i * 0.53f) +
                0.12f * sin(time * 0.73f - i * 0.82f)
            output[i] = (profile * (swell + detail) + transient).coerceIn(0.025f, 1f)
        }
        output[48] = beat.coerceIn(0f, 1f)
        return output
    }
}
