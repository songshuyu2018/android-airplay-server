package io.github.jqssun.airplay.ui

/** Independent from resolution. Lower rates do not slow the renderer's elapsed-time clock. */
internal object AuroraFrameRate {
    val options: List<Int> = listOf(15, 20, 30, 60)

    fun intervalNanos(value: Int): Long = 1_000_000_000L / (if (value in options) value else 20)
}
