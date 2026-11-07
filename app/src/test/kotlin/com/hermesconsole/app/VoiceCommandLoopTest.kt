package com.hermesconsole.app

import com.hermesconsole.app.TurnEngineLoopTest.FakeAgent
import com.hermesconsole.app.TurnEngineLoopTest.FakeCapture
import com.hermesconsole.app.TurnEngineLoopTest.FakeLink
import com.hermesconsole.app.TurnEngineLoopTest.FakeSink
import com.hermesconsole.app.TurnEngineLoopTest.FakeWake
import com.hermesconsole.audio.BargeInDetector
import com.hermesconsole.audio.ConvergenceGuard
import com.hermesconsole.audio.TtsPlayer
import com.hermesconsole.hermesclient.HermesEvent
import com.hermesconsole.lcars.LcarsCue
import com.hermesconsole.voiceclient.Transcript
import com.hermesconsole.voiceclient.TranscriptSegment
import com.hermesconsole.voiceclient.TtsEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * "Stop listening" is a command, not a turn: it ends whatever is in flight, closes the mic with
 * one close cue and leaves the console at READY behind the wake word, with nothing dispatched.
 * "End session" does the same and then hands the visit's end to the shell, once.
 */
class VoiceCommandLoopTest {

    // -- fakes -------------------------------------------------------------------------------

    /** The stops of a turn, in the order they reach the wire. */
    private val stops = mutableListOf<String>()

    /** The shared link, with the stops it sends written to [stops] as well. */
    private class OrderedLink(val inner: FakeLink, val stops: MutableList<String>) : VoiceLink by inner {
        override fun cancelTts(requestId: String) {
            stops += "tts_cancel"
            inner.cancelTts(requestId)
        }

        override fun sendPlaybackStop(requestId: String, micSample: Long, replySample: Long) {
            stops += "playback_stop"
            inner.sendPlaybackStop(requestId, micSample, replySample)
        }

        override fun sendListenStop() {
            stops += "listen_stop"
            inner.sendListenStop()
        }
    }

    /** The shared agent, with each dispatch's session id kept and each stop written to [stops]. */
    private class OrderedAgent(val inner: FakeAgent, val stops: MutableList<String>) : Agent by inner {
        val sessions = mutableListOf<String>()

        override suspend fun dispatch(input: String, sessionId: String): String {
            sessions += sessionId
            return inner.dispatch(input, sessionId)
        }

        override suspend fun stop(runId: String) {
            stops += "agent_stop"
            inner.stop(runId)
        }
    }

    // -- harness -----------------------------------------------------------------------------

    private val voice = FakeLink()
    private val agent = FakeAgent()
    private val orderedAgent = OrderedAgent(agent, stops)
    private val wake = FakeWake()
    private val capture = FakeCapture()
    private val sinks = mutableListOf<FakeSink>()
    private val player = TtsPlayer({ rate -> FakeSink(rate).also { sinks += it } })
    private val cued = mutableListOf<LcarsCue>()
    private val tick = AtomicLong(0)
    private val requestIds = AtomicLong(0)
    private var visitsEnded = 0

    private fun TestScope.loop(turnEngineMode: Boolean = true) = VoiceLoop(
        capture = capture,
        wake = wake,
        voice = OrderedLink(voice, stops),
        agent = orderedAgent,
        player = player,
        sessionId = { "session-test" },
        followUpWindowMillis = { 10_000 },
        followUpCueGraceMillis = 0,
        bargeIn = BargeInDetector(guard = ConvergenceGuard(windowMs = 0)),
        scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        now = { tick.incrementAndGet() },
        newRequestId = { "req-${requestIds.incrementAndGet()}" },
        cues = { cued += it },
        turnEngineMode = turnEngineMode,
        onEndSession = { visitsEnded++ },
    ).also { it.start() }

    private fun TestScope.speak(tag: Int = 0) {
        capture.speak(tag)
        runCurrent()
    }

