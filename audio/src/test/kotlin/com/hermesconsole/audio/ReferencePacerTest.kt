package com.hermesconsole.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReferencePacerTest {

    private val frame = ReferencePacer.FRAME_BYTES

    @Test
    fun `drain hands back whole fixed frames in order`() {
        val pacer = ReferencePacer()
        pacer.append(ByteArray(frame * 2))
        pacer.append(ByteArray(frame))
        assertEquals(frame * 3, pacer.bufferedBytes)

        repeat(3) { assertEquals(frame, pacer.drainFrame(frame).size) }
        assertEquals(0, pacer.bufferedBytes)
    }

    @Test
    fun `irregular chunk cadence is reassembled into exact frames`() {
        // The TTS tap is bursty: 7 ms, 3 ms, a 40 ms burst - never frame-aligned.
        val chunks = listOf(1120, 480, 3200, 413).map { ByteArray(it) { (it % 127).toByte() } }
        val pacer = ReferencePacer()
        chunks.forEach { pacer.append(it) }

        val drained = mutableListOf<ByteArray>()
        while (pacer.bufferedBytes > 0) {
            drained += pacer.drainFrame(frame)
        }
        // Every drained frame is a whole 640-byte frame.
        drained.forEach { assertEquals(frame, it.size) }

        // The concatenated drained bytes equal the appended bytes (order preserved), then a
        // zero-padded partial tail.
        val all = drained.fold(ByteArray(0)) { acc, f -> acc + f }
        val appended = chunks.fold(ByteArray(0)) { a, b -> a + b }
        val wholeFrames = appended.size / frame * frame
        assertTrue(all.copyOfRange(0, wholeFrames).contentEquals(appended.copyOfRange(0, wholeFrames)))
        // The leftover partial (if any) is zero-padded as silence, not garbage.
        val remainder = appended.size - wholeFrames
        repeat(remainder) { i -> assertEquals(appended[wholeFrames + i], all[wholeFrames + i]) }
        if (remainder < frame) {
            repeat(frame - remainder) { i -> assertEquals(0, all[wholeFrames + remainder + i]) }
        }
    }

    @Test
    fun `drain pads a short buffer with silence to a full frame`() {
        val pacer = ReferencePacer()
        pacer.append(ByteArray(64))
        val out = pacer.drainFrame(frame)
        assertEquals(frame, out.size)
        assertTrue(out.copyOfRange(64, frame).all { it == 0.toByte() })
        assertEquals(0, pacer.bufferedBytes)
    }

    @Test
    fun `drain on an empty buffer returns a full silence frame`() {
        val pacer = ReferencePacer()
        val out = pacer.drainFrame(frame)
        assertEquals(frame, out.size)
        assertTrue(out.all { it == 0.toByte() })
    }

    @Test
    fun `clear drops everything queued`() {
        val pacer = ReferencePacer()
        pacer.append(ByteArray(frame * 4))
        assertEquals(frame * 4, pacer.bufferedBytes)
        pacer.clear()
        assertEquals(0, pacer.bufferedBytes)
    }
}