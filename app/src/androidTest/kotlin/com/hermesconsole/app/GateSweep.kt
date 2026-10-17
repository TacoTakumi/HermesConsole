package com.hermesconsole.app

import com.hermesconsole.audio.SpeechGate

/**
 * Runs the barge-in gate at several candidate thresholds at once over one recording.
 *
 * The threshold is the single number the whole barge-in design rests on, and it must not be
 * guessed: it has to fire on a person at the wall mount and never on the tablet's own voice.
 * Sweeping the same frames through every candidate lets both recordings - human speech, and
 * cancelled echo - vote on it directly.
 */
class GateSweep(val thresholds: List<Double> = CANDIDATES) {

    private val gates = thresholds.map { SpeechGate(thresholdDbfs = it) }
    private val detections = IntArray(thresholds.size)

    fun accept(frame: ByteArray) {
        gates.forEachIndexed { i, gate -> if (gate.accept(frame)) detections[i]++ }
    }

    fun detectionsAt(threshold: Double): Int = detections[thresholds.indexOf(threshold)]

    fun report(): String = thresholds
        .mapIndexed { i, t -> "${t.toInt()}dBFS=${detections[i]}" }
        .joinToString("  ")

    companion object {
        val CANDIDATES: List<Double> =
            listOf(-30.0, -35.0, -40.0, -45.0, -50.0, -55.0, -60.0, -65.0, -70.0)
    }
}
