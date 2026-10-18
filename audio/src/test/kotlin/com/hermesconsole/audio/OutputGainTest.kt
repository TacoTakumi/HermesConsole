package com.hermesconsole.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The console's own playback level, read by every track it builds on each write. */
class OutputGainTest {

    @Test
    fun `a new gain is unity`() {
        assertEquals(1f, OutputGain().value)
    }

    @Test
    fun `values outside 0 to 1 are clamped`() {
        assertEquals(0f, OutputGain(-0.5f).value)
        assertEquals(1f, OutputGain(3f).value)
        val gain = OutputGain()
        gain.set(1.5f)
        assertEquals(1f, gain.value)
        gain.set(-1f)
        assertEquals(0f, gain.value)
    }

    @Test
    fun `set reports a change only when the value moved`() {
        val gain = OutputGain(0.5f)
        assertFalse(gain.set(0.5f))
        assertTrue(gain.set(0.4f))
        assertEquals(0.4f, gain.value)
        // A clamp that lands on the current value is no change.
        gain.set(1f)
        assertFalse(gain.set(2f))
    }
}
