package com.hermesconsole.app

import com.hermesconsole.audio.OneShotSink
import com.hermesconsole.voiceclient.TtsEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T-09 / REQ-11: a rising approval says so out loud - and the voice can never cost more than
 * itself.
 *
 * The announcer lives outside [VoiceLoop] on purpose (an in-loop announcement would flip the
 * console to SPEAKING and arm barge-in against the operator's own "deny"), so what these tests
 * pin is the whole contract: the fixed line synthesized once through a dedicated link, cached,
 * played through the one-shot cue path at the wire's rate, silent while a reply speaks, and a
 * dead backend skipped without a throw and retried on the next rise.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalAnnouncerTest {

    private class FakeSynth : AnnouncementSynth {
        data class Sent(val requestId: String, val text: String, val done: Boolean)

        val sent = mutableListOf<Sent>()
        var closed = false

        override val tts: Flow<TtsEvent> = flow {
            emit(TtsEvent.Start("approval-announcement", RATE_HZ))
            emit(TtsEvent.Audio(CHUNK_ONE, RATE_HZ))
            emit(TtsEvent.Audio(CHUNK_TWO, RATE_HZ))
            emit(TtsEvent.End("approval-announcement"))
        }

        override fun send(requestId: String, text: String, done: Boolean) {
            sent += Sent(requestId, text, done)
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeSink : OneShotSink {
        data class Played(val pcm: ByteArray, val sampleRateHz: Int)

        val plays = mutableListOf<Played>()

        override fun play(pcm: ByteArray, sampleRateHz: Int) {
            plays += Played(pcm.copyOf(), sampleRateHz)
        }
    }

    @Test
    fun `the first speak synthesizes the fixed line once and plays it whole at the wire rate`() =
        runTest(UnconfinedTestDispatcher()) {
            val synth = FakeSynth()
            val sink = FakeSink()
            val announcer = ApprovalAnnouncer(
                synths = { synth },
                sink = sink,
                scope = backgroundScope,
                speaking = { false },
            )

            announcer.speak()

            assertEquals(
                listOf(FakeSynth.Sent("approval-announcement", ApprovalAnnouncer.LINE, done = true)),
                synth.sent,
                "one synthesis of the fixed line, flushed with done",
            )
            val played = sink.plays.single()
            assertEquals(RATE_HZ, played.sampleRateHz, "played at the rate the wire declared")
            assertTrue((CHUNK_ONE + CHUNK_TWO).contentEquals(played.pcm), "the whole line, in order")
            assertTrue(synth.closed, "the announcement's link is not held open")
        }

    @Test
    fun `later speaks replay the cache - the line is synthesized once, ever`() =
        runTest(UnconfinedTestDispatcher()) {
            var opened = 0
            val sink = FakeSink()
            val announcer = ApprovalAnnouncer(
                synths = { opened++; FakeSynth() },
                sink = sink,
                scope = backgroundScope,
                speaking = { false },
            )

            announcer.speak()
            announcer.speak()

            assertEquals(1, opened, "the fixed line never needs a second synthesis")
            assertEquals(2, sink.plays.size, "every rise plays; only the first synthesizes")
        }

    @Test
    fun `a speak while the reply is talking stays silent - cue-only, reply audio untouched`() =
        runTest(UnconfinedTestDispatcher()) {
            var opened = 0
            val sink = FakeSink()
            val announcer = ApprovalAnnouncer(
                synths = { opened++; FakeSynth() },
                sink = sink,
                scope = backgroundScope,
                speaking = { true },
            )

            announcer.speak()

            assertEquals(0, opened, "no synthesis is even started over a live reply")
            assertTrue(sink.plays.isEmpty(), "nothing plays over the reply")
        }

    @Test
    fun `a dead voice backend costs the voice and nothing else - and the next rise retries`() =
        runTest(UnconfinedTestDispatcher()) {
            var opened = 0
            val sink = FakeSink()
            val announcer = ApprovalAnnouncer(
                synths = {
                    opened++
                    if (opened == 1) error("voicemanager unreachable") else FakeSynth()
                },
                sink = sink,
                scope = backgroundScope,
                speaking = { false },
            )

            announcer.speak()
            assertTrue(sink.plays.isEmpty(), "a failed synthesis plays nothing")

            announcer.speak()
            assertEquals(2, opened, "the next rise retries the synthesis")
            assertEquals(1, sink.plays.size, "and the retry's line is heard")
        }

    private companion object {
        const val RATE_HZ = 22_050
        val CHUNK_ONE = ByteArray(64) { it.toByte() }
        val CHUNK_TWO = ByteArray(32) { (it + 64).toByte() }
    }
}
