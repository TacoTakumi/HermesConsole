package com.hermesconsole.wakeword

import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The one piece of pure logic in this module: the capture path's 16-bit LE PCM into the normalised
 * floats sherpa-onnx wants. It is worth pinning because getting it wrong is silent - a sign or
 * endianness slip does not crash, it just quietly feeds the model noise and the wake word never
 * fires, which looks exactly like a bad model or a bad microphone.
 */
class PcmConversionTest {

    @Test
    fun `decodes little-endian sample pairs, not big-endian`() {
        // 0x1234 = 4660, little-endian on the wire: low byte first.
        val samples = toFloatSamples(byteArrayOf(0x34, 0x12))

        assertEquals(1, samples.size)
        assertClose(4660f / 32768f, samples[0])
    }

    @Test
    fun `preserves sign of negative samples`() {
        // 0xFFFF = -1 as a two's-complement short. Read unsigned it would become +65535, which is
        // the classic way this conversion breaks.
        val samples = toFloatSamples(byteArrayOf(0xFF.toByte(), 0xFF.toByte()))

        assertClose(-1f / 32768f, samples[0])
    }

    @Test
    fun `normalises full-scale samples into minus one to one`() {
        val fullScale = toFloatSamples(
            byteArrayOf(
                0xFF.toByte(), 0x7F, // +32767, full-scale positive
                0x00, 0x80.toByte(), // -32768, full-scale negative
            ),
        )

        assertTrue(fullScale.all { it >= -1f && it <= 1f }, "out of range: ${fullScale.toList()}")
        assertClose(0.99997f, fullScale[0])
        assertClose(-1f, fullScale[1])
    }

    @Test
    fun `converts a whole capture frame`() {
        // The capture path emits 20 ms chunks: 320 samples x 2 bytes (AudioConfig.chunkBytes).
        val samples = toFloatSamples(ByteArray(640))

        assertEquals(320, samples.size)
    }

    @Test
    fun `drops a trailing odd byte rather than reading past the frame`() {
        val samples = toFloatSamples(byteArrayOf(0x34, 0x12, 0x00))

        assertEquals(1, samples.size)
    }

    @Test
    fun `handles an empty frame`() {
        assertEquals(0, toFloatSamples(ByteArray(0)).size)
    }

    @Test
    fun `applies makeup gain to a quiet capture stream`() {
        // A voice at 2 m lands around here, and it is too quiet for the model as it arrives.
        val quiet = byteArrayOf(0x00, 0x08) // 2048 = 1/16 of full scale

        val amplified = toFloatSamples(quiet, gain = 6.0f)

        assertClose(2048f / 32768f * 6f, amplified[0])
    }

    @Test
    fun `clamps rather than wrapping when gain overdrives a close talker`() {
        // Someone speaking right into the tablet, times a gain set for someone across the room.
        // Wrapping here would turn a loud voice into noise - the exact case the wake word must
        // survive, since a person who leans in is a person expecting to be heard.
        val loud = byteArrayOf(
            0x00, 0x40, // +16384, half scale positive
            0x00, 0xC0.toByte(), // -16384, half scale negative
        )

        val amplified = toFloatSamples(loud, gain = 6.0f)

        assertClose(1.0f, amplified[0])
        assertClose(-1.0f, amplified[1])
    }

    @Test
    fun `leaves samples untouched at unit gain`() {
        assertClose(4660f / 32768f, toFloatSamples(byteArrayOf(0x34, 0x12), gain = 1.0f)[0])
    }

    private fun assertClose(expected: Float, actual: Float) {
        assertTrue(abs(expected - actual) < 1e-5f, "expected ~$expected, was $actual")
    }
}
