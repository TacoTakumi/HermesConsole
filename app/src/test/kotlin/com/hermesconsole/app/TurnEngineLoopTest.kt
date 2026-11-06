package com.hermesconsole.app

import com.hermesconsole.audio.AudioCapture
import com.hermesconsole.audio.AudioConfig
import com.hermesconsole.audio.AudioSink
import com.hermesconsole.audio.BargeInDetector
import com.hermesconsole.audio.ConvergenceGuard
import com.hermesconsole.audio.TtsPlayer
import com.hermesconsole.hermesclient.HermesEvent
import com.hermesconsole.lcars.LcarsCue
import com.hermesconsole.voiceclient.Barge
import com.hermesconsole.voiceclient.Transcript
import com.hermesconsole.voiceclient.TranscriptSegment
import com.hermesconsole.voiceclient.TtsEvent
import com.hermesconsole.voiceclient.TurnEnd
import com.hermesconsole.voiceclient.TurnEngineStatus
import com.hermesconsole.wakeword.WakeWord
import com.hermesconsole.wakeword.WakeWordSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * The voice loop in turn engine mode: a session streams the mic the whole time, VoiceManager's
 * turn engine says when a turn ended and when the user cut in, and the console reports where each
 * reply played. Outside a session the wake word still gates the mic.
 */
class TurnEngineLoopTest {

    // -- fakes -------------------------------------------------------------------------------

    class FakeLink : VoiceLink {
        val transcriptState = MutableStateFlow(Transcript())
        override val transcript: StateFlow<Transcript> get() = transcriptState

        private val ttsIn = Channel<TtsEvent>(Channel.UNLIMITED)
        override val tts: Flow<TtsEvent> = ttsIn.consumeAsFlow()

        val status = MutableStateFlow<TurnEngineStatus>(TurnEngineStatus.Ready)
        override val turnEngineStatus: StateFlow<TurnEngineStatus> get() = status

        private val turnEndsIn = Channel<TurnEnd>(Channel.UNLIMITED)
        override val turnEnds: Flow<TurnEnd> = turnEndsIn.consumeAsFlow()

        private val bargesIn = Channel<Barge>(Channel.UNLIMITED)
        override val barges: Flow<Barge> = bargesIn.consumeAsFlow()

        val captured = mutableListOf<ByteArray>()
        override var micSamplesSent: Long = 0

        val ttsOut = mutableListOf<Triple<String, String, Boolean>>()
        val ttsCancelled = mutableListOf<String>()
        val playback = mutableListOf<String>()

        private var openTurn = 0
        val turnsOpened = mutableListOf<Int>()
        val currentTurn: Int get() = openTurn

        override suspend fun turnStart(): Int {
            openTurn++
            turnsOpened += openTurn
            return openTurn
        }

        override fun sendCapture(frame: ByteArray) {
            captured += frame
            if (status.value != TurnEngineStatus.Pending) micSamplesSent += frame.size / 2
        }

        override fun sendTtsText(requestId: String, text: String, done: Boolean) {
            ttsOut += Triple(requestId, text, done)
        }

        override fun cancelTts(requestId: String) {
            ttsCancelled += requestId
        }

        override fun sendPlaybackStart(requestId: String, micSample: Long) {
            playback += "start $requestId $micSample"
        }

        override fun sendPlaybackStop(requestId: String, micSample: Long, replySample: Long) {
            playback += "stop $requestId $micSample $replySample"
        }

        fun emit(event: TtsEvent) {
            ttsIn.trySend(event)
        }

        fun turnEnd(text: String = "", turnId: Int = openTurn, micSample: Long = 0) {
            turnEndsIn.trySend(TurnEnd(turnId = turnId, text = text, micSample = micSample, by = "model"))
        }

        fun barge(requestId: String, micSample: Long = 0) {
            bargesIn.trySend(Barge(requestId = requestId, onsetMicSample = micSample, micSample = micSample))
        }

        /** A final for the open turn (or [turnId]), as the server stamps it. */
        fun hear(index: Int, text: String, isFinal: Boolean = true, turnId: Int = openTurn) {
            transcriptState.value = transcriptState.value.let { current ->
                Transcript(
                    (current.segments.filterNot { it.index == index } +
                        TranscriptSegment(index, text, isFinal, isFinal, turnId)).sortedBy { it.index },
                )
            }
        }
    }

    class FakeAgent : Agent {
        private val events = mutableMapOf<String, Channel<HermesEvent>>()
        val dispatched = mutableListOf<String>()
        val stopped = mutableListOf<String>()
        private var runs = 0

        override suspend fun dispatch(input: String, sessionId: String): String {
            dispatched += input
            return "run-${++runs}"
        }

