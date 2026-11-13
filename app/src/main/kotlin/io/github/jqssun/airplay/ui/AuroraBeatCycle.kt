package io.github.jqssun.airplay.ui

/** Separate wall-time lifetimes from 2x motion time. State stays on the GL thread. */
internal class AuroraBeatCycle {
    var elapsedSeconds = 0.0; private set
    var ageA = 17.6; private set
    var ageB = 35.6; private set
    var generationA = 0; private set
    var generationB = 0; private set
    var fadeA = 1.0; private set
    var fadeB = 1.0; private set
    var lastBorn = -1; private set
    var births = 0; private set
    var beatCount = 0; private set
    private var pending = false
    private var nextBand = 1
    private var due = 20.4
    private var lastSwitch = -18.0
    private var lastBeat = -100.0
    private var armed = true
    private var previousLevel = 0f
    private var fadeStartA = Double.POSITIVE_INFINITY
    private var fadeStartB = Double.POSITIVE_INFINITY

    fun update(dt: Double, beat: Float, threshold: Int) {
        if (dt <= 0 || !dt.isFinite()) return
        elapsedSeconds += dt; ageA += dt; ageB += dt
        val level = if (beat.isFinite()) beat.coerceIn(0f,1f) else 0f
        if (level < .16f) armed = true
        val onset = level >= .32f && elapsedSeconds-lastBeat >= .22 && (armed || level-previousLevel >= .08f)
        previousLevel = level
        if (onset) { armed=false; lastBeat=elapsedSeconds; beatCount++ }
        if (onset && beatCount >= threshold.coerceIn(4,64)) pending = true
        val targetHidden = if (nextBand==0) ageA>=53.0 || elapsedSeconds-fadeStartA>=2.5
            else ageB>=51.0 || elapsedSeconds-fadeStartB>=2.5
        if (pending && !targetHidden && elapsedSeconds-lastSwitch >= 5.5) {
            // Prepare the currently visible target before rebirth; never jump its shape.
            if (nextBand==0) fadeStartA=minOf(fadeStartA,elapsedSeconds)
            else fadeStartB=minOf(fadeStartB,elapsedSeconds)
        }
        val accelerated = onset && pending && targetHidden && elapsedSeconds-lastSwitch >= 8.0
        val timed = elapsedSeconds >= due && (onset || elapsedSeconds-lastBeat > 2.0 || elapsedSeconds-due >= 1.2)
        if (accelerated || timed) {
            val born = nextBand; nextBand = 1-nextBand
            if (born==0) {
                ageA=0.0; generationA++; fadeStartA=Double.POSITIVE_INFINITY
            } else {
                ageB=0.0; generationB++; fadeStartB=Double.POSITIVE_INFINITY
            }
            lastBorn=born; births++; beatCount=0; pending=false
            lastSwitch=elapsedSeconds; due=elapsedSeconds+(if (nextBand==0) 18.0 else 38.0)
        }
        fun fade(start: Double): Double {
            val f=((elapsedSeconds-start)/2.5).coerceIn(0.0,1.0)
            return 1-f*f*(3-2*f)
        }
        fadeA=fade(fadeStartA); fadeB=fade(fadeStartB)
    }
}