    /** One whole spoken reply for [runId]: a delta, the run's end, the reply's audio and its end. */
    private fun TestScope.reply(runId: String, requestId: String) {
        agent.emit(HermesEvent.MessageDelta(runId, "Aye."))
        agent.emit(HermesEvent.RunCompleted(runId))
        runCurrent()
        voice.emit(TtsEvent.Start(requestId, 24_000))
        voice.emit(TtsEvent.Audio(ByteArray(480), 24_000))
        voice.emit(TtsEvent.End(requestId))
        runCurrent()
    }

    /** A provisional endpoint for the open turn: VoiceManager may still revise [text]. */
    private fun hearProvisional(index: Int, text: String) {
        voice.transcriptState.value = voice.transcriptState.value.let { current ->
            Transcript(
                (current.segments.filterNot { it.index == index } +
                    TranscriptSegment(index, text, isFinal = false, endpointed = true, turnId = voice.currentTurn))
                    .sortedBy { it.index },
            )
        }
    }

    /** The frames sent after the last listen_stop. */
    private fun framesAfterStop(): List<String> =
        voice.frames.drop(voice.frames.lastIndexOf("listen_stop") + 1)

    private fun closeCues() = cued.count { it == LcarsCue.LISTENING_CLOSED }

    // -- turn engine mode --------------------------------------------------------------------

    @Test
    fun `stop listening in an idle session dispatches nothing and closes the mic`() = runTest {
        val loop = loop()
        wake.fire()
        runCurrent()
        speak(1)

        voice.hear(0, "stop listening")
        voice.turnEnd("stop listening")
        runCurrent()
        speak(2)

        assertEquals(emptyList<String>(), agent.dispatched)
        assertEquals(ConsoleState.READY, loop.state.value)
        assertEquals(1, closeCues())
        assertEquals(listOf("listen_start", "audio", "listen_stop"), voice.frames)
    }

    @Test
    fun `stop listening while Hermes works stops the run and reaches READY`() = runTest {
        val loop = loop()
        wake.fire()
        runCurrent()
        voice.hear(0, "scan the sector")
        voice.turnEnd("scan the sector")
        runCurrent()
        assertEquals(ConsoleState.THINKING, loop.state.value)

        voice.hear(1, "Stop listening.")
        voice.turnEnd("Stop listening.")
        runCurrent()

        assertEquals(listOf("scan the sector"), agent.dispatched)
        assertEquals(listOf("run-1"), agent.stopped)
        assertEquals(ConsoleState.READY, loop.state.value)
        assertEquals(1, closeCues())
    }

    @Test
    fun `other words while Hermes works are still dropped`() = runTest {
        val loop = loop()
        wake.fire()
        runCurrent()
        voice.hear(0, "scan the sector")
        voice.turnEnd("scan the sector")
        runCurrent()

        voice.hear(1, "what time is it")
        voice.turnEnd("what time is it")
        runCurrent()

        assertEquals(listOf("scan the sector"), agent.dispatched)
        assertEquals(emptyList<String>(), agent.stopped)
        assertEquals(ConsoleState.THINKING, loop.state.value)
        assertEquals(0, closeCues())
    }

    @Test
    fun `stop listening over a reply stops playback, synthesis, the run and the mic in that order`() = runTest {
        val loop = loop()
        wake.fire()
        runCurrent()
        voice.hear(0, "long answer please")
        voice.turnEnd("long answer please")
        runCurrent()
        agent.emit(HermesEvent.MessageDelta("run-1", "Once upon a time "))
        runCurrent()
        voice.emit(TtsEvent.Start("req-1", 24_000))
        voice.emit(TtsEvent.Audio(ByteArray(4_800), 24_000))
        runCurrent()
        sinks.last().presented = 600
        speak()
        assertEquals(ConsoleState.SPEAKING, loop.state.value)
        stops.clear()

        voice.hear(1, "computer stop listening")
        voice.turnEnd("computer stop listening")
        runCurrent()
        speak()

        // Hermes' stop is an HTTP call launched between tts_cancel and listen_stop: it is issued,
        // but it lands whenever its round trip does, so only the wire frames have a fixed order.
        assertEquals(listOf("playback_stop", "tts_cancel", "listen_stop"), stops - "agent_stop")
        assertEquals(1, stops.count { it == "agent_stop" })
        assertEquals(listOf("req-1"), voice.ttsCancelled)
        assertEquals(listOf("run-1"), agent.stopped)
        assertEquals(1, closeCues())
        assertEquals(ConsoleState.READY, loop.state.value)
        assertEquals(emptyList<String>(), framesAfterStop(), "no audio after the command")
        assertEquals(listOf("long answer please"), agent.dispatched)
    }

