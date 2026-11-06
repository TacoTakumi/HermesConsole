package com.hermesconsole.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Placing a speaker edge on the mic stream a client sent. Both devices report a frame position and
 * the CLOCK_MONOTONIC time of that frame; the edge's time on the track's line becomes a position on
 * the recorder's line, and the recorder's line becomes the count of samples sent, anchored by the
 * frames the client actually sent.
 */
class PlaybackClockTest {

    private val second = 1_000_000_000L

    @Test
    fun `a track frame's time maps to the record index captured at that time`() {
        // The track presented frame 24000 at t=10 s; frame 36000 plays half a second later at 24 kHz.
        val edgeNs = timeOfFrame(frame = 36_000, anchorFrame = 24_000, anchorNs = 10 * second, rateHz = 24_000)
        assertEquals(10 * second + second / 2, edgeNs)

        // The recorder captured frame 160000 at t=9 s, so t=10.5 s is 1.5 s of 16 kHz later.
        val recordIndex = frameAtTime(timeNs = edgeNs, anchorFrame = 160_000, anchorNs = 9 * second, rateHz = 16_000)
        assertEquals(184_000.0, recordIndex, 1e-6)

        // Everything recorded so far was sent: the sent index is the record index.
        val clock = MicSampleClock()
        clock.anchor(recordFramesEnd = 200_000, sentSamplesEnd = 200_000)
        assertEquals(184_000L, clock.micSampleAt(recordIndex))
    }

    @Test
    fun `a gap between sessions does not count`() {
        val clock = MicSampleClock()
        // Session one sent the first second of recording.
        clock.anchor(recordFramesEnd = 16_000, sentSamplesEnd = 16_000)
        // A minute later the next session sends its first 20 ms frame: recorded 976320, sent 16320.
        clock.anchor(recordFramesEnd = 976_320, sentSamplesEnd = 16_320)
        // An edge half a second into session two sits half a second after its first sent sample.
        assertEquals(16_000L + 8_000L, clock.micSampleAt(976_000.0 + 8_000.0))
    }

    @Test
    fun `an edge before the stream started clamps to zero`() {
        val clock = MicSampleClock()
        clock.anchor(recordFramesEnd = 50_000, sentSamplesEnd = 320)
        assertEquals(0L, clock.micSampleAt(40_000.0))
    }

    @Test
    fun `a clock with no sent frame has no index`() {
        val clock = MicSampleClock()
        assertNull(clock.micSampleAt(1_000.0))
        clock.anchor(recordFramesEnd = 320, sentSamplesEnd = 320)
        clock.reset()
        assertNull(clock.micSampleAt(1_000.0))
    }

    @Test
    fun `the fallback without a capture timestamp lands within 20 ms`() {
        // Truth: the recorder captures frame f at t = 2 s + f / 16 kHz.
        val truth = { f: Long -> 2 * second + f * second / 16_000 }
        // The reader got frames up to 48000 back 10 ms after the last was captured.
        val lastReadNs = truth(48_000) + 10_000_000
        val edgeNs = truth(56_000)

        val estimate = frameAtTime(timeNs = edgeNs, anchorFrame = 48_000, anchorNs = lastReadNs, rateHz = 16_000)
        val errorMs = abs(estimate - 56_000) / 16.0
        assertTrue(errorMs <= 20.0, "fallback off by $errorMs ms")
    }

    @Test
    fun `reply_sample counts played frames at the reply's declared rate`() {
        assertEquals(48_000L, replySamples(playedFrames = 24_000, playedRateHz = 24_000, declaredRateHz = 48_000))
        assertEquals(24_000L, replySamples(playedFrames = 24_000, playedRateHz = 24_000, declaredRateHz = 24_000))
    }
}
