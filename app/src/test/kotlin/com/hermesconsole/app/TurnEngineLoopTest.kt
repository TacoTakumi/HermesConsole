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
import org.junit.jupiter.api.Assertions.assertFalse
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

        val dialCount = MutableStateFlow(1L)
        override val dials: StateFlow<Long> get() = dialCount

        /**
         * The link redials so fast the status never shows Pending: the server's turns and segment
         * indexes start over, and the transcript with them.
         */
        fun reconnect() {
            openTurn = 0
            transcriptState.value = Transcript()
            dialCount.value++
        }

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

        /** Every audio frame and listen boundary sent, in wire order. */
        val frames = mutableListOf<String>()

        private val listenAcks = Channel<Long>(Channel.UNLIMITED)
        override val listenStopped: Flow<Long> = listenAcks.consumeAsFlow()

        override fun sendCapture(frame: ByteArray) {
            captured += frame
            frames += "audio"
            if (status.value != TurnEngineStatus.Pending) micSamplesSent += frame.size / 2
        }

        override fun sendListenStop() {
            frames += "listen_stop"
        }

        override fun sendListenStart() {
            frames += "listen_start"
        }

        /** The server's listen_stopped ack, carrying the mic samples it accepted. */
        fun ackListenStop(micSample: Long) {
            listenAcks.trySend(micSample)
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

        /** A frame of somebody talking loudly: what the local barge-in detector trips on. */
        fun shout() {
            framesRead += config.chunkBytes / 2
            pcm.trySend(ByteArray(config.chunkBytes) { i -> if (i % 2 == 0) 0x40.toByte() else 0x1F })
        }

        /** Record index at a time: 16 frames per ms from t = 0, so the test can predict mic_sample. */
        override fun recordIndexAt(timeNs: Long): Double = timeNs / 1_000_000.0 * 16

        override fun close() = Unit
    }

    /** Presents frames as the test says; frame f is presented at 2 s + f / rate. */
    class FakeSink(override val sampleRateHz: Int) : AudioSink {
        override var framesWritten = 0L
        var presented = 0L
        var stops = 0
        override val framesPresented: Long get() = presented
        override fun presentationTimeOf(framePosition: Long): Long =
            2_000_000_000L + framePosition * 1_000_000_000L / sampleRateHz
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

    // -- while Hermes works ------------------------------------------------------------------

    @Test
    fun `words that end while Hermes works are dropped and the run goes on`() = runTest {
        val loop = loop()
        wake.fire()
        runCurrent()
        voice.hear(0, "scan the sector")
        voice.turnEnd("scan the sector")
        runCurrent()
        assertEquals(ConsoleState.THINKING, loop.state.value)

        // A stray word, or an approval answer, while the run works.
        voice.hear(1, "approve")
        voice.turnEnd("approve")
        runCurrent()

        assertEquals(listOf("scan the sector"), agent.dispatched, "nothing new is dispatched")
        assertEquals(emptyList<String>(), agent.stopped, "the run is not stopped")
        assertEquals(ConsoleState.THINKING, loop.state.value)

        reply("run-1", "req-1")
        voice.hear(2, "next question")
        voice.turnEnd("approve next question")
        runCurrent()
        assertEquals(listOf("scan the sector", "next question"), agent.dispatched, "the dropped words stay out")
    }

    // -- barge-in and playback ------------------------------------------------------------------

    /** A session with the run's reply on the speaker: dispatched, first delta out, audio playing. */
    private fun TestScope.speaking(): VoiceLoop {
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
        return loop
    }

    @Test
    fun `a server barge stops the reply and the run without a tts_cancel`() = runTest {
        val loop = speaking()
        sinks.last().presented = 600
        capture.speak()
        runCurrent()

        voice.barge("req-1")
        runCurrent()

        assertEquals(1, sinks.last().stops, "playback cleared")
        assertEquals(listOf("run-1"), agent.stopped)
        assertEquals(emptyList<String>(), voice.ttsCancelled, "the server already cancelled it")
        assertEquals(1, voice.playback.count { it.startsWith("stop req-1") }, "one stop")
        assertEquals(ConsoleState.LISTENING, loop.state.value)
        assertTrue(LcarsCue.BARGE_IN !in cued)

        // The interruption is the next turn.
        voice.hear(1, "stop there")
        voice.turnEnd("stop there")
        runCurrent()
        assertEquals(listOf("long answer please", "stop there"), agent.dispatched)
    }

    @Test
    fun `a barge for another reply changes nothing`() = runTest {
        val loop = speaking()
        voice.barge("req-0")
        runCurrent()

        assertEquals(0, sinks.last().stops)
        assertEquals(emptyList<String>(), agent.stopped)
        assertEquals(ConsoleState.SPEAKING, loop.state.value)
    }

    @Test
    fun `the local detector never stops a reply in turn engine mode`() = runTest {
        val loop = speaking()
        repeat(20) { capture.shout() }
        runCurrent()

        assertEquals(0, sinks.last().stops)
        assertEquals(emptyList<String>(), voice.ttsCancelled)
        assertEquals(ConsoleState.SPEAKING, loop.state.value)
    }

    @Test
    fun `a reply played to its end reports its start and stop on the sent mic stream`() = runTest {
        // One second recorded before the session, never sent: the clock must not count it.
        capture.framesRead = 16_000
        loop()
        wake.fire()
        runCurrent()
        capture.speak()
        runCurrent()
        voice.hear(0, "short answer")
        voice.turnEnd("short answer")
        runCurrent()
        agent.emit(HermesEvent.MessageDelta("run-1", "Aye."))
        agent.emit(HermesEvent.RunCompleted("run-1"))
        runCurrent()
        voice.emit(TtsEvent.Start("req-1", 24_000))
        voice.emit(TtsEvent.Audio(ByteArray(480), 24_000))
        voice.emit(TtsEvent.End("req-1"))
        runCurrent()

        sinks.last().presented = 1
        capture.speak()
        runCurrent()
        sinks.last().presented = 240
        capture.speak()
        runCurrent()

        // Frame 0 plays at 2 s: record index 32000, which is 16000 past the first sent sample.
        // Frame 240 plays 10 ms later. Each reply gets exactly one of each.
        assertEquals(listOf("start req-1 16000", "stop req-1 16160 240"), voice.playback)
    }

    @Test
    fun `a reply cut before it played reports nothing`() = runTest {
        val loop = speaking()
        voice.barge("req-1")
        runCurrent()
        assertEquals(emptyList<String>(), voice.playback)
        assertEquals(ConsoleState.LISTENING, loop.state.value)
    }

    // -- fallback to classic ---------------------------------------------------------------------

    /**
     * After [status], the loop takes a whole turn the classic way: the endpoint dispatches, a
     * local trip stops the reply, the follow-up window holds the floor and then closes it, and no
     * playback frame goes out.
     */
    private fun TestScope.assertClassicTurn(loop: VoiceLoop, runId: String, requestId: String) {
        voice.hear(10, "classic question")
        runCurrent()
        assertEquals("classic question", agent.dispatched.last(), "the endpoint dispatched it")

        agent.emit(HermesEvent.MessageDelta(runId, "A long reply "))
        runCurrent()
        voice.emit(TtsEvent.Start(requestId, 24_000))
        voice.emit(TtsEvent.Audio(ByteArray(4_800), 24_000))
        runCurrent()
        sinks.last().presented = 600
        repeat(20) { capture.shout() }
        runCurrent()
        assertEquals(listOf(requestId), voice.ttsCancelled, "the local detector barged in")
        assertEquals(emptyList<String>(), voice.playback.filter { requestId in it }, "no playback frames")
        assertTrue(LcarsCue.BARGE_IN in cued)
        assertEquals(ConsoleState.LISTENING, loop.state.value)
    }

    private fun TestScope.refusedBeforeTheWake(refusal: String) {
        voice.status.value = TurnEngineStatus.Refused(refusal)
        val loop = loop()
        wake.fire()
        runCurrent()
        assertClassicTurn(loop, "run-1", "req-1")
    }

    @Test
    fun `turn_engine_unavailable before the wake runs the classic loop`() = runTest {
        refusedBeforeTheWake("turn_engine_unavailable")
    }

    @Test
    fun `turn_engine_unsupported before the wake runs the classic loop`() = runTest {
        refusedBeforeTheWake("turn_engine_unsupported")
    }

    @Test
    fun `invalid_request for the opt-in runs the classic loop`() = runTest {
        refusedBeforeTheWake("invalid_request")
    }

    @Test
    fun `a refusal while the session waits hands it to the classic loop`() = runTest {
        voice.status.value = TurnEngineStatus.Pending
        val loop = loop()
        wake.fire()
        runCurrent()
        voice.status.value = TurnEngineStatus.Refused("turn_engine_unavailable")
        runCurrent()
        assertClassicTurn(loop, "run-1", "req-1")
    }

    @Test
    fun `a lost turn engine mid-session falls back, follow-up window included`() = runTest {
        val loop = loop(followUpWindowMillis = { 1_000 })
        wake.fire()
        runCurrent()
        voice.hear(0, "first question")
        voice.turnEnd("first question")
        runCurrent()
        voice.status.value = TurnEngineStatus.Lost
        runCurrent()
        reply("run-1", "req-1")

        // The classic end of a spoken reply: the follow-up window, then READY behind the wake word.
        assertEquals(ConsoleState.LISTENING, loop.state.value)
        testScheduler.advanceTimeBy(2_000)
        runCurrent()
        assertEquals(ConsoleState.READY, loop.state.value)
        assertTrue(LcarsCue.LISTENING_CLOSED in cued)

        val before = voice.playback.size
        wake.fire()
        runCurrent()
        assertClassicTurn(loop, "run-2", "req-2")
        assertEquals(before, voice.playback.size)
    }

    // -- the idle clock --------------------------------------------------------------------------

    @Test
    fun `the loop says when a session is listening with no turn in progress`() = runTest {
        val loop = loop()
        assertFalse(loop.sessionListening)
        wake.fire()
        runCurrent()
        assertTrue(loop.sessionListening)

        voice.hear(0, "report")
        voice.turnEnd("report")
        runCurrent()
        assertFalse(loop.sessionListening, "a turn is in progress")

        reply("run-1", "req-1")
        assertTrue(loop.sessionListening)

        loop.abort()
        assertFalse(loop.sessionListening)
    }

    // -- the approval line -------------------------------------------------------------------

    @Test
    fun `the approval line plays as a reply on the main link while Hermes waits`() = runTest {
        val loop = loop()
        wake.fire()
        runCurrent()
        voice.hear(0, "clean the temp directory")
        voice.turnEnd("clean the temp directory")
        runCurrent()

        assertTrue(loop.announce("Approval required."), "a session takes the line")
        assertEquals(Triple("req-2", "Approval required.", true), voice.ttsOut.last())

        voice.emit(TtsEvent.Start("req-2", 24_000))
        voice.emit(TtsEvent.Audio(ByteArray(480), 24_000))
        voice.emit(TtsEvent.End("req-2"))
        runCurrent()
        sinks.last().presented = 240
        capture.speak()
        runCurrent()

        assertEquals(listOf("start req-2", "stop req-2"), voice.playback.map { it.split(" ").take(2).joinToString(" ") })
        assertEquals(ConsoleState.THINKING, loop.state.value, "the run still waits on the approval")

        // The run carries on and speaks its reply as usual.
        reply("run-1", "req-1")
        assertEquals(ConsoleState.LISTENING, loop.state.value)
    }

    @Test
    fun `the approval line stays off the main link once the reply is on the wire`() = runTest {
        val loop = speaking()
        val sent = voice.ttsOut.size
        assertTrue(loop.announce("Approval required."), "handled: cue only")
        assertEquals(sent, voice.ttsOut.size, "a new request would cancel the open reply")
    }

    @Test
    fun `outside a turn engine session the loop leaves the line to its own link`() = runTest {
        val loop = loop(turnEngineMode = false)
        assertFalse(loop.announce("Approval required."))
    }

    // -- a reconnect inside a session -----------------------------------------------------------

    @Test
    fun `a reconnect that never shows Pending still starts the session's turns over`() = runTest {
        loop()
        wake.fire()
        runCurrent()
        voice.hear(0, "first")
        voice.turnEnd("first")
        runCurrent()
        reply("run-1", "req-1")

        voice.reconnect()
        runCurrent()
        assertEquals(1, voice.currentTurn, "the session opened a turn on the new connection")

        // The new connection numbers its turns and segments from the start again.
        voice.hear(0, "second", turnId = 1)
        voice.turnEnd("second", turnId = 1)
        runCurrent()
        assertEquals(listOf("first", "second"), agent.dispatched)
    }
}
