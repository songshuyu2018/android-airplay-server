package io.github.jqssun.airplay.ui

import kotlin.math.*

/** 96 depth-only values, shared by all pixels. Reuse caller storage; keep shader float time. */
internal object AuroraDepthWind {
    fun write(time: Float, result: FloatArray) {
        for (i in 0 until 96) {
            val z = 1f + i * (7.8f / 95f)
            val a = 2.64f * .65f + z * .32f + time * .060f
            val b = 2.64f * 1.10f - z * .47f + time * .039f
            result[i*2] = .25f * sin(a) + .065f * sin(b)
            result[i*2+1] = .25f * .32f * cos(a) - .065f * .47f * cos(b)
        }
    }
}
