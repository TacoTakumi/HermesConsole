package com.hermesconsole.audio

import kotlin.math.log10
import kotlin.math.sqrt

/** RMS level of a 16-bit LE PCM frame, in dBFS. Digital silence returns -inf-ish (-120). */
fun rmsDbfs(pcm: ByteArray): Double {
    if (pcm.size < 2) return SILENCE_DBFS
    var sumSquares = 0.0
    var samples = 0
    var i = 0
    while (i + 1 < pcm.size) {
        val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
        sumSquares += sample.toDouble() * sample.toDouble()
        samples++
        i += 2
    }
    if (samples == 0) return SILENCE_DBFS
    val rms = sqrt(sumSquares / samples) / Short.MAX_VALUE
    if (rms <= 0.0) return SILENCE_DBFS
    return 20 * log10(rms)
}

const val SILENCE_DBFS: Double = -120.0

/**
 * Decides whether what the mic hears is someone speaking.
 *
 * Energy alone, deliberately: this is the gate the hardware AEC has to beat (REQ-05). It must be
 * sustained, not instantaneous - a plosive, a door, a chair scrape are all loud for one frame, and
 * barging in on those would make the console feel twitchy.
 */
class SpeechGate(
    private val thresholdDbfs: Double = DEFAULT_THRESHOLD_DBFS,
    private val consecutiveFrames: Int = DEFAULT_CONSECUTIVE_FRAMES,
) {
    private var run = 0
    private var lastLevel = SILENCE_DBFS

    /**
     * The level of the most recent frame judged, in dBFS - whatever the verdict was.
     *
     * Read straight after a trip, this is the energy that fired the gate, and it is the first
     * question asked of every self-interruption: a person at the wall mount and the echo canceller
     * leaking both cross -35 dBFS, but they do not cross it by the same margin.
     */
    val lastLevelDbfs: Double get() = lastLevel

    /** Feed one capture frame. Returns true on the frame where sustained speech is confirmed. */
    fun accept(frame: ByteArray): Boolean {
        val level = rmsDbfs(frame)
        lastLevel = level
        if (level < thresholdDbfs) {
            run = 0
            return false
        }
        run++
        return run == consecutiveFrames
    }

    fun reset() { run = 0 }

    companion object {
        /**
         * Speech at conversational distance lands well above this on the tablet's mic; room tone
         * and AEC residue land well below it. T-04 measures both distributions on the real device
         * and records the margin - this number is only defensible because that gap was measured.
         */
        const val DEFAULT_THRESHOLD_DBFS: Double = -35.0

        /** 5 frames x 20 ms = 100 ms of sustained energy before we call it speech. */
        const val DEFAULT_CONSECUTIVE_FRAMES: Int = 5
    }
}
