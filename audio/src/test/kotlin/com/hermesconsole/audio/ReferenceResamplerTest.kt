package com.hermesconsole.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * T-02 / REQ-03, REQ-04: the TTS reference is resampled to the APM's 16 kHz before it reaches
 * ProcessReverseStream, with the resampler as the only rate conversion (D-05: pure Kotlin, so the
 * conversion is JVM-testable).
 *
 * Each case feeds a 1 kHz tone at the engine rate and asserts the two things that matter to the
 * AEC: the output is at the right rate (sample count = in * 16000 / rate) and the level is
 * preserved (RMS within +-1 dB of the input). A 1 kHz tone sits well inside the 8 kHz output
 * Nyquist, so any level error is resampler error, not content.
 */
class ReferenceResamplerTest {

    /** A [durationMs]-long 1 kHz sine at [amplitude] of full scale, as int16 PCM mono LE. */
    private fun toneAt(rateHz: Int, durationMs: Int, amplitude: Double = 0.5): ByteArray {
        val samples = rateHz * durationMs / 1000
        val bytes = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val v = (amplitude * Short.MAX_VALUE * sin(2 * PI * 1000.0 * i / rateHz)).toInt().toShort()
            bytes[i * 2] = (v.toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v.toInt() shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private fun resampled(rateHz: Int, durationMs: Int): ByteArray {
        val r = ReferenceResampler(rateHz)
        val out = r.resample(toneAt(rateHz, durationMs))
        val flushed = r.flush()
        return out + flushed
    }

    @Test
    fun `48000 to 16000 decimates by 3 and preserves level`() {
        val input = toneAt(48_000, 100)
        val output = resampled(48_000, 100)

        assertEquals(1600, output.size / 2, "4800 samples at 48k become 1600 at 16k")
        assertEquals(input.size / 3, output.size)
        assertWithin1Db(rmsDbfs(input), rmsDbfs(output))
    }

    @Test
    fun `24000 to 16000 resamples by 2 over 3 and preserves level`() {
        val input = toneAt(24_000, 100)
        val output = resampled(24_000, 100)

        assertEquals(1600, output.size / 2, "2400 samples at 24k become 1600 at 16k")
        assertEquals(input.size * 2 / 3, output.size)
        assertWithin1Db(rmsDbfs(input), rmsDbfs(output))
    }

    @Test
    fun `22050 to 16000 resamples by 320 over 441 and preserves level`() {
        val input = toneAt(22_050, 100)
        val output = resampled(22_050, 100)

        assertEquals(1600, output.size / 2, "2205 samples at 22050 become 1600 at 16k")
        assertEquals(input.size * 16_000 / 22_050, output.size)
        assertWithin1Db(rmsDbfs(input), rmsDbfs(output))
    }

    @Test
    fun `streaming chunks produce the same output as one long feed`() {
        val rate = 48_000
        val chunk = toneAt(rate, 20)
        val streamed = ReferenceResampler(rate)
        val parts = mutableListOf<ByteArray>()
        repeat(5) { parts += streamed.resample(chunk) }
        parts += streamed.flush()

        val oneShot = resampled(rate, 100)
        // Chunking must not change the resampled content: same length, level within 0.1 dB.
        val streamedAll = parts.reduce { a, b -> a + b }
        assertEquals(oneShot.size, streamedAll.size)
        assertTrue(kotlin.math.abs(rmsDbfs(streamedAll) - rmsDbfs(oneShot)) < 0.1, "chunked vs one-shot RMS within 0.1 dB")
    }

    private fun assertWithin1Db(inputDb: Double, outputDb: Double) {
        val diff = kotlin.math.abs(outputDb - inputDb)
        assertTrue(diff <= 1.0, "RMS preserved within +-1 dB (input=${"%.2f".format(inputDb)} dBFS, output=${"%.2f".format(outputDb)} dBFS, diff=$diff)")
    }
}
