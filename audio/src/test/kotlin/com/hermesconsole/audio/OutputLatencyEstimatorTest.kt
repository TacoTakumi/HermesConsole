package com.hermesconsole.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * T-07 / REQ-05: the APM's delay controller is seeded from a measured output-latency estimate,
 * never a hardcoded constant. The estimator's math is the whole device-independent part, so it is
 * pinned here on the JVM; the device supplies the same numbers AudioTrack.getTimestamp reports
 * (CDD 5.6 C-1-1, +-2 ms).
 */
class OutputLatencyEstimatorTest {

    private val estimator = OutputLatencyEstimator()

    @Test
    fun `in-flight frames divided by rate is the output latency`() {
        // 4800 frames written, 2400 presented: 2400 still in flight at 48 kHz = 50 ms.
        assertEquals(50.0, estimator.estimateLatencyMs(4800, 48_000, AudioTimestampSnapshot(2400, 0)), 0.001)
    }

    @Test
    fun `the same span at 16 kHz gives the same millisecond answer`() {
        assertEquals(50.0, estimator.estimateLatencyMs(1600, 16_000, AudioTimestampSnapshot(800, 0)), 0.001)
    }

    @Test
    fun `a track that has presented everything has zero latency`() {
        assertEquals(0.0, estimator.estimateLatencyMs(4410, 44_100, AudioTimestampSnapshot(4410, 0)), 0.001)
    }

    @Test
    fun `a stale or rolled-back presentation position cannot go negative`() {
        assertEquals(0.0, estimator.estimateLatencyMs(4800, 48_000, AudioTimestampSnapshot(5000, 0)), 0.001)
    }

    @Test
    fun `an unknown sample rate yields zero rather than a divide by zero`() {
        assertEquals(0.0, estimator.estimateLatencyMs(4800, 0, AudioTimestampSnapshot(0, 0)), 0.001)
    }
}