    @Test
    fun `after stop listening the wake word opens a new session stream`() = runTest {
        val loop = loop()
        wake.fire()
        runCurrent()
        voice.hear(0, "stop listening")
        voice.turnEnd("stop listening")
        runCurrent()
        assertEquals(ConsoleState.READY, loop.state.value)
        val sent = voice.frames.size

        wake.fire()
        runCurrent()
        speak(3)

        assertEquals(ConsoleState.LISTENING, loop.state.value)
        assertEquals(listOf(1, 2), voice.turnsOpened, "a new turn_start")
        assertEquals(listOf("listen_start", "audio"), voice.frames.drop(sent))

        voice.hear(1, "status report")
        voice.turnEnd("status report")
        runCurrent()
        assertEquals(listOf("status report"), agent.dispatched)
    }

    // -- classic mode ------------------------------------------------------------------------

    @Test
    fun `stop listening in the wake window dispatches nothing and closes the mic once`() = runTest {
        val loop = loop(turnEngineMode = false)
        wake.fire()
        runCurrent()
        speak(1)

        voice.hear(0, "stop listening please")
        runCurrent()
        speak(2)
        // The wake window's own bound passes with nothing left to close.
        advanceTimeBy(20_000)
        runCurrent()

        assertEquals(emptyList<String>(), agent.dispatched)
        assertEquals(ConsoleState.READY, loop.state.value)
        assertEquals(1, closeCues())
        assertEquals(listOf("listen_start", "audio", "listen_stop"), voice.frames)
    }

    @Test
    fun `stop listening in a follow-up window closes it, and the next wake keeps the session`() = runTest {
        val loop = loop(turnEngineMode = false)
        wake.fire()
        runCurrent()
        voice.hear(0, "what time is it")
        runCurrent()
        reply("run-1", "req-1")
        assertEquals(ConsoleState.LISTENING, loop.state.value, "the follow-up window is open")

        voice.hear(1, "stop listening")
        runCurrent()
        speak(5)

        assertEquals(listOf("what time is it"), agent.dispatched)
        assertEquals(ConsoleState.READY, loop.state.value)
        assertEquals(1, closeCues())
        assertEquals("listen_stop", voice.frames.last(), "no audio after the command")
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(1, closeCues(), "the window does not close a second time")

        wake.fire()
        runCurrent()
        assertEquals(ConsoleState.LISTENING, loop.state.value)
        speak(6)
        assertEquals(listOf("listen_start", "audio"), framesAfterStop())

        voice.hear(2, "status report")
        runCurrent()
        assertEquals(listOf("what time is it", "status report"), agent.dispatched)
        assertEquals(listOf("session-test", "session-test"), orderedAgent.sessions)
    }

    @Test
    fun `end session in the wake window dispatches nothing, closes the mic and ends the visit once`() = runTest {
        val loop = loop(turnEngineMode = false)
        wake.fire()
        runCurrent()
        speak(1)

        voice.hear(0, "end session")
        runCurrent()
        speak(2)
        advanceTimeBy(20_000)
        runCurrent()

        assertEquals(emptyList<String>(), agent.dispatched)
        assertEquals(ConsoleState.READY, loop.state.value)
        assertEquals(1, closeCues())
        assertEquals(listOf("listen_start", "audio", "listen_stop"), voice.frames)
        assertEquals(1, visitsEnded)
    }

    @Test
    fun `goodbye in a follow-up window ends the visit once`() = runTest {
        val loop = loop(turnEngineMode = false)
        wake.fire()
        runCurrent()
        voice.hear(0, "what time is it")
        runCurrent()
        reply("run-1", "req-1")
        assertEquals(ConsoleState.LISTENING, loop.state.value, "the follow-up window is open")

        voice.hear(1, "Goodbye.")
        runCurrent()
        speak(5)
        advanceTimeBy(20_000)
        runCurrent()

        assertEquals(listOf("what time is it"), agent.dispatched)
        assertEquals(ConsoleState.READY, loop.state.value)
        assertEquals(1, closeCues())
        assertEquals(emptyList<String>(), framesAfterStop(), "no audio after the command")
        assertEquals(1, visitsEnded)
    }

