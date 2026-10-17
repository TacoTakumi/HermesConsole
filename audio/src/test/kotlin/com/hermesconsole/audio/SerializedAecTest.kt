package com.hermesconsole.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T-04 / REQ-06: every APM call is serialized, because the reference (TTS stream) and the capture
 * (mic flow) cross threads and the APM is not thread-safe.
 *
 * The stress test drives processReference and processCapture from separate coroutines against the
 * wrapper and asserts both halves of the acceptance: no interleaving (the delegate's call intervals
 * are disjoint - each call completes before the next starts) and no lost updates (every frame
 * arrives, in order). The fake records its call intervals under a lock that protects only the
 * append, never the busy work, so overlapping intervals would appear if the wrapper let two calls
 * run concurrently.
 */
class SerializedAecTest {

    private class RecordingFake : SoftwareAec {
        data class Call(val startNs: Long, val endNs: Long, val kind: String)

        val captureInputs = mutableListOf<ByteArray>()
        val referenceInputs = mutableListOf<ByteArray>()
        val calls = mutableListOf<Call>()
        var delaySeedMs: Int? = null
        var closed = false

        private val recordLock = Any()

        @Synchronized
        override fun processCapture(frame: ByteArray): ByteArray {
            val start = System.nanoTime()
            busy()
            synchronized(recordLock) {
                calls += Call(start, System.nanoTime(), "capture")
                captureInputs += frame
            }
            return frame.copyOf()
        }

        @Synchronized
        override fun processReference(pcm: ByteArray) {
            val start = System.nanoTime()
            busy()
            synchronized(recordLock) {
                calls += Call(start, System.nanoTime(), "reference")
                referenceInputs += pcm
            }
        }

        @Synchronized
        override fun setDelaySeedMs(ms: Int) {
            delaySeedMs = ms
        }

        @Synchronized
        override fun close() {
            closed = true
        }

        /** A few hundred microseconds of CPU, so concurrent calls visibly overlap when unsynchronized. */
        private fun busy() {
            var x = 0
            repeat(200_000) { x += it }
        }
    }

    @Test
    fun `reference and capture calls never interleave and nothing is lost`() = runBlocking {
        val fake = RecordingFake()
        val aec = SerializedSoftwareAec(fake)

        withContext(Dispatchers.Default) {
            val captureJob = launch {
                repeat(300) { i -> aec.processCapture(frameOf(i)) }
            }
            val referenceJob = launch {
                repeat(300) { i -> aec.processReference(frameOf(i)) }
            }
            captureJob.join()
            referenceJob.join()
        }

        // Nothing lost: every frame arrived, each caller's frames in order.
        assertEquals(300, fake.captureInputs.size)
        assertEquals(300, fake.referenceInputs.size)
        fake.captureInputs.forEachIndexed { i, frame -> assertTrue(frame.contentEquals(frameOf(i)), "capture frame $i in order") }
        fake.referenceInputs.forEachIndexed { i, frame -> assertTrue(frame.contentEquals(frameOf(i)), "reference frame $i in order") }

        // No interleaving: sorted by start, each call ends before the next begins.
        val ordered = fake.calls.sortedBy { it.startNs }
        assertEquals(600, ordered.size)
        for (i in 0 until ordered.size - 1) {
            assertTrue(
                ordered[i].endNs <= ordered[i + 1].startNs,
                "call ${ordered[i].kind} at $i overlaps the next: no two delegate calls ran concurrently",
            )
        }
    }

    @Test
    fun `seed and close are serialized through the same monitor`() = runBlocking {
        val fake = RecordingFake()
        val aec = SerializedSoftwareAec(fake)

        withContext(Dispatchers.Default) {
            val seedJob = launch { repeat(50) { aec.setDelaySeedMs(it) } }
            val closeJob = launch {
                repeat(10) {
                    aec.processCapture(frameOf(1))
                    aec.processReference(frameOf(2))
                }
            }
            seedJob.join()
            closeJob.join()
        }

        aec.close()
        assertTrue(fake.closed)
        // All 10 capture + 10 reference calls happened, interleaved with the seeds.
        assertEquals(10, fake.captureInputs.size)
        assertEquals(10, fake.referenceInputs.size)
        val ordered = fake.calls.sortedBy { it.startNs }
        for (i in 0 until ordered.size - 1) {
            assertTrue(ordered[i].endNs <= ordered[i + 1].startNs, "no overlapping delegate calls")
        }
    }

    private fun frameOf(n: Int): ByteArray = byteArrayOf((n and 0xFF).toByte(), ((n shr 8) and 0xFF).toByte(), 0, 0)
}
