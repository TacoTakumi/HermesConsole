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
import com.hermesconsole.voiceclient.TtsEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * Every stop and start of the mic stream is told to VoiceManager: listen_stop after a stream's
 * last audio, listen_start before the next stream's first, and the listen_stopped ack checked
 * against the samples sent when the stop went out.
 */
class ListenBoundaryLoopTest {

    private val voice = FakeLink()
    private val agent = FakeAgent()
    private val wake = FakeWake()
    private val capture = FakeCapture()
    private val player = TtsPlayer({ rate -> FakeSink(rate) })
    private val cued = mutableListOf<LcarsCue>()
    private val warnings = mutableListOf<String>()
    private val tick = AtomicLong(0)
    private val requestIds = AtomicLong(0)

    private fun TestScope.loop(turnEngineMode: Boolean = true, followUpWindowMillis: Long = 1_000) = VoiceLoop(
        capture = capture,
        wake = wake,
        voice = voice,
        agent = agent,
        player = player,
        sessionId = { "session-test" },
        followUpWindowMillis = { followUpWindowMillis },
        followUpCueGraceMillis = 0,
        bargeIn = BargeInDetector(guard = ConvergenceGuard(windowMs = 0)),
        scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        now = { tick.incrementAndGet() },
        newRequestId = { "req-${requestIds.incrementAndGet()}" },
        cues = { cued += it },
        turnEngineMode = turnEngineMode,
        warn = { warnings += it },
    ).also { it.start() }

    /** The frames sent, with each run of audio folded into one "audio". */
    private fun streams(): List<String> =
        voice.frames.fold(mutableListOf()) { out, frame ->
            if (frame != "audio" || out.lastOrNull() != "audio") out += frame
            out
        }

    private fun TestScope.speak(tag: Int) {
        capture.speak(tag)
        runCurrent()
    }

    private fun TestScope.reply(runId: String, requestId: String) {
        agent.emit(HermesEvent.MessageDelta(runId, "Aye."))
        agent.emit(HermesEvent.RunCompleted(runId))
        runCurrent()
        voice.emit(TtsEvent.Start(requestId, 24_000))
        voice.emit(TtsEvent.Audio(ByteArray(480), 24_000))
        voice.emit(TtsEvent.End(requestId))
        runCurrent()
    }

    // -- the frames --------------------------------------------------------------------------

    @Test
    fun `classic mode fences each wake's stream, across the reply and the follow-up window`() = runTest {
        val loop = loop(turnEngineMode = false)
        speak(1)

        wake.fire()
        runCurrent()
        speak(2)
        voice.hear(0, "what time is it")
        runCurrent()
        speak(3)
        reply("run-1", "req-1")
        assertEquals(ConsoleState.LISTENING, loop.state.value, "the follow-up window is open")
        speak(4)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(ConsoleState.READY, loop.state.value, "silence closed the window")
        speak(5)
        assertEquals(listOf("listen_start", "audio", "listen_stop"), streams())

        // A second wake that nobody answers: its window closes at the wake bound.
        wake.fire()
        runCurrent()
        speak(6)
        advanceTimeBy(16_000)
        runCurrent()
        assertEquals(ConsoleState.READY, loop.state.value)
        speak(7)

        assertEquals(
            listOf("listen_start", "audio", "listen_stop", "listen_start", "audio", "listen_stop"),
            streams(),
        )
        assertEquals(listOf(2, 3, 4, 6), voice.captured.map { it[0].toInt() })
    }

    @Test
    fun `a session stopped and reopened by the wake word is fenced on both sides`() = runTest {
        val loop = loop()
        wake.fire()
        runCurrent()
        speak(1)

        loop.abort()
        runCurrent()
        speak(2)
        assertEquals(listOf("listen_start", "audio", "listen_stop"), streams())

        wake.fire()
        runCurrent()
        speak(3)

        assertEquals(ConsoleState.LISTENING, loop.state.value)
        assertEquals(listOf("listen_start", "audio", "listen_stop", "listen_start", "audio"), streams())
        assertEquals(listOf(1, 3), voice.captured.map { it[0].toInt() })
    }

    @Test
    fun `leaving a session sends one listen_stop, and a second abort sends nothing`() = runTest {
        val loop = loop()
        loop.enterSession()
        runCurrent()
        speak(1)
        speak(2)

        loop.abort()
        runCurrent()
        loop.abort()
        runCurrent()
        speak(3)

        assertEquals(ConsoleState.READY, loop.state.value)
        assertEquals(listOf("listen_start", "audio", "listen_stop"), streams())
    }

    @Test
    fun `a typed turn never streams, so it sends no listen boundary`() = runTest {
        val loop = loop(turnEngineMode = false)
        loop.say("status report")
        runCurrent()
        reply("run-1", "req-1")

        assertEquals(ConsoleState.READY, loop.state.value)
        assertEquals(emptyList<String>(), voice.frames)
    }

    // -- the ack check -----------------------------------------------------------------------

    @Test
    fun `an ack that matches the samples sent at the stop logs nothing, even after new audio`() = runTest {
        val loop = loop()
        wake.fire()
        runCurrent()
        speak(1)
        speak(2)
        loop.abort()
        runCurrent()
        val atStop = voice.micSamplesSent

        // The console does not wait: the next stream is already on the wire when the ack lands.
        wake.fire()
        runCurrent()
        speak(3)
        assertTrue(voice.micSamplesSent > atStop)

        voice.ackListenStop(atStop)
        runCurrent()
        assertEquals(emptyList<String>(), warnings)
    }

    @Test
    fun `an ack that differs logs a warning with both counts and changes nothing else`() = runTest {
        val loop = loop()
        wake.fire()
        runCurrent()
        speak(1)
        loop.abort()
        runCurrent()
        val atStop = voice.micSamplesSent

        val state = loop.state.value
        val frames = voice.frames.toList()
        val cues = cued.toList()
        val turns = voice.turnsOpened.toList()

        voice.ackListenStop(atStop + 7)
        runCurrent()

        assertEquals(1, warnings.size)
        assertTrue(warnings[0].contains("$atStop"), warnings[0])
        assertTrue(warnings[0].contains("${atStop + 7}"), warnings[0])
        assertEquals(state, loop.state.value)
        assertEquals(frames, voice.frames, "no frame goes out on a mismatch")
        assertEquals(cues, cued)
        assertEquals(turns, voice.turnsOpened)
    }

    @Test
    fun `a new connection forgets the stops still waiting for an ack`() = runTest {
        val loop = loop()
        wake.fire()
        runCurrent()
        speak(1)
        speak(2)
        loop.abort()
        runCurrent()

        // The old connection's ack never comes: the link redials and its mic count starts over.
        voice.reconnect()
        voice.micSamplesSent = 0
        runCurrent()

        wake.fire()
        runCurrent()
        speak(3)
        loop.abort()
        runCurrent()

        voice.ackListenStop(voice.micSamplesSent)
        runCurrent()
        assertEquals(emptyList<String>(), warnings)
    }
}