        override suspend fun stop(runId: String) {
            stopped += runId
        }

        override fun events(runId: String): Flow<HermesEvent> = flow {
            for (event in channel(runId)) {
                emit(event)
                if (event.isTerminal) break
            }
        }

        fun emit(event: HermesEvent) {
            channel(event.runId).trySend(event)
        }

        private fun channel(runId: String) = events.getOrPut(runId) { Channel(Channel.UNLIMITED) }
    }

    class FakeWake : WakeWordSource {
        private val heard = Channel<WakeWord>(Channel.UNLIMITED)

        override fun detections(frames: Flow<ByteArray>): Flow<WakeWord> = flow {
            coroutineScope {
                launch { frames.collect { } }
                for (word in heard) emit(word)
            }
        }

        fun fire() {
            heard.trySend(WakeWord("hey computer", 1L))
        }

        override fun reset() = Unit
        override fun close() = Unit
    }

    class FakeCapture : AudioCapture {
        override val config = AudioConfig()
        private val pcm = Channel<ByteArray>(Channel.UNLIMITED)
        override fun frames(): Flow<ByteArray> = pcm.consumeAsFlow()
        override var framesRead: Long = 0

        /** A captured frame, tagged with [tag] in its first byte so the test can tell frames apart. */
        fun speak(tag: Int = 0) {
            framesRead += config.chunkBytes / 2
            pcm.trySend(ByteArray(config.chunkBytes).also { it[0] = tag.toByte() })
        }

        /** Record index at a time: 16 frames per ms from t = 0, so the test can predict mic_sample. */
        override fun recordIndexAt(timeNs: Long): Double = timeNs / 1_000_000.0 * 16

        override fun close() = Unit
    }

    /** Presents frames as the test says; frame f is presented at f / rate seconds. */
    class FakeSink(override val sampleRateHz: Int) : AudioSink {
        override var framesWritten = 0L
        var presented = 0L
        var stops = 0
        override val framesPresented: Long get() = presented
        override fun presentationTimeOf(framePosition: Long): Long = framePosition * 1_000_000_000L / sampleRateHz
        override fun write(pcm: ByteArray) { framesWritten += pcm.size / 2 }
        override fun stop() { stops++ }
        override fun close() = Unit
    }

    // -- harness -----------------------------------------------------------------------------

    private val voice = FakeLink()
    private val agent = FakeAgent()
    private val wake = FakeWake()
    private val capture = FakeCapture()
    private val sinks = mutableListOf<FakeSink>()
    private val player = TtsPlayer({ rate -> FakeSink(rate).also { sinks += it } })
    private val cued = mutableListOf<LcarsCue>()
    private val tick = AtomicLong(0)
    private val requestIds = AtomicLong(0)

    private fun TestScope.loop(
        turnEngineMode: Boolean = true,
        followUpWindowMillis: () -> Long = { 10_000 },
        bargeInEnabled: () -> Boolean = { true },
        bargeIn: BargeInDetector = BargeInDetector(guard = ConvergenceGuard(windowMs = 0)),
    ) = VoiceLoop(
        capture = capture,
        wake = wake,
        voice = voice,
        agent = agent,
        player = player,
        sessionId = { "session-test" },
        followUpWindowMillis = followUpWindowMillis,
        followUpCueGraceMillis = 0,
        bargeIn = bargeIn,
        bargeInEnabled = bargeInEnabled,
        scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        now = { tick.incrementAndGet() },
        newRequestId = { "req-${requestIds.incrementAndGet()}" },
        cues = { cued += it },
        turnEngineMode = turnEngineMode,
    ).also { it.start() }

    private fun sentTags() = voice.captured.map { it[0].toInt() }

    // -- session open and close --------------------------------------------------------------

    @Test
    fun `outside a session nothing is sent, and the stream starts with the frame after the wake`() = runTest {
        val loop = loop()
        capture.speak(1)
        capture.speak(2)
        runCurrent()
        assertEquals(emptyList<Int>(), sentTags(), "READY sends no audio, the wake word's frames included")

        wake.fire()
        runCurrent()
        capture.speak(3)
        runCurrent()

        assertEquals(listOf(3), sentTags())
        assertEquals(ConsoleState.LISTENING, loop.state.value)
    }

    @Test
    fun `a session opens with turn_start and one listening cue`() = runTest {
        loop()
        wake.fire()
        runCurrent()

        assertEquals(listOf(1), voice.turnsOpened)
        assertEquals(listOf(LcarsCue.LISTENING_OPEN), cued)
    }

