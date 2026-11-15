package io.github.jqssun.airplay.ui

internal object AuroraSampling {
    val options = listOf(48, 64, 80, 96)
    fun normalize(value: Int): Int = if (value in options) value else 96
}
