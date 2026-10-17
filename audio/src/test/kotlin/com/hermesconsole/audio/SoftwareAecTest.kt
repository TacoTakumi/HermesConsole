package com.hermesconsole.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T-01 / REQ-07: the software AEC is a testable seam - an interface with a fake implementation -
 * so the whole :audio test suite runs on the JVM with no native library present.
 */
class SoftwareAecTest {

    @Test
    fun `fake records capture, reference and seed in call order`() {
        val aec = FakeSoftwareAec()
        val capture1 = byteArrayOf(1, 2, 3, 4)
        val capture2 = byteArrayOf(5, 6, 7, 8)
        val ref = byteArrayOf(9, 10)

        aec.setDelaySeedMs(42)
        val out1 = aec.processCapture(capture1)
        aec.processReference(ref)
        val out2 = aec.processCapture(capture2)

        assertEquals(42, aec.delaySeedMs)
        assertEquals(listOf(capture1, capture2), aec.captureInputs)
        assertEquals(listOf(ref), aec.referenceInputs)
        // The cancelled frame is fresh-owned, never the caller's buffer aliased.
        assertNotSame(capture1, out1)
        assertTrue(out1.contentEquals(capture1))
        assertTrue(out2.contentEquals(capture2))
        assertFalse(aec.closed)

        aec.close()
        assertTrue(aec.closed)
    }
}
