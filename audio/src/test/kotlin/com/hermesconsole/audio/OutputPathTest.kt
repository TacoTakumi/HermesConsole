package com.hermesconsole.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * REQ-09: the playback rate is whatever the audio_start frame declares - 22050 on OpenVoice,
 * 24000 on VibeVoice - and the track is rebuilt when it changes between turns. A constant here
 * would play every VibeVoice turn ~9% slow and pitched down.
 *
 * REQ-34: the output route is runtime config, defaulting to the built-in speakers - the one
 * configuration the barge-in gate (T-04) actually measures. Any other route re-runs the AEC
 * self-check and surfaces the verdict rather than assuming it still holds.
 */
class OutputPathTest {

    private val speaker = AudioDeviceDescriptor(id = 2, type = DeviceType.BUILTIN_SPEAKER, name = "speakers")
    private val usb = AudioDeviceDescriptor(id = 8, type = DeviceType.USB, name = "usb dac")
    private val builtInMic = AudioDeviceDescriptor(id = 1, type = DeviceType.BUILTIN_MIC, name = "builtin")

    private class FakeSink(override val sampleRateHz: Int) : AudioSink {
        val written = mutableListOf<ByteArray>()
        var closed = false
        override var underrunCount: Int = 0
        override fun write(pcm: ByteArray) { written += pcm }
        override fun stop() = Unit
        override fun close() { closed = true }
    }

    private class RecordingFactory : AudioSinkFactory {
        val created = mutableListOf<FakeSink>()
        override fun create(sampleRateHz: Int): AudioSink = FakeSink(sampleRateHz).also { created += it }
    }

    @Test
    fun `the track rate follows the rate declared on the wire`() {
        val factory = RecordingFactory()
        val player = TtsPlayer(factory)

        player.onAudioStart(sampleRateHz = 24_000) // VibeVoice
        player.onChunk(ByteArray(64))

        assertEquals(listOf(24_000), factory.created.map { it.sampleRateHz })
        assertEquals(24_000, player.currentSampleRateHz)
    }

    @Test
    fun `the player reports the open track's underruns, and says nothing when no track is open`() {
        // A starved track is restarted by the mixer, and every restart is a fresh onset for the
        // echo canceller to re-learn - the standing suspect for the phantom barge-in
        // (docs/aec-spike.md). The count is only evidence if it is read while the track still
        // exists, so barge-in takes it before it stops the speaker.
        val factory = RecordingFactory()
        val player = TtsPlayer(factory)

        assertNull(player.underrunCount, "no track, no count - and reporting 0 here would be a lie")

        player.onAudioStart(sampleRateHz = 24_000)
        factory.created.single().underrunCount = 3
        assertEquals(3, player.underrunCount)

        player.stopImmediately()
        assertNull(player.underrunCount, "torn down: the count went with the track")
    }

    @Test
    fun `a rate change between turns rebuilds the track at the new rate`() {
        val factory = RecordingFactory()
        val player = TtsPlayer(factory)

        player.onAudioStart(22_050) // OpenVoice turn
        player.onChunk(ByteArray(32))
        player.onAudioEnd()

        player.onAudioStart(24_000) // VibeVoice turn
        player.onChunk(ByteArray(32))

        assertEquals(listOf(22_050, 24_000), factory.created.map { it.sampleRateHz })
        assertTrue(factory.created[0].closed, "the stale 22050 track must be released")
        assertNotSame(factory.created[0], factory.created[1])
        assertEquals(24_000, player.currentSampleRateHz)
    }

    @Test
    fun `an unchanged rate reuses the open track`() {
        val factory = RecordingFactory()
        val player = TtsPlayer(factory)

        player.onAudioStart(22_050)
        player.onChunk(ByteArray(8))
        player.onAudioEnd()
        player.onAudioStart(22_050)
        player.onChunk(ByteArray(8))

        assertEquals(1, factory.created.size, "same rate must not churn the track")
        assertSame(factory.created[0], factory.created[0])
        assertEquals(2, factory.created[0].written.size)
    }