    @Test
    fun `a tap into a session starts the stream in turn engine mode`() = runTest {
        val loop = loop()
        loop.enterSession()
        runCurrent()
        capture.speak(7)
        runCurrent()

        assertEquals(ConsoleState.LISTENING, loop.state.value)
        assertEquals(listOf(7), sentTags())
    }

    @Test
    fun `a tap into a session leaves classic mode wake-gated`() = runTest {
        val loop = loop(turnEngineMode = false)
        loop.enterSession()
        runCurrent()
        capture.speak(7)
        runCurrent()

        assertEquals(ConsoleState.READY, loop.state.value)
        assertEquals(emptyList<Int>(), sentTags())
    }

    @Test
    fun `leaving the session stops the stream, and the next wake opens a new session on the link`() = runTest {
        val loop = loop()
        wake.fire()
        runCurrent()
        capture.speak(1)
        runCurrent()

        loop.abort()
        runCurrent()
        capture.speak(2)
        runCurrent()

        assertEquals(ConsoleState.READY, loop.state.value)
        assertEquals(listOf(1), sentTags(), "no frame after the session ended")
        assertEquals(listOf(LcarsCue.LISTENING_OPEN, LcarsCue.LISTENING_CLOSED), cued)

        wake.fire()
        runCurrent()
        capture.speak(3)
        runCurrent()
        assertEquals(listOf(1, 3), sentTags())
        assertEquals(listOf(1, 2), voice.turnsOpened)
        assertTrue(loop.state.value == ConsoleState.LISTENING)
    }

    // -- turns ---------------------------------------------------------------------------------

    /** One whole spoken reply for [runId]: a delta, the run's end, the reply's audio and its end. */
    private fun TestScope.reply(runId: String, requestId: String, text: String = "Aye.") {
        agent.emit(HermesEvent.MessageDelta(runId, text))
        agent.emit(HermesEvent.RunCompleted(runId))
        runCurrent()
        voice.emit(TtsEvent.Start(requestId, 24_000))
        voice.emit(TtsEvent.Audio(ByteArray(480), 24_000))
        voice.emit(TtsEvent.End(requestId))
        runCurrent()
    }

    @Test
    fun `a final alone dispatches nothing, the turn_end does`() = runTest {
        loop()
        wake.fire()
        runCurrent()

        voice.hear(0, "status report")
        runCurrent()
        assertEquals(emptyList<String>(), agent.dispatched, "an endpointed final is not the end of the turn")

        voice.hear(1, "on the warp core")
        voice.turnEnd("status report on the warp core")
        runCurrent()
        assertEquals(listOf("status report on the warp core"), agent.dispatched)
    }

    @Test
    fun `a turn_end that holds only a final from before the session dispatches nothing`() = runTest {
        loop()
        voice.hear(0, "left over", turnId = 0)
        wake.fire()
        runCurrent()

        voice.turnEnd("left over", turnId = 0)
        runCurrent()
        assertEquals(emptyList<String>(), agent.dispatched)

        voice.hear(1, "now this")
        voice.turnEnd("left over now this")
        runCurrent()
        assertEquals(listOf("now this"), agent.dispatched, "the prompt is the session's own words")
    }

    @Test
    fun `after a reply the session keeps listening with the stream on and opens the next turn`() = runTest {
        val loop = loop(followUpWindowMillis = { 1_000 })
        wake.fire()
        runCurrent()
        voice.hear(0, "red alert")
        voice.turnEnd("red alert")
        runCurrent()
        reply("run-1", "req-1")

        assertEquals(ConsoleState.LISTENING, loop.state.value)
        assertEquals(listOf(1, 2), voice.turnsOpened, "a new server turn after the reply")
        assertEquals(listOf(LcarsCue.LISTENING_OPEN), cued, "no close cue between turns")

        // Well past the follow-up window: nothing closes the floor.
        testScheduler.advanceTimeBy(60_000)
        runCurrent()
        capture.speak(9)
        runCurrent()
        assertEquals(ConsoleState.LISTENING, loop.state.value)
        assertEquals(9, sentTags().last())

        voice.hear(1, "belay that")
        voice.turnEnd("belay that")
        runCurrent()
        assertEquals(listOf("red alert", "belay that"), agent.dispatched)
    }

    @Test
    fun `a run that says nothing still hands the floor back in the session`() = runTest {
        val loop = loop()
        wake.fire()
        runCurrent()
        voice.hear(0, "lights")
        voice.turnEnd("lights")
        runCurrent()
        agent.emit(HermesEvent.RunCompleted("run-1"))
        runCurrent()

        assertEquals(ConsoleState.LISTENING, loop.state.value)
        assertEquals(listOf(1, 2), voice.turnsOpened)
        assertEquals(listOf(LcarsCue.LISTENING_OPEN), cued)
    }
}
