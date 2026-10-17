package com.hermesconsole.app.tools

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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T-04 / REQ-07: reading tool output never interrupts the spoken reply, and the desk is total.
 *
 * The tool-activity desk taps the very collector that forwards `message.delta` tokens to
 * `tts_stream` ([VoiceLoop]'s event tap), so the thing to prove is negative space: with
 * `tool.completed` events - a routable one, a render_artifact that is suppressed, and a malformed
 * result that must not throw - riding the same stream as the deltas, every token still goes out to be
 * spoken, in order, and the turn still ends the way every turn ends. The fakes are ArtifactTurnTest's,
 * the typed-turn slice of VoiceLoopTest's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ToolActivityTurnTest {

    // -- fakes (the typed-turn slice of VoiceLoopTest's) --------------------------------------

    private data class TtsFrame(val requestId: String, val text: String, val done: Boolean)

    private class FakeVoiceLink : VoiceLink {
        override val transcript: StateFlow<Transcript> = MutableStateFlow(Transcript())

        private val ttsIn = Channel<TtsEvent>(Channel.UNLIMITED)
        override val tts: Flow<TtsEvent> = ttsIn.consumeAsFlow()

        val ttsOut = mutableListOf<TtsFrame>()

        private var openTurn = 0

        override suspend fun turnStart(): Int = ++openTurn

        override fun sendCapture(frame: ByteArray) = Unit

        override fun sendTtsText(requestId: String, text: String, done: Boolean) {
            ttsOut += TtsFrame(requestId, text, done)
        }

        override fun cancelTts(requestId: String) = Unit

        fun emit(event: TtsEvent) {
            ttsIn.trySend(event)
        }
    }

    private class FakeAgent : Agent {
        private val events = Channel<HermesEvent>(Channel.UNLIMITED)

        override suspend fun dispatch(input: String, sessionId: String): String = "run-1"

        override suspend fun stop(runId: String) = Unit

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
        sessionId = { "session-tool" },
        scope = scope,
        newRequestId = { "req-1" },
        eventTap = eventTap,
    ).also { it.start() }

    private fun toolCompleted(tool: String, result: JsonElement?, isError: Boolean = false) =
        HermesEvent.ToolCompleted(runId = "run-1", tool = tool, isError = isError, result = result)

    // -- the requirement ----------------------------------------------------------------------

    @Test
    fun `tool completions route to strip lines without costing the reply a single spoken token`() = runTest {
        val router = ToolActivityRouter()
        val loop = loop(CoroutineScope(UnconfinedTestDispatcher(testScheduler)), router::onEvent)

        loop.say("run the build")
        advanceUntilIdle()
        assertEquals(ConsoleState.THINKING, loop.state.value)

        // The reply, with tool.completed events riding the same stream: one routable, one
        // render_artifact (already a pane, suppressed), one malformed result (must not throw).
        agent.emit(HermesEvent.MessageDelta("run-1", "Running "))
        agent.emit(toolCompleted("bash", JsonPrimitive("build passed")))
        agent.emit(HermesEvent.MessageDelta("run-1", "the build. "))
        agent.emit(toolCompleted("render_artifact", JsonPrimitive("shown as a pane")))
        agent.emit(toolCompleted("flaky_tool", JsonPrimitive("{ not: valid json,,,")))
        agent.emit(HermesEvent.MessageDelta("run-1", "All green."))
        agent.emit(HermesEvent.RunCompleted("run-1", output = ""))
        advanceUntilIdle()

        // THE ASSERTION: the spoken reply is whole, in order, and flushed - as if no tool.completed
        // had ever been on the stream.
        assertEquals(
            listOf(
                TtsFrame("req-1", "Running ", done = false),
                TtsFrame("req-1", "the build. ", done = false),
                TtsFrame("req-1", "All green.", done = false),
                TtsFrame("req-1", "", done = true),
            ),
            voice.ttsOut,
            "routing tool output must not disturb a token of the reply",
        )

        // And the lines built while the reply was still being spoken: the routable one and the
        // malformed one (verbatim, no throw); render_artifact suppressed, no blob on screen.
        val lines = router.lines.value
        assertEquals(listOf("bash", "flaky_tool"), lines.map { it.tool }, "render_artifact earns no line")
        assertEquals("build passed", lines.first().body)

        // The turn ends the way every turn ends: on its audio_end.
        voice.emit(TtsEvent.Start("req-1", 22_050))
        voice.emit(TtsEvent.End("req-1"))
        advanceUntilIdle()
        assertEquals(ConsoleState.READY, loop.state.value)
    }

    /**
     * REQ-07's explicit list, at the desk boundary: null, empty, malformed, and very-large results
     * each return normally from onEvent with no exception, and a line still renders.
     */
    @Test
    fun `the desk onEvent is total - null, empty, malformed, and oversized results never throw`() {
        val router = ToolActivityRouter()

        assertDoesNotThrow {
            router.onEvent(toolCompleted("a", result = null))
            router.onEvent(toolCompleted("b", result = buildJsonObject { }))
            router.onEvent(toolCompleted("c", result = JsonPrimitive("{ oops: not, json")))
            router.onEvent(toolCompleted("d", result = JsonPrimitive("x".repeat(ToolResultReader.BODY_CAP * 3))))
        }

        val lines = router.lines.value
        assertEquals(4, lines.size, "each result still renders a line")
        assertTrue(lines.first { it.tool == "d" }.truncated, "the oversized result is bounded and marked")
    }
}
