package com.hermesconsole.audio

import kotlin.math.PI
import kotlin.math.sin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The energy threshold barge-in is decided on (REQ-04/REQ-05). The hardware gate (T-04) measures
 * the AEC-processed capture stream against exactly this gate while the tablet talks: if the
 * tablet's own voice ever crosses it, the tablet barges in on itself.
 */
class SpeechEnergyTest {

    /** A frame of 20 ms at 16 kHz: 320 samples, 640 bytes. */
    private fun tone(amplitude: Double, samples: Int = 320): ByteArray {
        val bytes = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val v = (amplitude * Short.MAX_VALUE * sin(2 * PI * 440 * i / 16_000)).toInt().toShort()
            bytes[i * 2] = (v.toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v.toInt() shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private fun silence(samples: Int = 320) = ByteArray(samples * 2)

    @Test
    fun `digital silence is far below any speech threshold`() {
        assertTrue(rmsDbfs(silence()) < -90.0, "got ${rmsDbfs(silence())}")
    }

    @Test
    fun `a full-scale tone sits near zero dBFS`() {
        val level = rmsDbfs(tone(amplitude = 1.0))
        assertTrue(level > -4.0 && level <= 0.0, "full-scale sine RMS should be about -3 dBFS, got $level")
    }

    @Test
    fun `rms rises with amplitude`() {
        assertTrue(rmsDbfs(tone(0.5)) > rmsDbfs(tone(0.05)))
    }

    @Test
    fun `a single loud frame does not trip the gate`() {
        // Hysteresis: barge-in must not fire on a door slam or a plosive tick.
        val gate = SpeechGate()
        assertFalse(gate.accept(tone(0.5)))
    }

    @Test
    fun `sustained speech-level energy trips the gate`() {
        val gate = SpeechGate()
        val loud = tone(0.5)
        val results = (1..SpeechGate.DEFAULT_CONSECUTIVE_FRAMES).map { gate.accept(loud) }

        assertFalse(results.dropLast(1).any { it }, "must wait for sustained energy")
        assertTrue(results.last(), "the gate trips once the energy is sustained")
    }

    @Test
    fun `quiet frames reset the run of loud frames`() {
        val gate = SpeechGate()
        val loud = tone(0.5)

        repeat(SpeechGate.DEFAULT_CONSECUTIVE_FRAMES - 1) { gate.accept(loud) }
        gate.accept(silence())
        assertFalse(gate.accept(loud), "the run restarts after a quiet frame")
    }

    @Test
    fun `residual echo below the threshold never trips the gate`() {
        // What T-04 asserts on hardware, in miniature: quiet leakage, however long it runs, is not speech.
        val gate = SpeechGate()
        val leakage = tone(0.001) // about -60 dBFS

        assertTrue(rmsDbfs(leakage) < SpeechGate.DEFAULT_THRESHOLD_DBFS)
        assertFalse((1..500).any { gate.accept(leakage) })
    }

    @Test
    fun `the gate reports the level it judged, fired or not`() {
        // The number that decides REQ-04 is the number the barge-in log has to carry. Three
        // phantom self-interruptions have now been classified by hand from a server log and a
        // shrug (docs/aec-spike.md); a gate that answers only yes/no leaves the fourth exactly as
        // unexplained as the first three.
        val gate = SpeechGate()
        val loud = tone(0.5)

        gate.accept(loud)
        assertEquals(rmsDbfs(loud), gate.lastLevelDbfs, 0.001)

        gate.accept(silence())
        assertEquals(rmsDbfs(silence()), gate.lastLevelDbfs, 0.001, "the last frame's level, not the last trip's")
    }

    @Test
    fun `the default threshold is the one the gate is measured against`() {
        assertEquals(-35.0, SpeechGate.DEFAULT_THRESHOLD_DBFS)
        assertEquals(5, SpeechGate.DEFAULT_CONSECUTIVE_FRAMES) // 100 ms at 20 ms/frame
    }
}
