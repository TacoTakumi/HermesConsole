package com.hermesconsole.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * TTS playback rate handling (REQ-09) and the native-rate downsample (docs/aec-spike.md, "The
 * ninth: the sample-rate bypass").
 *
 * The rate arrives on the wire and differs by engine. Below the mixer ceiling it is played as
 * declared; at or above it - audiocpp's native 48 kHz - it is halved so the track leaves the
 * device's native output path and the hardware AEC can reference it.
 */
class TtsPlayerTest {

    private class FakeSink(override val sampleRateHz: Int) : AudioSink {
        val written = mutableListOf<ByteArray>()
        override fun write(pcm: ByteArray) { written += pcm }
        override fun stop() = Unit
        override fun close() = Unit
    }

    private val built = mutableListOf<FakeSink>()
    private fun player(config: AudioConfig = AudioConfig()) =
        TtsPlayer({ rate -> FakeSink(rate).also { built += it } }, config)

    // The downsample defaults OFF (a refuted hypothesis, docs/aec-spike.md). Tests that exercise it
    // opt in explicitly; the default-off behavior is locked by its own test below.
    private val downsampling = AudioConfig(forceTtsThroughMixer = true)

    @Test
    fun `the downsample is off by default - the native rate is played as declared`() {
        val player = player()
        player.onAudioStart(48_000) // audiocpp dots, the SM-X210 native rate
        assertEquals(48_000, player.currentSampleRateHz, "default off: native rate straight to the track")
    }

    @Test
    fun `a rate below the ceiling is played as declared even when downsampling is on`() {
        val player = player(downsampling)
        player.onAudioStart(24_000) // VibeVoice
        assertEquals(24_000, player.currentSampleRateHz, "sub-ceiling rates route through the mixer already")
    }

    @Test
    fun `with the toggle on the native rate is halved`() {
        val player = player(downsampling)
        player.onAudioStart(48_000)
        assertEquals(24_000, player.currentSampleRateHz, "on: 48 kHz is halved")
    }

    @Test
    fun `a halved reply decimates its chunks to match the track`() {
        val player = player(downsampling)
        player.onAudioStart(48_000)
        // 8 samples (16 bytes) at 48k -> 4 samples (8 bytes) at 24k.
        player.onChunk(ByteArray(16))
        assertEquals(8, built.single().written.single().size, "chunk length must halve with the rate")
    }

    @Test
    fun `an unhalved reply passes its chunks through untouched`() {
        val player = player()
        player.onAudioStart(24_000)
        player.onChunk(ByteArray(16))
        assertEquals(16, built.single().written.single().size)
    }

    @Test
    fun `pair-averaging is the decimation, so a constant signal is preserved`() {
        val player = player(downsampling)
        player.onAudioStart(48_000)
        // Four identical samples of value 1000 (LE): averaging pairs must give 1000, 1000.
        val s = byteArrayOf(0xE8.toByte(), 0x03, 0xE8.toByte(), 0x03, 0xE8.toByte(), 0x03, 0xE8.toByte(), 0x03)
        player.onChunk(s)
        val out = built.single().written.single()
        val first = (out[0].toInt() and 0xFF) or (out[1].toInt() shl 8)
        val second = (out[2].toInt() and 0xFF) or (out[3].toInt() shl 8)
        assertEquals(1000, first)
        assertEquals(1000, second)
    }

    @Test
    fun `a repeated declared rate reuses the track rather than rebuilding it`() {
        val player = player()
        player.onAudioStart(48_000)
        player.onAudioStart(48_000)
        assertEquals(1, built.size, "the same declared rate must not churn the track")
    }

    // -- the reference tap (T-03, REQ-03) ------------------------------------------------------

    @Test
    fun `the reference consumer receives every chunk written to the sink, in order`() {
        val player = player()
        val tapped = mutableListOf<ByteArray>()
        player.referenceConsumer = { tapped += it }
        player.onAudioStart(24_000) // VibeVoice, no decimation

        player.onChunk(byteArrayOf(1, 2, 3, 4))
        player.onChunk(byteArrayOf(5, 6))
        player.onChunk(byteArrayOf(7, 8, 9, 10))

        assertEquals(built.single().written, tapped, "the consumer sees exactly what the sink writes, in order")
    }

    @Test
    fun `with decimation the consumer receives the decimated bytes, not the raw ones`() {
        val player = player(downsampling)
        val tapped = mutableListOf<ByteArray>()
        player.referenceConsumer = { tapped += it }
        player.onAudioStart(48_000)

        // 8 samples (16 bytes) at 48k -> 4 samples (8 bytes) at 24k, and the tap sees the 8 bytes.
        player.onChunk(ByteArray(16))

        assertEquals(8, built.single().written.single().size)
        assertEquals(built.single().written.single(), tapped.single())
    }

    @Test
    fun `the reference consumer receives nothing after stopImmediately`() {
        val player = player()
        val tapped = mutableListOf<ByteArray>()
        player.referenceConsumer = { tapped += it }
        player.onAudioStart(24_000)

        player.onChunk(byteArrayOf(1, 2, 3, 4))
        player.stopImmediately()
        player.onChunk(byteArrayOf(5, 6, 7, 8)) // the tail of an abandoned reply

        assertEquals(1, tapped.size, "chunks after barge-in are dropped, never tapped")
        assertContentEquals(byteArrayOf(1, 2, 3, 4), tapped.single())
    }

    private fun assertContentEquals(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.toList(), actual.toList())
    }
}
