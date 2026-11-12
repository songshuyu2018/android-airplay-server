package io.github.jqssun.airplay.ui

import kotlin.math.floor
import kotlin.math.sqrt

/** Pixel and frame budgets apply only to the aurora SurfaceView, never the foreground. */
internal enum class AuroraQuality(
    val maxWidth: Int, val maxHeight: Int, val maxPixels: Int, val framesPerSecond: Int
) {
    SAVER(320, 240, 57_600, 15),
    SMOOTH(480, 360, 129_600, 20),
    BALANCED(640, 480, 230_400, 30),
    HIGH(960, 720, 518_400, 30);

    val frameIntervalNanos: Long get() = 1_000_000_000L / framesPerSecond

    fun bufferSize(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 1 to 1
        val scale = minOf(1.0, maxWidth.toDouble() / width, maxHeight.toDouble() / height,
            sqrt(maxPixels.toDouble() / (width.toDouble() * height)))
        return maxOf(1, floor(width * scale).toInt()) to maxOf(1, floor(height * scale).toInt())
    }

    companion object {
        fun fromSetting(value: Int): AuroraQuality = values().getOrNull(value) ?: SMOOTH
    }
}