    @Test
    fun `stop listening does not end the visit`() = runTest {
        loop(turnEngineMode = false)
        wake.fire()
        runCurrent()
        voice.hear(0, "stop listening")
        runCurrent()

        assertEquals(0, visitsEnded)
    }

    @Test
    fun `a provisional stop revised to stop listening stops the run and closes the mic`() = runTest {
        val loop = loop(turnEngineMode = false)
        wake.fire()
        runCurrent()
        speak(1)

        hearProvisional(0, "stop")
        runCurrent()
        assertEquals(listOf("stop"), agent.dispatched)

        voice.hear(0, "stop listening")
        runCurrent()
        speak(2)

        assertEquals(listOf("stop"), agent.dispatched, "the command is never dispatched")
        assertEquals(listOf("run-1"), agent.stopped)
        assertEquals(1, closeCues())
        assertEquals("listen_stop", voice.frames.last(), "no audio after the command")
        assertEquals(ConsoleState.READY, loop.state.value)
        assertEquals(0, visitsEnded)
    }

    @Test
    fun `a provisional end revised to end session stops the run and ends the visit once`() = runTest {
        val loop = loop(turnEngineMode = false)
        wake.fire()
        runCurrent()
        speak(1)

        hearProvisional(0, "end")
        runCurrent()
        assertEquals(listOf("end"), agent.dispatched)

        voice.hear(0, "end session")
        runCurrent()
        speak(2)

        assertEquals(listOf("end"), agent.dispatched, "the command is never dispatched")
        assertEquals(listOf("run-1"), agent.stopped)
        assertEquals(1, closeCues())
        assertEquals("listen_stop", voice.frames.last(), "no audio after the command")
        assertEquals(ConsoleState.READY, loop.state.value)
        assertEquals(1, visitsEnded)
    }

    @Test
    fun `a provisional question revised to a longer one is still redispatched`() = runTest {
        val loop = loop(turnEngineMode = false)
        wake.fire()
        runCurrent()
        speak(1)

        hearProvisional(0, "what is")
        runCurrent()
        voice.hear(0, "what is the time")
        runCurrent()

        assertEquals(listOf("what is", "what is the time"), agent.dispatched)
        assertEquals(listOf("run-1"), agent.stopped)
        assertEquals(0, closeCues())
        assertEquals(ConsoleState.THINKING, loop.state.value)
        assertEquals("what is the time", loop.turn.value.prompt)
    }

    // -- end session, turn engine mode -------------------------------------------------------

    @Test
    fun `end session in an idle session dispatches nothing, closes the mic and ends the visit once`() = runTest {
        val loop = loop()
        wake.fire()
        runCurrent()
        speak(1)

        voice.hear(0, "end session")
        voice.turnEnd("end session")
        runCurrent()
        speak(2)

        assertEquals(emptyList<String>(), agent.dispatched)
        assertEquals(ConsoleState.READY, loop.state.value)
        assertEquals(1, closeCues())
        assertEquals(listOf("listen_start", "audio", "listen_stop"), voice.frames)
        assertEquals(1, visitsEnded)
    }

    @Test
    fun `end the session while Hermes works stops the run and ends the visit once`() = runTest {
        val loop = loop()
        wake.fire()
        runCurrent()
        voice.hear(0, "scan the sector")
        voice.turnEnd("scan the sector")
        runCurrent()
        assertEquals(ConsoleState.THINKING, loop.state.value)

        voice.hear(1, "Computer, end the session.")
        voice.turnEnd("Computer, end the session.")
        runCurrent()
        speak(3)

        assertEquals(listOf("scan the sector"), agent.dispatched)
        assertEquals(listOf("run-1"), agent.stopped)
        assertEquals(ConsoleState.READY, loop.state.value)
        assertEquals(1, closeCues())
        assertEquals(emptyList<String>(), framesAfterStop(), "no audio after the command")
        assertEquals(1, visitsEnded)
    }
}
