package io.github.jqssun.airplay.ui

/** Fixed phase deadlines; render at most once per callback and skip expired slots. */
internal class AuroraFramePacer {
    private var nextDeadline = Long.MIN_VALUE
    private var previousCallback = Long.MIN_VALUE

    fun reset() { nextDeadline = Long.MIN_VALUE; previousCallback = Long.MIN_VALUE }

    fun shouldRender(now: Long, interval: Long): Boolean {
        require(interval > 0)
        if (now < previousCallback) reset()
        previousCallback = now
        if (nextDeadline == Long.MIN_VALUE) nextDeadline = now
        // Accommodate small display timestamp jitter without moving the phase.
        if (now < nextDeadline - 500_000L) return false
        val late = now - nextDeadline
        val slots = if (late >= 0) late / interval + 1 else 1
        nextDeadline += slots * interval
        return true
    }
}
