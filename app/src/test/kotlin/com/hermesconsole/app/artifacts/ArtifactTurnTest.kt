package com.hermesconsole.app.artifacts

import com.hermesconsole.app.Agent
import com.hermesconsole.app.ConsoleState
import com.hermesconsole.app.VoiceLink
import com.hermesconsole.app.VoiceLoop
import com.hermesconsole.audio.AudioCapture
import com.hermesconsole.audio.AudioConfig
import com.hermesconsole.audio.AudioSink
import com.hermesconsole.audio.TtsPlayer
import com.hermesconsole.hermesclient.HermesEvent
import com.hermesconsole.voiceclient.Transcript
import com.hermesconsole.voiceclient.TtsEvent
import com.hermesconsole.wakeword.WakeWord
import com.hermesconsole.wakeword.WakeWordSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * T-24 / REQ-15's hardest clause: routing an artifact never interrupts the spoken reply.
 *
 * The router taps the very collector that forwards `message.delta` tokens to `tts_stream`
 * ([VoiceLoop]'s event tap), so the thing to prove is negative space: with artifacts - routable,
 * unroutable, even a tap that throws - riding the same stream as the deltas, every token still
 * goes out to be spoken, in order, and the turn still ends the way every turn ends. The fakes are
 * [VoiceLoopTest][com.hermesconsole.app]'s, trimmed to the typed turn this test drives.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArtifactTurnTest {

    // -- fakes (the typed-turn slice of VoiceLoopTest's) --------------------------------------

    private data class TtsFrame(val requestId: String, val text: String, val done: Boolean)

    private class FakeVoiceLink : VoiceLink {
        override val transcript: StateFlow<Transcript> = MutableStateFlow(Transcript())

        private val ttsIn = Channel<TtsEvent>(Channel.UNLIMITED)
        override val tts: Flow<TtsEvent> = ttsIn.consumeAsFlow()

        val ttsOut = mutableListOf<TtsFrame>()

        /** The server owns the turn counter (D-18): each turn_start advances it and acks the id. */
        private var openTurn = 0

        override suspend fun turnStart(): Int = ++openTurn

        override fun sendCapture(frame: ByteArray) = Unit

        override fun sendTtsText(requestId: String, text: String, done: Boolean) {
            ttsOut += TtsFrame(requestId, text, done)
        }

        override fun cancelTts(requestId: String) = Unit

        /** VoiceManager sends the console some speech. */
        fun emit(event: TtsEvent) {
            ttsIn.trySend(event)
        }
    }

    private class FakeAgent : Agent {
        private val events = Channel<HermesEvent>(Channel.UNLIMITED)

        override suspend fun dispatch(input: String, sessionId: String): String = "run-1"

        override suspend fun stop(runId: String) = Unit

        // Ends on a terminal event, exactly as HermesClient's stream does.
        override fun events(runId: String): Flow<HermesEvent> = flow {
            for (event in events) {
                emit(event)
                if (event.isTerminal) break
            }
        }

        fun emit(event: HermesEvent) {
            events.trySend(event)
        }
    }

    private class FakeWake : WakeWordSource {
        override fun detections(frames: Flow<ByteArray>): Flow<WakeWord> = flow {
            frames.collect { } // consume, never wake: these turns are typed
        }

        override fun reset() = Unit
        override fun close() = Unit
    }

    private class FakeCapture : AudioCapture {
        override val config = AudioConfig()
        override fun frames(): Flow<ByteArray> = emptyFlow()
        override fun close() = Unit
    }

    private class FakeSink(override val sampleRateHz: Int) : AudioSink {
        override fun write(pcm: ByteArray) = Unit
        override fun stop() = Unit
        override fun close() = Unit
    }

    // -- harness -----------------------------------------------------------------------------

    private val voice = FakeVoiceLink()
    private val agent = FakeAgent()

    private fun loop(scope: CoroutineScope, eventTap: (HermesEvent) -> Unit) = VoiceLoop(
        capture = FakeCapture(),
        wake = FakeWake(),
        voice = voice,
        agent = agent,
        player = TtsPlayer({ rate -> FakeSink(rate) }),
        sessionId = { "session-artifact" },
        scope = scope,
        newRequestId = { "req-1" },
        eventTap = eventTap,
    ).also { it.start() }

    private fun artifact(kind: String, vararg fields: Pair<String, String>) = HermesEvent.ToolStarted(
        runId = "run-1",
        tool = "render_artifact",
        args = buildJsonObject {
            put("kind", kind)
            fields.forEach { (key, value) -> put(key, value) }
        },
    )

    // -- the requirement ----------------------------------------------------------------------

    @Test
    fun `artifacts route to panes without costing the reply a single spoken token`() = runTest {
        val router = ArtifactRouter(AppletRegistry.standard())
        val loop = loop(CoroutineScope(UnconfinedTestDispatcher(testScheduler)), router::onEvent)

        loop.say("show me the warp core")
        advanceUntilIdle()
        assertEquals(ConsoleState.THINKING, loop.state.value)

        // The reply, with artifacts riding the same stream: one routable, one that nobody
        // registered. The second must cost exactly nothing (REQ-15).
        agent.emit(HermesEvent.MessageDelta("run-1", "Here is "))
        agent.emit(artifact("image", "url" to "http://x/core.png"))
        agent.emit(HermesEvent.MessageDelta("run-1", "the warp core. "))
        agent.emit(artifact("hologram"))
        agent.emit(HermesEvent.MessageDelta("run-1", "Steady as she goes."))
        agent.emit(HermesEvent.RunCompleted("run-1", output = ""))
        advanceUntilIdle()

        // THE ASSERTION: the spoken reply is whole, in order, and flushed - as if no artifact
        // had ever been on the stream.
        assertEquals(
            listOf(
                TtsFrame("req-1", "Here is ", done = false),
                TtsFrame("req-1", "the warp core. ", done = false),
                TtsFrame("req-1", "Steady as she goes.", done = false),
                TtsFrame("req-1", "", done = true),
            ),
            voice.ttsOut,
            "routing artifacts must not disturb a token of the reply",
        )

        // And the routable artifact became a pane while the reply was still being spoken -
        // the registered one only: no blank pane for the hologram.
        val pane = router.panes.value.single()
        assertSame(ImageApplet, pane.applet)
        assertEquals("run-1", pane.artifact.runId)

        // The turn ends the way every turn ends: on its audio_end.
        voice.emit(TtsEvent.Start("req-1", 22_050))
        voice.emit(TtsEvent.End("req-1"))
        advanceUntilIdle()
        assertEquals(ConsoleState.READY, loop.state.value)
    }

    @Test
    fun `a tap that throws is logged and dropped - the reply is spoken to the end`() = runTest {
        // The router never throws; this pins the loop's guard for whatever else the shell taps in.
        val loop = loop(CoroutineScope(UnconfinedTestDispatcher(testScheduler))) {
            throw IllegalStateException("pane exploded")
        }

        loop.say("status report")
        advanceUntilIdle()

        agent.emit(HermesEvent.MessageDelta("run-1", "All "))
        agent.emit(HermesEvent.MessageDelta("run-1", "nominal."))
        agent.emit(HermesEvent.RunCompleted("run-1", output = ""))
        advanceUntilIdle()

        assertEquals(
            listOf(
                TtsFrame("req-1", "All ", done = false),
                TtsFrame("req-1", "nominal.", done = false),
                TtsFrame("req-1", "", done = true),
            ),
            voice.ttsOut,
            "a throwing tap must never reach the speech path",
        )

        voice.emit(TtsEvent.Start("req-1", 22_050))
        voice.emit(TtsEvent.End("req-1"))
        advanceUntilIdle()
        assertEquals(ConsoleState.READY, loop.state.value)
    }
}