    @Test
    fun `playback defaults to the built-in speakers`() {
        val config = AudioConfig()

        assertEquals(OutputRoute.BUILTIN_SPEAKER, config.outputRoute)
        assertEquals(speaker, selectOutputDevice(config.outputRoute, listOf(speaker, usb)))
    }

    @Test
    fun `a non-default route selects a different device`() {
        val config = AudioConfig(outputRoute = OutputRoute.USB)

        assertEquals(usb, selectOutputDevice(config.outputRoute, listOf(speaker, usb)))
    }

    @Test
    fun `a non-default route re-runs the AEC self-check and surfaces the verdict`() {
        var probes = 0
        val probe = AecProbe { device ->
            probes++
            AecStatus(device = device, available = true, enabled = true)
        }

        val default = verifyOutputRoute(AudioConfig(), listOf(builtInMic), probe)
        val rerouted = verifyOutputRoute(
            AudioConfig(outputRoute = OutputRoute.BLUETOOTH), listOf(builtInMic), probe,
        )

        assertEquals(2, probes, "the self-check re-runs for the new route, never cached")
        assertTrue(default.bargeInMeasured, "built-in speakers are the configuration T-04 measures")
        assertFalse(
            rerouted.bargeInMeasured,
            "barge-in was never measured on this route; the verdict must say so, not assume",
        )
        assertTrue(rerouted.aec.available)
    }

    @Test
    fun `the voice loop is untouched by an output route change`() {
        // The loop drives the player through the same three calls whichever route is configured;
        // a route change is a configuration diff, never a state-machine diff (REQ-34).
        fun turn(player: TtsPlayer): Int {
            player.onAudioStart(22_050)
            player.onChunk(ByteArray(16))
            player.onAudioEnd()
            return player.currentSampleRateHz ?: -1
        }

        val onSpeakers = RecordingFactory().also { turn(TtsPlayer(it, AudioConfig())) }
        val onUsb = RecordingFactory().also {
            turn(TtsPlayer(it, AudioConfig(outputRoute = OutputRoute.USB)))
        }

        assertEquals(
            onSpeakers.created.map { it.sampleRateHz },
            onUsb.created.map { it.sampleRateHz },
        )
        assertEquals(
            onSpeakers.created.single().written.size,
            onUsb.created.single().written.size,
        )
    }

    @Test
    fun `barge-in stops playback immediately and drops the queued audio`() {
        val factory = RecordingFactory()
        val player = TtsPlayer(factory)

        player.onAudioStart(22_050)
        player.onChunk(ByteArray(16))
        player.stopImmediately()

        assertTrue(factory.created.single().closed, "the track is torn down on barge-in")
        assertEquals(null, player.currentSampleRateHz)

        // The cancelled request does not go quiet on the wire - VoiceManager finishes the chunk it
        // is synthesising - so its last PCM arrives after the track is gone. It has to be dropped:
        // playing it is the console talking over the person who interrupted it (REQ-04).
        player.onChunk(ByteArray(320))
        assertEquals(1, factory.created.single().written.size, "the abandoned reply was played")
    }

    @Test
    fun `a chunk that loses the race with barge-in does not kill the stream`() {
        // Playback and barge-in are on different threads by construction: the microphone stops the
        // track while the TTS stream is writing to it. A write that is inside AudioTrack when the
        // track is released comes back out as an IllegalStateException, and a console that let it
        // escape would lose the TTS collector - and with it every turn after this one.
        val player = TtsPlayer({ rate -> ReleasedSink(rate) })

        player.onAudioStart(22_050)
        player.onChunk(ByteArray(16))
    }

    /** A track that was released underneath the writer - what AudioTrack does when it loses the race. */
    private class ReleasedSink(override val sampleRateHz: Int) : AudioSink {
        override fun write(pcm: ByteArray): Unit = throw IllegalStateException("track released")
        override fun stop() = Unit
        override fun close() = Unit
    }
}
