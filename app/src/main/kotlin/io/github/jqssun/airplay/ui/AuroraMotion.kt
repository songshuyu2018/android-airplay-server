package io.github.jqssun.airplay.ui

import kotlin.math.*

/** Deterministic new folds per appearance; morph into the next seed during the fade. */
internal object AuroraMotion {
    private fun random(cycle: Double, band: Int, component: Int): Double {
        val v = sin(cycle * 127.1 + band * 269.5 + component * 311.7) * 43758.5453
        return v - floor(v)
    }
    fun writeShapes(time: Double, result: FloatArray) {
        for (band in 0..1) {
            val clock = time / 56.0 + if (band == 0) .10 else .60
            val cycle = floor(clock)
            val f = ((clock-cycle-.90)/.10).coerceIn(0.0,1.0)
            val blend = f*f*(3-2*f)
            for (k in 0..3) {
                val a=random(cycle,band,k);val b=random(cycle+1,band,k)
                val v=a+(b-a)*blend
                result[band*4+k] = when(k) {
                    0 -> ((if (band==0) .85 else .70) + v*.55).toFloat()
                    1 -> (.43+v*.57).toFloat()
                    else -> ((v-.5)*2*PI).toFloat()
                }
            }
        }
    }
}
