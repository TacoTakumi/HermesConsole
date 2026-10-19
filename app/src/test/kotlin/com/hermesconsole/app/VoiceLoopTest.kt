package com.hermesconsole.app

import com.hermesconsole.audio.AudioCapture
import com.hermesconsole.audio.AudioConfig
import com.hermesconsole.audio.AudioSink
import com.hermesconsole.audio.BargeInDetector
import com.hermesconsole.audio.ConvergenceGuard
import com.hermesconsole.audio.FakeSoftwareAec
import com.hermesconsole.audio.SoftwareAec
import com.hermesconsole.audio.TtsPlayer
import com.hermesconsole.audio.rmsDbfs
import com.hermesconsole.hermesclient.HermesEvent
import com.hermesconsole.hermesclient.HermesMessage
import com.hermesconsole.lcars.LcarsCue
import com.hermesconsole.voiceclient.Transcript
import com.hermesconsole.voiceclient.TranscriptSegment
import com.hermesconsole.voiceclient.TtsEvent
import com.hermesconsole.wakeword.WakeWord
import com.hermesconsole.wakeword.WakeWordSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * T-13 / REQ-12: the console speaks while Hermes is still writing.
 *
 * The requirement is an ordering claim across two pipes at once - tokens going out to be spoken
 * before the run that produced them has finished. On device that is measured end to end
 * (VoiceLoopTurnTest); here it is pinned down where it can be made to fail on purpose: every delta
 * reaches `tts_stream` the moment it arrives, and nothing waits for `run.completed`.
 *
 * The failure this guards against is the tempting one. Accumulating the reply and speaking it at the
 * end is less code and passes any test that only checks *what* was said - so the assertions below
 * are all about *when*.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceLoopTest {

    // -- fakes -------------------------------------------------------------------------------

    private data class TtsFrame(val requestId: String, val text: String, val done: Boolean)

    private class FakeVoiceLink : VoiceLink {
        val transcriptState = MutableStateFlow(Transcript())
        override val transcript: StateFlow<Transcript> get() = transcriptState

        private val ttsIn = Channel<TtsEvent>(Channel.UNLIMITED)
        override val tts: Flow<TtsEvent> = ttsIn.consumeAsFlow()

        val captured = mutableListOf<ByteArray>()
        val ttsOut = mutableListOf<TtsFrame>()
        val ttsCancelled = mutableListOf<String>()

        /** The server owns the turn counter (D-18): each turn_start advances it and acks the id. */
        private var openTurn = 0
        val turnsOpened = mutableListOf<Int>()

        /**
         * When set, turn_start suspends on its ack instead of returning synchronously - the link
         * down at wake, or the socket dropping between the turn_start frame and the turn_started
         * ack (T-51). The test drives the ack with [ackTurnStart], or cancels it with
         * [cancelPendingTurnStart] exactly as the real client's drainStaleState cancels a pending
         * turn_start when it redials (VoiceManagerClient, REQ-10).
         */
        var suspendTurnStart = false
        private var pendingTurnStart: CompletableDeferred<Int>? = null

        override suspend fun turnStart(): Int {
            openTurn++
            turnsOpened += openTurn
            if (!suspendTurnStart) return openTurn
            val ack = CompletableDeferred<Int>()
            pendingTurnStart = ack
            return ack.await()
        }

        /** The server's turn_started ack finally arrives on the live link. */
        fun ackTurnStart() {
            pendingTurnStart?.complete(openTurn)
        }

        /** A redial cancels the pending turn_start, as the real client's drainStaleState does. */
        fun cancelPendingTurnStart() {
            pendingTurnStart?.cancel()
        }

        override fun sendCapture(frame: ByteArray) {
            captured += frame
        }

        override fun sendTtsText(requestId: String, text: String, done: Boolean) {
            ttsOut += TtsFrame(requestId, text, done)
        }

        override fun cancelTts(requestId: String) {
            ttsCancelled += requestId
        }

        /** VoiceManager sends the console some speech. */
        fun emit(event: TtsEvent) {
            ttsIn.trySend(event)
        }

        /**
         * VoiceManager revises the transcript - [index] is the segment's identity, not its place.
         * Segments are stamped with the open turn unless the test says otherwise, as the server
         * stamps them (D-18).
         */
        fun hear(
            index: Int,
            text: String,
            endpointed: Boolean = false,
            isFinal: Boolean = false,
            turnId: Int = openTurn,
        ) {
            transcriptState.value = transcriptState.value.let { current ->
                Transcript(
                    (current.segments.filterNot { it.index == index } +
                        TranscriptSegment(index, text, isFinal, endpointed, turnId)).sortedBy { it.index },
                )
            }
        }
    }

    private class FakeAgent : Agent {
        private val events = mutableMapOf<String, Channel<HermesEvent>>()
        val dispatched = mutableListOf<String>()
        val sessions = mutableListOf<String>()
        val stopped = mutableListOf<String>()
        var runId = "run-1"

        /** Set to make the next dispatch throw - Hermes unreachable at dispatch (T-50). */
        var dispatchError: Throwable? = null

        override suspend fun dispatch(input: String, sessionId: String): String {
            dispatchError?.let { throw it }
            dispatched += input
            sessions += sessionId
            return runId
        }

        override suspend fun stop(runId: String) {
            stopped += runId
        }

        // Ends on a terminal event, exactly as HermesClient's stream does - the loop's `done` frame
        // depends on this flow completing. One channel per run, as the real client has one SSE
        // stream per run: a superseded run's late deltas must not leak into its replacement.
        override fun events(runId: String): Flow<HermesEvent> = flow {
            for (event in channel(runId)) {
                emit(event)
                if (event.isTerminal) break
            }
        }

        fun emit(event: HermesEvent) {
            channel(event.runId).trySend(event)
        }

        /**
         * The run's event stream drops mid-reply (T-50): the console is left collecting a flow that
         * throws. Buffered events drain first, then the `for` loop rethrows [cause] - exactly as a
         * dead SSE connection surfaces to HermesClient's caller.
         */
        fun failStream(runId: String, cause: Throwable) {
            channel(runId).close(cause)
        }

        private fun channel(runId: String) = events.getOrPut(runId) { Channel(Channel.UNLIMITED) }
    }

    private class FakeWake : WakeWordSource {
        private val heard = Channel<WakeWord>(Channel.UNLIMITED)
        var resets = 0

        /** Every frame the wake detector was offered, in order - the routing test's evidence (REQ-08). */
        val seen = mutableListOf<ByteArray>()

        override fun detections(frames: Flow<ByteArray>): Flow<WakeWord> = flow {
            coroutineScope {
                // The real detector consumes the capture flow; so must this one, or the frames never
                // move and nothing is ever forwarded to STT.
                launch { frames.collect { seen += it } }
                for (word in heard) emit(word)
            }
        }

        fun fire(atMillis: Long = 1L) {
            heard.trySend(WakeWord("hey computer", atMillis))
        }

        override fun reset() {
            resets++
        }

        override fun close() = Unit
    }

    private class FakeCapture : AudioCapture {
        override val config = AudioConfig()
        private val pcm = Channel<ByteArray>(Channel.UNLIMITED)
        override fun frames(): Flow<ByteArray> = pcm.consumeAsFlow()

        fun speak(frame: ByteArray) {
            pcm.trySend(frame)
        }

        override fun close() = Unit
    }

    private class FakeSink(override val sampleRateHz: Int) : AudioSink {
        val written = mutableListOf<ByteArray>()
        var stops = 0
        override var underrunCount: Int = 0

        /** A settable measured latency: null is the unmeasured default, the seeding test sets one. */
        override var latencyMillis: Int? = null

        override fun write(pcm: ByteArray) {
            written += pcm
        }

        override fun stop() {
            stops++
        }

        override fun close() = Unit
    }

    // -- harness -----------------------------------------------------------------------------

    private val voice = FakeVoiceLink()
    private val agent = FakeAgent()
    private val wake = FakeWake()
    private val capture = FakeCapture()
    private val sinks = mutableListOf<FakeSink>()
    private val player = TtsPlayer({ rate -> FakeSink(rate).also { sinks += it } })
    private val cued = mutableListOf<LcarsCue>()

    /** Set to make every cue callback throw: the loop must shrug a dead cue sink off. */
    private var cueFailure: Exception? = null

    /** A monotonic clock, so a turn's four moments are ordered and deterministic. */
    private val tick = AtomicLong(0)

    private val requestIds = AtomicLong(0)

    private fun TestScopeLoop(
        scope: CoroutineScope,
        // The canceller is warm by default: the cold-start window is the guard's own subject
        // (ConvergenceGuardTest), and every barge-in below would otherwise be measuring it instead.
        bargeIn: BargeInDetector = BargeInDetector(guard = ConvergenceGuard(windowMs = 0)),
        sessionId: suspend () -> String = { "session-test" },
        // Off by default: the window is REQ-40's own subject, and every other test here would
        // otherwise end its spoken turns in a window instead of at READY.
        followUpWindowMillis: () -> Long = { 0 },
        // Zero by default for the same reason: the beat is REQ-21's own subject, and with the
        // shipping default every follow-up test would have to advance the clock to hear its cue.
        followUpCueGraceMillis: Long = 0,
        // Overridable for the chunk-gap re-arm, which reasons about the wall-clock spacing between
        // PCM chunks; the default monotonic tick keeps every other test deterministic.
        now: () -> Long = { tick.incrementAndGet() },
        // On by default (the design); the half-duplex stopgap wires it off. A supplier, as the
        // loop reads it: a test that flips it between two trips sees each read at its own trip.
        bargeInEnabled: () -> Boolean = { true },
        // The software AEC factory (T-08): null by default, so every existing test runs raw
        // capture exactly as before; the AEC-wiring tests inject a fake through this seam.
        aecFactory: (() -> SoftwareAec?)? = null,
        // A hook that runs after the loop is built but before it starts - e.g. to open a TTS
        // track so the capture-start delay seed has a measurement to read.
        preStart: () -> Unit = {},
        mediaTap: (String, String) -> Unit = { _, _ -> },
    ) = VoiceLoop(
        capture = capture,
        wake = wake,
        voice = voice,
        agent = agent,
        player = player,
        sessionId = sessionId,
        followUpWindowMillis = followUpWindowMillis,
        followUpCueGraceMillis = followUpCueGraceMillis,
        bargeIn = bargeIn,
        bargeInEnabled = bargeInEnabled,
        aecFactory = aecFactory,
        scope = scope,
        now = now,
        newRequestId = { "req-${requestIds.incrementAndGet()}" },
        mediaTap = mediaTap,
        cues = {
            cued += it
            cueFailure?.let { failure -> throw failure }
        },
    ).also { preStart(); it.start() }

    /**
     * A frame of somebody talking: 20 ms at about -12 dBFS, well over the speech gate's -35 dB bar.
     * Five of them in a row is what the gate calls an interruption.
     */
    private fun speech(): ByteArray = ByteArray(AudioConfig().chunkBytes) { i ->
        if (i % 2 == 0) 0x40.toByte() else 0x1F // 8000, little-endian
    }

    /** A frame of nobody talking. */
    private fun silence(): ByteArray = ByteArray(AudioConfig().chunkBytes)

    /**
     * One spoken word at the microphone, as the window's speech gate hears it (T-25, REQ-23): a
     * breath of silence resetting the gate's run, then 100 ms of sustained speech-level energy
     * confirming it. Real speech dips between words, so a long utterance is a train of these.
     */
    private fun word() {
        capture.speak(silence())
        repeat(5) { capture.speak(speech()) }
    }

    // -- the requirement --------------------------------------------------------------------

    @Test
    fun `a MEDIA line is never spoken or shown, its target goes to the media tap`() = runTest {
        val tapped = mutableListOf<Pair<String, String>>()
        val loop = TestScopeLoop(
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            mediaTap = { runId, target -> tapped += runId to target },
        )

        wake.fire()
        voice.hear(0, "show me a picture", endpointed = true)
        runCurrent()
        agent.emit(HermesEvent.MessageDelta("run-1", "Here is the picture.\n\nMEDIA:https://example.com/a.jpg"))
        agent.emit(HermesEvent.RunCompleted("run-1"))
        runCurrent()

        assertEquals(listOf("run-1" to "https://example.com/a.jpg"), tapped)
        assertEquals("Here is the picture.\n\n", loop.turn.value.reply)
        assertEquals("Here is the picture.\n\n", voice.ttsOut.joinToString("") { it.text })
    }

    @Test
    fun `deltas are spoken as they arrive, not gathered up and spoken at the end`() = runTest {
        val loop = TestScopeLoop(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        wake.fire()
        runCurrent()
        assertEquals(ConsoleState.LISTENING, loop.state.value)

        voice.hear(0, "what is the warp core status", endpointed = true)
        runCurrent()
        assertEquals(listOf("what is the warp core status"), agent.dispatched)
        assertEquals(ConsoleState.THINKING, loop.state.value)

        agent.emit(HermesEvent.MessageDelta("run-1", "The warp core "))
        agent.emit(HermesEvent.MessageDelta("run-1", "is stable. "))
        runCurrent()

        // THE ASSERTION. Both deltas are already on their way to be spoken, and the run has not
        // finished - no terminal event has been seen, and no `done` has been sent. A loop that
        // buffered the reply would have sent nothing at all by now.
        assertEquals(
            listOf(
                TtsFrame("req-1", "The warp core ", done = false),
                TtsFrame("req-1", "is stable. ", done = false),
            ),
            voice.ttsOut,
            "each delta must go out verbatim as it arrives",
        )
        assertNull(loop.turn.value.runCompletedAtMillis, "the run is still in flight")

        // VoiceManager comes back with speech for the first sentence, still mid-run.
        voice.emit(TtsEvent.Start("req-1", 22_050))
        voice.emit(TtsEvent.Audio(ByteArray(320), 22_050))
        runCurrent()
        assertEquals(ConsoleState.SPEAKING, loop.state.value)
        assertEquals(22_050, sinks.single().sampleRateHz, "the rate is the turn's, off the wire")
        assertEquals(1, sinks.single().written.size)

        agent.emit(HermesEvent.RunCompleted("run-1", output = "The warp core is stable."))
        runCurrent()

        // REQ-12, stated as the turn log states it: the console was audible before the run ended.
        val turn = loop.turn.value
        assertTrue(turn.spokeBeforeRunCompleted, "audio must reach the speaker before run.completed")
        assertTrue(
            turn.firstAudioAtMillis!! < turn.runCompletedAtMillis!!,
            "first audio ${turn.firstAudioAtMillis} should precede run.completed ${turn.runCompletedAtMillis}",
        )

        // Only now does the request close - the terminal event is what flushes the last sentence.
        assertEquals(TtsFrame("req-1", "", done = true), voice.ttsOut.last())

        voice.emit(TtsEvent.End("req-1"))
        runCurrent()
        assertEquals(ConsoleState.READY, loop.state.value)
    }

    @Test
    fun `the next wake word does not re-dispatch the last turn's transcript`() = runTest {
        // VoiceManager numbers segments per connection, so the previous turn's endpointed text is
        // still sitting in the transcript when the console wakes again - stamped with the old
        // turn_id. Unfenced, the loop would read it, believe it, and dispatch a turn the speaker
        // never asked for (D-18).
        val loop = TestScopeLoop(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        wake.fire()
        voice.hear(0, "set a course for home", endpointed = true, isFinal = true)
        runCurrent()
        assertEquals(listOf("set a course for home"), agent.dispatched)

        agent.emit(HermesEvent.RunCompleted("run-1", output = ""))
        runCurrent()
        assertEquals(ConsoleState.READY, loop.state.value)

        // Woken again. The old segment is still in the transcript, and it is still endpointed.
        agent.runId = "run-2"
        wake.fire(atMillis = 99)
        runCurrent()

        assertEquals(
            listOf("set a course for home"),
            agent.dispatched,
            "the last turn's text must not be dispatched again",
        )
        assertEquals(ConsoleState.LISTENING, loop.state.value, "the console is waiting for new speech")

        // Now the speaker actually says something, and only that is dispatched.
        voice.hear(1, "engage", endpointed = true, isFinal = true)
        runCurrent()
        assertEquals(listOf("set a course for home", "engage"), agent.dispatched)
    }

    @Test
    fun `a run that says nothing still returns the console to READY`() = runTest {
        // No deltas means no tts_stream request was ever opened, so no audio_end is coming to end
        // the turn. A console that waited for one would sit in THINKING until someone rebooted it.
        val loop = TestScopeLoop(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        wake.fire()
        voice.hear(0, "do nothing", endpointed = true)
        runCurrent()
        assertEquals(ConsoleState.THINKING, loop.state.value)

        agent.emit(HermesEvent.RunFailed("run-1", error = "tool exploded"))
        runCurrent()

        assertEquals(ConsoleState.READY, loop.state.value)
        assertTrue(voice.ttsOut.isEmpty(), "nothing was spoken, so nothing was sent to be spoken")
    }

    // -- REQ-04: barge-in ---------------------------------------------------------------------

    @Test
    fun `speaking over the assistant stops the speaker, the synthesis and the run - and is the next turn`() =
        runTest {
            val loop = TestScopeLoop(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

            // A turn, up to the point where the console is talking.
            wake.fire()
            voice.hear(0, "what is the warp core status", endpointed = true)
            runCurrent()
            agent.emit(HermesEvent.MessageDelta("run-1", "The warp core is stable. "))
            voice.emit(TtsEvent.Start("req-1", 22_050))
            voice.emit(TtsEvent.Audio(ByteArray(320), 22_050))
            runCurrent()
            assertEquals(ConsoleState.SPEAKING, loop.state.value)
            assertEquals(1, sinks.single().written.size)

            // The human talks over it. 100 ms of sustained speech is what the gate calls an
            // interruption, so five frames is exactly the moment of truth.
            agent.runId = "run-2"
            repeat(5) { capture.speak(speech()) }
            runCurrent()

            // THE THREE CANCELS. All of them, or the interruption is a lie: the tablet goes quiet
            // while the synthesiser keeps making the sentence and the run keeps writing it, and both
            // come back a second later to talk over the person who interrupted.
            assertEquals(1, sinks.single().stops, "the local track must be flushed and stopped")
            assertEquals(listOf("req-1"), voice.ttsCancelled, "VoiceManager must stop synthesising")
            assertEquals(listOf("run-1"), agent.stopped, "Hermes must stop the run")

            val bargeIn = loop.lastBargeIn.value!!
            assertNotNull(bargeIn.playbackStoppedAtMillis)
            assertNotNull(bargeIn.ttsCancelledAtMillis)
            assertNotNull(bargeIn.runStoppedAtMillis)
            assertNotNull(bargeIn.cancelLatencyMillis, "all three cancels must be stamped")

            // And the console is listening to the person who interrupted it.
            assertEquals(ConsoleState.LISTENING, loop.state.value)

            // The cancelled turn does not go quiet on the wire. VoiceManager finishes the chunk it
            // is in and then sends audio_end for the request it just killed - and neither may be
            // heard: the first would speak the abandoned reply, the second would end the turn the
            // interrupter has already started.
            voice.emit(TtsEvent.Audio(ByteArray(160), 22_050))
            voice.emit(TtsEvent.End("req-1"))
            runCurrent()
            assertEquals(1, sinks.single().written.size, "not one byte of the cancelled reply is spoken")
            assertEquals(160, loop.lastBargeIn.value!!.droppedAudioBytes)
            assertEquals(ConsoleState.LISTENING, loop.state.value, "the cancelled turn's audio_end is not ours")

            // Nor may a token of the dead run reach VoiceManager: the server accumulates by
            // request_id, so a delta landing after the cancel would re-open the request and the
            // abandoned reply would start speaking over the human.
            agent.emit(HermesEvent.MessageDelta("run-1", "and the deflector is at full."))
            runCurrent()
            assertTrue(
                voice.ttsOut.none { it.requestId == "req-1" && it.text.contains("deflector") },
                "a token from the cancelled run must never be sent to be spoken",
            )
            assertTrue(voice.ttsOut.none { it.done }, "the cancelled request is never flushed with done")

            // The interruption becomes turn N+1, off the transcript that never stopped being fed.
            voice.hear(1, "belay that", endpointed = true, isFinal = true)
            runCurrent()
            assertEquals(
                listOf("what is the warp core status", "belay that"),
                agent.dispatched,
                "what the human said over the top of the reply is the next turn",
            )
            assertEquals(ConsoleState.THINKING, loop.state.value)
        }

    // -- REQ-08: the cancelled stream ---------------------------------------------------------

    /**
     * A [SoftwareAec] that records every capture frame and returns digital silence - the cancelled
     * stream as a device with a perfect canceller would deliver it. The routing test uses it to
     * prove the barge-in detector consumes the POST-AEC frame: through this fake, the same speech
     * that trips raw capture (the barge-in tests above) must not trip at all (REQ-08, REQ-01).
     */
    private class ZeroingAec : SoftwareAec {
        val captureInputs = mutableListOf<ByteArray>()
        var delaySeedMs: Int? = null
        var closed = false

        override fun processCapture(frame: ByteArray): ByteArray {
            captureInputs += frame
            return ByteArray(frame.size)
        }

        override fun processReference(pcm: ByteArray) = Unit

        override fun setDelaySeedMs(ms: Int) {
            delaySeedMs = ms
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun `the barge-in detector sees the cancelled frame while STT and wake keep the raw one`() = runTest {
        val aec = ZeroingAec()
        val loop = TestScopeLoop(
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            aecFactory = { aec },
        )

        // A turn, up to where the console is talking - the same shape as the barge-in tests.
        wake.fire()
        voice.hear(0, "what is the warp core status", endpointed = true)
        runCurrent()
        agent.emit(HermesEvent.MessageDelta("run-1", "The warp core is stable. "))
        voice.emit(TtsEvent.Start("req-1", 22_050))
        voice.emit(TtsEvent.Audio(ByteArray(320), 22_050))
        runCurrent()
        assertEquals(ConsoleState.SPEAKING, loop.state.value)

        // The speech that trips raw capture - five sustained frames, as in `speaking over the
        // assistant...` above. Through the zeroing canceller the detector must hear silence: it
        // consumed the POST-AEC frame, not the raw one (REQ-08, REQ-01).
        val frames = List(5) { speech() }
        frames.forEach { capture.speak(it) }
        runCurrent()

        assertNull(loop.lastBargeIn.value, "the detector heard the cancelled stream, which is silence")

        // The canceller itself was fed the raw capture, in order.
        assertEquals(frames.size, aec.captureInputs.size)
        frames.zip(aec.captureInputs).forEach { (raw, seen) -> assertTrue(raw.contentEquals(seen)) }

        // STT and the wake detector kept the raw frames (REQ-08).
        assertEquals(frames.size, voice.captured.size)
        frames.zip(voice.captured).forEach { (raw, seen) -> assertTrue(raw.contentEquals(seen)) }
        assertEquals(frames.size, wake.seen.size)
        frames.zip(wake.seen).forEach { (raw, seen) -> assertTrue(raw.contentEquals(seen)) }

        loop.close()
    }

    @Test
    fun `the AEC is seeded with the measured output latency at capture start and closed at close`() =
        runTest {
            val aec = FakeSoftwareAec()
            val loop = TestScopeLoop(
                CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                aecFactory = { aec },
                // Open a TTS track before capture starts and give it a measured latency: the seed
                // the loop reads at capture start must be exactly that measurement (REQ-05).
                preStart = {
                    player.onAudioStart(16_000)
                    sinks.single().latencyMillis = 50
                },
            )

            assertEquals(50, aec.delaySeedMs, "seeded from the sink's measured output latency")
            assertFalse(aec.closed)

            loop.close()
            assertTrue(aec.closed, "the capture-session AEC is closed with the loop")
        }

    @Test
    fun `an AEC with no measured latency at capture start is not seeded`() = runTest {
        val aec = FakeSoftwareAec()
        val loop = TestScopeLoop(
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            aecFactory = { aec },
        )

        assertNull(aec.delaySeedMs, "no sink, no measurement, no seed - the AEC3 controller tracks from zero")

        loop.close()
    }

    // -- T-09: the TTS reference reaches the AEC (REQ-03, REQ-04) ----------------------------------

    @Test
    fun `every played chunk reaches the AEC in order - the reference bytes equal the tapped bytes`() =
        runTest {
            val aec = FakeSoftwareAec()
            val loop = TestScopeLoop(
                CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                aecFactory = { aec },
            )

            // A turn up to where the console speaks - the same shape as the barge-in tests.
            wake.fire()
            voice.hear(0, "open the airlock", endpointed = true)
            runCurrent()
            agent.emit(HermesEvent.MessageDelta("run-1", "Opening. "))
            // A 16 kHz engine feed: the reference resampler passes it through untouched, so the bytes
            // the AEC receives must be exactly the tapped bytes, in order (T-09's acceptance).
            voice.emit(TtsEvent.Start("req-1", 16_000))
            val first = ByteArray(640) { (it * 37).toByte() } // 20 ms at 16 kHz, real content
            val second = ByteArray(640) { (255 - it).toByte() }
            voice.emit(TtsEvent.Audio(first, 16_000))
            voice.emit(TtsEvent.Audio(second, 16_000))
            // The reference drains onto the AEC on the capture cadence (T-12 pacer): drive two
            // capture frames so the two buffered 640-byte chunks are handed over in order.
            capture.speak(ByteArray(640))
            capture.speak(ByteArray(640))
            runCurrent()

            assertEquals(2, aec.referenceInputs.size, "both chunks reached the AEC, in order")
            assertTrue(first.contentEquals(aec.referenceInputs[0]), "first chunk, byte for byte")
            assertTrue(second.contentEquals(aec.referenceInputs[1]), "second chunk, byte for byte")
            // And the tapped bytes are exactly what the sink played: the 16 kHz tap needs no conversion.
            assertEquals(2, sinks.single().written.size)
            assertTrue(first.contentEquals(sinks.single().written[0]))
            assertTrue(second.contentEquals(sinks.single().written[1]))

            loop.close()
        }

    @Test
    fun `the reference is resampled to 16 kHz before the AEC - the only conversion in the path`() =
        runTest {
            val aec = FakeSoftwareAec()
            val loop = TestScopeLoop(
                CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                aecFactory = { aec },
            )

            wake.fire()
            voice.hear(0, "engage the drive", endpointed = true)
            runCurrent()
            agent.emit(HermesEvent.MessageDelta("run-1", "Engaging. "))
            // A 22050 Hz engine feed (OpenVoice): the raw tap is 882 bytes per 20 ms frame, but the
            // APM runs at 16 kHz end to end (REQ-04) - the wiring must convert before processReference.
            voice.emit(TtsEvent.Start("req-1", 22_050))
            voice.emit(TtsEvent.Audio(ByteArray(882) { (it * 7).toByte() }, 22_050))
            voice.emit(TtsEvent.End("req-1"))
            // Drain the settled reference on the capture cadence (T-12 pacer): one whole 640-byte
            // 16 kHz frame, no more - the 882-byte tap collapses to exactly one 20 ms frame.
            capture.speak(ByteArray(640))
            runCurrent()

            // The chunk's resampled bytes plus the zero-padded tail from flush: exactly 320 samples
            // (640 bytes) per 20 ms at 16 kHz - never the raw 882-byte tap.
            assertEquals(640, aec.referenceInputs.sumOf { it.size },
                "882 bytes at 22050 become 640 bytes at 16000 - the exact ratio")
            loop.close()
        }

    // -- T-10: per-frame AEC cost and health (REQ-11, REQ-13) -------------------------------------

    @Test
    fun `the per-frame AEC cost is instrumented while the AEC is active`() = runTest {
        val aec = FakeSoftwareAec()
        val loop = TestScopeLoop(
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            aecFactory = { aec },
        )

        // Every frame offered to the loop is processed by the AEC, whatever the state - the
        // barge-in check happens on the raw stream. Three frames, three timed calls.
        val frames = List(3) { speech() }
        frames.forEach { capture.speak(it) }
        runCurrent()

        assertEquals(3, aec.captureInputs.size, "the AEC saw all three frames")
        assertEquals(3, loop.aecFrameCount, "all three calls were timed")
        assertTrue(loop.aecFrameTimeNs > 0, "the timed calls accumulated a duration")
        assertTrue(loop.aecMaxNs >= loop.aecFrameTimeNs / loop.aecFrameCount,
            "the max is at least the mean")

        loop.close()
    }

    @Test
    fun `the AEC is measured while barge-in sits behind the stopgap`() = runTest {
        val aec = FakeSoftwareAec()
        val loop = TestScopeLoop(
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            aecFactory = { aec },
            // The stored setting's default: barge-in off until on-device validation passes (REQ-13).
            bargeInEnabled = { false },
        )

        capture.speak(speech())
        runCurrent()

        assertEquals(1, loop.aecFrameCount,
            "frames still flow through the AEC and are timed under the stopgap")

        loop.close()
    }

    @Test
    fun `the half-duplex stopgap suppresses barge-in while the console speaks`() = runTest {
        // The ZEF->ZGC OTA broke this device's hardware AEC, so the console barges in on its own TTS
        // (docs/aec-spike.md "The tenth"). With bargeInEnabled=false the detector still measures but
        // the action is gated: the console keeps speaking and no cancel fires.
        val loop = TestScopeLoop(
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            bargeInEnabled = { false },
        )

        wake.fire()
        voice.hear(0, "status report", endpointed = true)
        runCurrent()
        agent.emit(HermesEvent.MessageDelta("run-1", "All systems nominal. "))
        voice.emit(TtsEvent.Start("req-1", 22_050))
        voice.emit(TtsEvent.Audio(ByteArray(320), 22_050))
        runCurrent()
        assertEquals(ConsoleState.SPEAKING, loop.state.value)

        // Speech-level energy over the reply - what would be an interruption if barge-in were live.
        repeat(5) { capture.speak(speech()) }
        runCurrent()

        assertNull(loop.lastBargeIn.value, "the action is gated: no barge-in is recorded")
        assertEquals(ConsoleState.SPEAKING, loop.state.value, "the console keeps speaking")
        assertTrue(voice.ttsCancelled.isEmpty(), "synthesis is not cancelled")
        assertTrue(agent.stopped.isEmpty(), "the run is not stopped")
        assertEquals(0, sinks.single().stops, "the speaker is not flushed")
    }

    @Test
    fun `the barge-in setting is read at each detection, so a flip applies to the next trip`() =
        runTest {
            // The settings toggle, as the wall wires it: a read of the stored value, not a copy
            // taken at construction. Off at the first trip, on by the second - in one loop, with
            // nothing rebuilt between them.
            var live = false
            val loop = TestScopeLoop(
                CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                bargeInEnabled = { live },
            )

            wake.fire()
            voice.hear(0, "status report", endpointed = true)
            runCurrent()
            agent.emit(HermesEvent.MessageDelta("run-1", "All systems nominal. "))
            voice.emit(TtsEvent.Start("req-1", 22_050))
            voice.emit(TtsEvent.Audio(ByteArray(320), 22_050))
            runCurrent()
            assertEquals(ConsoleState.SPEAKING, loop.state.value)

            // The first trip, with the setting off: measured, logged as suppressed, not acted on.
            repeat(5) { capture.speak(speech()) }
            runCurrent()
            assertNull(loop.lastBargeIn.value, "off at the trip: no barge-in is recorded")
            assertEquals(ConsoleState.SPEAKING, loop.state.value, "off at the trip: the reply plays on")
            assertTrue(voice.ttsCancelled.isEmpty(), "off at the trip: synthesis is not cancelled")
            assertTrue(agent.stopped.isEmpty(), "off at the trip: the run is not stopped")
            assertEquals(0, sinks.single().stops, "off at the trip: the speaker is not flushed")

            // The toggle flips mid-reply. The next trip - a breath, then another word - is acted on.
            live = true
            agent.runId = "run-2"
            word()
            runCurrent()
            assertNotNull(loop.lastBargeIn.value, "on at the trip: the barge-in is recorded")
            assertEquals(1, sinks.single().stops, "on at the trip: the speaker is flushed")
            assertEquals(listOf("req-1"), voice.ttsCancelled, "on at the trip: synthesis is cancelled")
            assertEquals(listOf("run-1"), agent.stopped, "on at the trip: the run is stopped")
            assertEquals(ConsoleState.LISTENING, loop.state.value)
        }

    @Test
    fun `a cold echo canceller cannot be barged in on`() = runTest {
        // The guard, honoured by the loop (T-36). A capture session that has not yet heard the
        // console speak leaks the console's own voice back into the microphone loudly enough to be
        // taken for a person - so until it has warmed up, speech-level energy is measured and not
        // acted on. Wired wrong (playing = false, say) the guard would never arm and barge-in would
        // be dead; wired absent, the console interrupts itself on its first sentence after a boot.
        val loop = TestScopeLoop(
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            bargeIn = BargeInDetector(), // the production 10 s window
        )

        wake.fire()
        voice.hear(0, "status report", endpointed = true)
        runCurrent()
        agent.emit(HermesEvent.MessageDelta("run-1", "All systems nominal. "))
        voice.emit(TtsEvent.Start("req-1", 22_050))
        voice.emit(TtsEvent.Audio(ByteArray(320), 22_050))
        runCurrent()
        assertEquals(ConsoleState.SPEAKING, loop.state.value)

        // 100 ms of speech - into an echo canceller with 100 ms of exposure.
        repeat(5) { capture.speak(speech()) }
        runCurrent()

        assertNull(loop.lastBargeIn.value, "a cold canceller's verdict is not to be trusted")
        assertEquals(ConsoleState.SPEAKING, loop.state.value)
        assertTrue(voice.ttsCancelled.isEmpty())
        assertTrue(agent.stopped.isEmpty())
        assertEquals(0, sinks.single().stops)

        // Ten seconds of the console's own voice later, the canceller is warm and the same speech is
        // acted on. (Exposure is counted in playback, not wall-clock: silence teaches it nothing.)
        repeat(500) { capture.speak(silence()) }
        runCurrent()
        repeat(5) { capture.speak(speech()) }
        runCurrent()

        assertNotNull(loop.lastBargeIn.value, "a warm canceller's verdict is the human interrupting")
        assertEquals(ConsoleState.LISTENING, loop.state.value)
        assertEquals(listOf("req-1"), voice.ttsCancelled)
        assertEquals(listOf("run-1"), agent.stopped)
    }

    @Test
    fun `speech resuming after a mid-reply tool-call silence re-arms the guard`() = runTest {
        // The eighth sighting (docs/aec-spike.md). One run's TTS is a single open request_id, so a
        // tool call that runs silent mid-reply sends no fresh audio_start - but the canceller
        // de-adapts through the quiet and leaks the speech that resumes after it. The gap between
        // PCM chunks is the only signal, and it must re-arm the guard.
        val clock = AtomicLong(1_000_000) // nonzero: the loop treats now()==0 as "no chunk yet"
        val loop = TestScopeLoop(
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            now = { clock.get() },
        )

        wake.fire()
        voice.hear(0, "weather for bermuda", endpointed = true)
        runCurrent()
        agent.emit(HermesEvent.MessageDelta("run-1", "Checking. "))
        voice.emit(TtsEvent.Start("req-1", 22_050))
        voice.emit(TtsEvent.Audio(ByteArray(320), 22_050)) // first chunk: the re-arm's baseline
        runCurrent()
        assertEquals(ConsoleState.SPEAKING, loop.state.value)

        // The canceller warms on the opening narration (default guard: windowMs = 0).
        repeat(5) { capture.speak(silence()) }
        runCurrent()

        // The tool call runs silent for three seconds, then speech resumes with a fresh chunk.
        clock.addAndGet(3_000)
        voice.emit(TtsEvent.Audio(ByteArray(320), 22_050))
        runCurrent()

        // The onset leak of the resumed speech: it must be suppressed, not acted on.
        repeat(5) { capture.speak(speech()) }
        runCurrent()

        assertNull(loop.lastBargeIn.value, "the resumed onset re-armed the guard: its leak is suppressed")
        assertEquals(ConsoleState.SPEAKING, loop.state.value)
        assertTrue(voice.ttsCancelled.isEmpty())
        assertTrue(agent.stopped.isEmpty())
    }

    @Test
    fun `a chunk arriving without a gap does not re-arm the guard`() = runTest {
        // The other side of the same wire: ordinary streaming chunks, close together, must not
        // re-arm - or the guard would be cold for the whole reply and REQ-04 would never hold.
        val clock = AtomicLong(1_000_000)
        val loop = TestScopeLoop(
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            now = { clock.get() },
        )

        wake.fire()
        voice.hear(0, "status report", endpointed = true)
        runCurrent()
        agent.emit(HermesEvent.MessageDelta("run-1", "All nominal. "))
        voice.emit(TtsEvent.Start("req-1", 22_050))
        voice.emit(TtsEvent.Audio(ByteArray(320), 22_050))
        runCurrent()
        repeat(5) { capture.speak(silence()) } // warm the canceller
        runCurrent()

        // A second chunk a few ms later - normal streaming cadence, no gap.
        clock.addAndGet(40)
        voice.emit(TtsEvent.Audio(ByteArray(320), 22_050))
        runCurrent()

        // A person speaks: the guard is still warm, so this is heard as the interruption it is.
        repeat(5) { capture.speak(speech()) }
        runCurrent()

        assertNotNull(loop.lastBargeIn.value, "no gap, no re-arm: a real interruption is still acted on")
        assertEquals(ConsoleState.LISTENING, loop.state.value)
        assertEquals(listOf("run-1"), agent.stopped)
    }

    @Test
    fun `the guard latches on the silence before a lagging reply's first chunk`() = runTest {
        // A1 of docs/handoff-2026-08-18-aec-dropout.md, and the diagnosis it is there to settle.
        // audio_start arrives when VoiceManager *begins* the reply, not when sound is audible, and
        // the loop enters SPEAKING there - so every capture frame through the model's lag is counted
        // as playback exposure. A lag past the guard's window spends the whole of it on silence, and
        // the guard is latched converged before the tablet has made a sound. This pins that
        // measurement; it does not fix it.
        val clock = AtomicLong(1_000_000) // nonzero: the loop treats now()==0 as "no chunk yet"
        val loop = TestScopeLoop(
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            bargeIn = BargeInDetector(guard = ConvergenceGuard(windowMs = 2_000)),
            now = { clock.get() },
        )

        wake.fire()
        voice.hear(0, "hello world in python", endpointed = true)
        runCurrent()
        agent.emit(HermesEvent.MessageDelta("run-1", "Here it is. "))
        voice.emit(TtsEvent.Start("req-1", 22_050))
        runCurrent()
        assertEquals(ConsoleState.SPEAKING, loop.state.value, "audio_start alone puts the console in SPEAKING")

        // Three seconds of the model's lag: no PCM has arrived, the room is quiet, and the capture
        // loop hands the guard 2 s of "playback" it never heard. 100 frames x 20 ms.
        clock.addAndGet(3_000)
        repeat(100) { capture.speak(silence()) }
        runCurrent()

        // Only now does the reply become audible.
        voice.emit(TtsEvent.Audio(ByteArray(320), 22_050))
        voice.emit(TtsEvent.End("req-1"))
        runCurrent()

        val cadence = requireNotNull(loop.lastReplyCadence) { "every reply records its playback shape" }
        assertEquals("req-1", cadence.requestId)
        assertEquals(3_000L, cadence.onsetLagMillis, "the lag from audio_start to the first chunk")
        assertTrue(cadence.onsetConverged, "the guard latched before the tablet made a sound")
        assertEquals(2_000L, cadence.onsetExposureMillis, "and it spent its whole window on silence")
    }

    @Test
    fun `a reply's chunk arrivals are summarised at audio_end`() = runTest {
        // The other half of A1: the delivery cadence, on one line at audio_end. It says how often
        // the stream stalled - each gap at or past the re-arm threshold is a mid-reply re-arm - and
        // it is the data a faithful replay of the live playback pattern would be built from.
        val clock = AtomicLong(1_000_000)
        val loop = TestScopeLoop(
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            now = { clock.get() },
        )

        wake.fire()
        voice.hear(0, "weather for bermuda", endpointed = true)
        runCurrent()
        agent.emit(HermesEvent.MessageDelta("run-1", "Checking. "))
        voice.emit(TtsEvent.Start("req-1", 22_050))
        voice.emit(TtsEvent.Audio(ByteArray(320), 22_050))
        runCurrent()

        // Two ordinary streaming gaps, then a tool call's three-second silence.
        clock.addAndGet(40)
        voice.emit(TtsEvent.Audio(ByteArray(320), 22_050))
        clock.addAndGet(100)
        voice.emit(TtsEvent.Audio(ByteArray(320), 22_050))
        clock.addAndGet(3_000)
        voice.emit(TtsEvent.Audio(ByteArray(320), 22_050))
        voice.emit(TtsEvent.End("req-1"))
        runCurrent()

        val cadence = requireNotNull(loop.lastReplyCadence)
        assertEquals(4, cadence.chunks, "every playable chunk is counted")
        assertEquals(1_280L, cadence.audioBytes)
        assertEquals(40L, cadence.minGapMillis)
        assertEquals(100L, cadence.medianGapMillis)
        assertEquals(3_000L, cadence.maxGapMillis)
        assertEquals(1, cadence.gapsOverRearmThreshold, "only the tool call's silence re-armed the guard")
    }

    @Test
    fun `a barge-in records what tripped it - the level, the canceller, and the starved track`() = runTest {
        // The console has interrupted itself three times with nobody in the room, and each sighting
        // was classified by hand from a server log and a shrug (docs/aec-spike.md). This is what
        // makes the fourth one evidence instead: the energy that fired the gate, whether the
        // canceller had earned the right to be believed when it did, and whether the mixer had been
        // restarting a starved track underneath - a restart being a fresh onset for the canceller to
        // re-learn, and the standing suspect.
        val loop = TestScopeLoop(
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            bargeIn = BargeInDetector(guard = ConvergenceGuard(windowMs = 100)),
        )

        wake.fire()
        voice.hear(0, "status report", endpointed = true)
        runCurrent()
        agent.emit(HermesEvent.MessageDelta("run-1", "All systems nominal. "))
        voice.emit(TtsEvent.Start("req-1", 22_050))
        voice.emit(TtsEvent.Audio(ByteArray(320), 22_050))
        runCurrent()
        assertEquals(ConsoleState.SPEAKING, loop.state.value)

        // The device starved this track five times while it played.
        sinks.single().underrunCount = 5

        // 100 ms of playback exposure to converge the canceller, then 100 ms of speech to trip it.
        repeat(5) { capture.speak(silence()) }
        repeat(5) { capture.speak(speech()) }
        runCurrent()

        val log = loop.lastBargeIn.value!!
        assertEquals(rmsDbfs(speech()), log.levelDbfs, 0.001, "the level of the frame that fired the gate")
        assertTrue(log.converged, "the canceller had converged, so this trip is a person until shown otherwise")
        assertEquals(100L, log.exposureMillis)
        assertEquals(
            5,
            log.playbackUnderruns,
            "the count must be read before the speaker is stopped - after it, the track is gone and it is always null",
        )
    }

    // -- REQ-08: the endpoint-hold reopening ---------------------------------------------------

    @Test
    fun `a turn that reopens after dispatch supersedes the run - stopped, redispatched, never spoken`() =
        runTest {
            val loop = TestScopeLoop(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

            wake.fire()
            runCurrent()
            assertEquals(listOf(1), voice.turnsOpened, "a spoken turn opens a VoiceManager turn (D-18)")

            // VoiceManager calls the endpoint early - a provisional guess the speaker is done, and
            // the loop takes the head start rather than waiting out the hold (D-08).
            voice.hear(0, "what is the weather", endpointed = true)
            runCurrent()
            assertEquals(listOf("what is the weather"), agent.dispatched)

            // The first run starts answering; its tokens go out to be synthesised. No audio has
            // come back yet - the synthesiser is still working on the first sentence.
            agent.emit(HermesEvent.MessageDelta("run-1", "It is sunny. "))
            runCurrent()
            assertEquals(listOf(TtsFrame("req-1", "It is sunny. ", done = false)), voice.ttsOut)

            // The speaker was only drawing breath. The turn reopens under the SAME segment_index
            // and turn_id - the exact frames a segment-index watermark filters out as old (D-18).
            agent.runId = "run-2"
            voice.hear(0, "what is the weather in paris", endpointed = false)
            voice.hear(0, "what is the weather in paris tomorrow", endpointed = true, isFinal = true)
            runCurrent()

            // THE SUPERSEDE. The run in flight is answering a question that was taken back: stop
            // it, cancel its synthesis, and dispatch what was actually asked (REQ-08).
            assertEquals(listOf("run-1"), agent.stopped, "the superseded run must be stopped")
            assertEquals(listOf("req-1"), voice.ttsCancelled, "its synthesis must be cancelled")
            assertEquals(
                listOf("what is the weather", "what is the weather in paris tomorrow"),
                agent.dispatched,
                "the revised final text is dispatched as its own run",
            )
            assertEquals(listOf(1), voice.turnsOpened, "a supersede stays inside the same conversation turn")

            // Not a word of the first reply is ever spoken (REQ-08). Its late delta is not
            // forwarded, the request is never flushed with done, and its audio - arriving only
            // now, after the cancel - never opens a track: the sink hears none of it.
            agent.emit(HermesEvent.MessageDelta("run-1", "with a high of 25."))
            voice.emit(TtsEvent.Start("req-1", 22_050))
            voice.emit(TtsEvent.Audio(ByteArray(320), 22_050))
            voice.emit(TtsEvent.End("req-1"))
            runCurrent()
            assertTrue(
                voice.ttsOut.none { it.text.contains("high of 25") },
                "a token from the superseded run must never be sent to be spoken",
            )
            assertTrue(voice.ttsOut.none { it.requestId == "req-1" && it.done }, "the dead request is never flushed")
            assertTrue(sinks.isEmpty(), "zero audio frames attributable to the first run")
            assertEquals(ConsoleState.THINKING, loop.state.value, "the dead request's audio_end ends nothing")

            // The second run's reply is the one the console speaks, on a fresh request id.
            agent.emit(HermesEvent.MessageDelta("run-2", "Rain in Paris tomorrow."))
            agent.emit(HermesEvent.RunCompleted("run-2", output = "Rain in Paris tomorrow."))
            voice.emit(TtsEvent.Start("req-2", 22_050))
            voice.emit(TtsEvent.Audio(ByteArray(320), 22_050))
            voice.emit(TtsEvent.End("req-2"))
            runCurrent()

            assertEquals(
                listOf("Rain in Paris tomorrow."),
                voice.ttsOut.filter { it.requestId == "req-2" && !it.done }.map { it.text },
            )
            assertEquals(1, sinks.single().written.size, "the second run's reply is what plays")
            assertEquals("run-2", loop.turn.value.runId)
            assertEquals("what is the weather in paris tomorrow", loop.turn.value.prompt)
            assertEquals(ConsoleState.READY, loop.state.value)
        }

    @Test
    fun `a reopen while the first reply is already audible silences it mid-sentence`() = runTest {
        // The provisional head start means the console can already be speaking when the revision
        // lands. The supersede then behaves like a barge-in from the transcript's side: the track
        // is stopped immediately, and the abandoned reply's residual PCM has nowhere to land.
        val loop = TestScopeLoop(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        wake.fire()
        voice.hear(0, "what is the weather", endpointed = true)
        runCurrent()
        agent.emit(HermesEvent.MessageDelta("run-1", "It is sunny. "))
        voice.emit(TtsEvent.Start("req-1", 22_050))
        voice.emit(TtsEvent.Audio(ByteArray(320), 22_050))
        runCurrent()
        assertEquals(ConsoleState.SPEAKING, loop.state.value)
        assertEquals(1, sinks.single().written.size)

        agent.runId = "run-2"
        voice.hear(0, "what is the weather in paris tomorrow", endpointed = true, isFinal = true)
        runCurrent()

        assertEquals(1, sinks.single().stops, "the audible reply must stop mid-sentence")
        assertEquals(listOf("run-1"), agent.stopped)
        assertEquals(listOf("req-1"), voice.ttsCancelled)

        // Residual PCM of the cancelled request keeps arriving for a moment - none of it plays.
        voice.emit(TtsEvent.Audio(ByteArray(160), 22_050))
        voice.emit(TtsEvent.End("req-1"))
        runCurrent()
        assertEquals(1, sinks.single().written.size, "not one more byte of the superseded reply is spoken")
        assertEquals(ConsoleState.THINKING, loop.state.value, "the superseding run owns the turn now")
    }

    @Test
    fun `a provisional endpoint confirmed unchanged leaves the run alone`() = runTest {
        val loop = TestScopeLoop(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        wake.fire()
        voice.hear(0, "status report", endpointed = true)
        runCurrent()
        assertEquals(listOf("status report"), agent.dispatched)

        agent.emit(HermesEvent.MessageDelta("run-1", "All systems nominal."))
        runCurrent()

        // The hold expires and VoiceManager commits the same text, immutably. A confirmation is
        // not a revision: the head start was right, and taking the run down would throw it away.
        voice.hear(0, "status report", endpointed = true, isFinal = true)
        runCurrent()

        assertTrue(agent.stopped.isEmpty(), "a confirmation is not a revision")
        assertTrue(voice.ttsCancelled.isEmpty())
        assertEquals(listOf("status report"), agent.dispatched, "the run is not re-dispatched")

        agent.emit(HermesEvent.RunCompleted("run-1", output = "All systems nominal."))
        voice.emit(TtsEvent.Start("req-1", 22_050))
        voice.emit(TtsEvent.Audio(ByteArray(320), 22_050))
        voice.emit(TtsEvent.End("req-1"))
        runCurrent()
        assertEquals(ConsoleState.READY, loop.state.value)
    }

    // -- REQ-23: the session a turn lands in is read at dispatch, not fixed at construction ----

    @Test
    fun `each dispatch reads the current session - switching between turns is what the ready state does`() =
        runTest {
            var session = "session-a"
            val loop = TestScopeLoop(
                CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                sessionId = { session },
            )

            loop.say("remember the codeword")
            runCurrent()
            agent.emit(HermesEvent.RunCompleted("run-1", output = ""))
            runCurrent()
            assertEquals(ConsoleState.READY, loop.state.value)

            // The human taps another session on the ready screen between turns.
            session = "session-b"
            agent.runId = "run-2"

            loop.say("what was the codeword")
            runCurrent()

            assertEquals(listOf("session-a", "session-b"), agent.sessions)
        }

    @Test
    fun `a dispatch waits on the session provider - the visit's first turn lands in the session it makes`() =
        runTest {
            // The provider is suspend precisely so the composition root can make the session on
            // the visit's first dispatch (REQ-06): until it resolves, nothing goes to Hermes, and
            // what was said lands in the session it made - never in no session at all (REQ-01).
            val creating = CompletableDeferred<String>()
            val loop = TestScopeLoop(
                CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                sessionId = { creating.await() },
            )

            loop.say("open a channel")
            runCurrent()
            assertEquals(emptyList<String>(), agent.sessions, "nothing dispatches before the session exists")

            creating.complete("made-1")
            runCurrent()

            assertEquals(listOf("made-1"), agent.sessions)
            assertEquals(listOf("open a channel"), agent.dispatched)
        }

    // -- T-43: entering a session replaces what the wall shows ---------------------------------

    @Test
    fun `entering a session takes the finished turn off the wall`() = runTest {
        // The restored back-log carries this very turn's lines (the gateway stored them as they
        // were spoken), so a display that kept the turn as well would show its last exchange twice.
        val loop = TestScopeLoop(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        loop.say("what is the warp core status")
        runCurrent()
        agent.emit(HermesEvent.MessageDelta("run-1", "Stable. "))
        agent.emit(HermesEvent.RunCompleted("run-1", output = "Stable."))
        voice.emit(TtsEvent.Start("req-1", 22_050))
        voice.emit(TtsEvent.End("req-1"))
        runCurrent()
        assertEquals(ConsoleState.READY, loop.state.value)
        assertEquals("what is the warp core status", loop.turn.value.prompt)

        loop.clearTurn()

        assertEquals(TurnLog(), loop.turn.value, "the back-log replaces the finished turn, not joins it")
    }

    @Test
    fun `a turn in flight owns the wall - it cannot be cleared off it`() = runTest {
        val loop = TestScopeLoop(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        loop.say("run a level two diagnostic")
        runCurrent()
        assertEquals(ConsoleState.THINKING, loop.state.value)

        loop.clearTurn()

        assertEquals(
            "run a level two diagnostic",
            loop.turn.value.prompt,
            "a live turn is not display state to be discarded",
        )
    }

    // -- T-44: the pane accumulates the live session -------------------------------------------

    @Test
    fun `a new wake retires the finished exchange onto the spoken record, not off the wall`() = runTest {
        val loop = TestScopeLoop(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        // Turn one, completed: the exchange is the live display, and nothing is retired yet.
        wake.fire()
        voice.hear(0, "what is the warp core status", endpointed = true, isFinal = true)
        runCurrent()
        agent.emit(HermesEvent.MessageDelta("run-1", "Stable."))
        agent.emit(HermesEvent.RunCompleted("run-1", output = "Stable."))
        voice.emit(TtsEvent.Start("req-1", 22_050))
        voice.emit(TtsEvent.End("req-1"))
        runCurrent()
        assertEquals(ConsoleState.READY, loop.state.value)
        assertTrue(loop.spoken.value.isEmpty(), "the finished turn is still the live display")

        // The next wake. Before T-44 this wiped the wall back to STANDING BY mid-session; now the
        // exchange moves up into the spoken record and the new turn opens clean below it.
        agent.runId = "run-2"
        wake.fire(atMillis = 50)
        runCurrent()
        assertEquals(
            listOf(SpokenTurn(runId = "run-1", prompt = "what is the warp core status", reply = "Stable.")),
            loop.spoken.value,
            "a wake mid-session must keep the prior exchange on the wall",
        )
        assertEquals("", loop.turn.value.prompt, "the new turn opens clean")

        // Turn two completes; the third wake stacks both exchanges in spoken order.
        voice.hear(1, "and the deflector", endpointed = true, isFinal = true)
        runCurrent()
        agent.emit(HermesEvent.MessageDelta("run-2", "At full power."))
        agent.emit(HermesEvent.RunCompleted("run-2", output = "At full power."))
        voice.emit(TtsEvent.Start("req-2", 22_050))
        voice.emit(TtsEvent.End("req-2"))
        runCurrent()
        assertEquals(ConsoleState.READY, loop.state.value)

        agent.runId = "run-3"
        wake.fire(atMillis = 99)
        runCurrent()
        assertEquals(
            listOf("what is the warp core status", "and the deflector"),
            loop.spoken.value.map { it.prompt },
            "every finished exchange stays, in spoken order",
        )
        assertEquals(
            listOf("Stable.", "At full power."),
            loop.spoken.value.map { it.reply },
            "each finished exchange keeps its reply",
        )
    }

    @Test
    fun `a barge-in keeps the interrupted exchange on the wall, cut off where it was`() = runTest {
        val loop = TestScopeLoop(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        wake.fire()
        voice.hear(0, "what is the warp core status", endpointed = true)
        runCurrent()
        agent.emit(HermesEvent.MessageDelta("run-1", "The warp core is stable. "))
        voice.emit(TtsEvent.Start("req-1", 22_050))
        voice.emit(TtsEvent.Audio(ByteArray(320), 22_050))
        runCurrent()
        assertEquals(ConsoleState.SPEAKING, loop.state.value)

        agent.runId = "run-2"
        repeat(5) { capture.speak(speech()) }
        runCurrent()

        assertEquals(ConsoleState.LISTENING, loop.state.value)
        assertEquals(
            listOf(
                SpokenTurn(runId = "run-1", prompt = "what is the warp core status", reply = "The warp core is stable. "),
            ),
            loop.spoken.value,
            "the interrupted exchange is history, not debris - it stays, cut off where it was",
        )
    }

    @Test
    fun `a supersede revises the live exchange in place - nothing is retired`() = runTest {
        val loop = TestScopeLoop(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        wake.fire()
        voice.hear(0, "what is the weather", endpointed = true)
        runCurrent()
        agent.emit(HermesEvent.MessageDelta("run-1", "It is sunny. "))
        runCurrent()

        agent.runId = "run-2"
        voice.hear(0, "what is the weather in paris tomorrow", endpointed = true, isFinal = true)
        runCurrent()

        assertTrue(loop.spoken.value.isEmpty(), "a revision replaces the exchange; it does not append one")
        assertEquals("what is the weather in paris tomorrow", loop.turn.value.prompt)
    }

    @Test
    fun `a run that said nothing retires as a lone query line`() = runTest {
        val loop = TestScopeLoop(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        loop.say("do nothing")
        runCurrent()
        agent.emit(HermesEvent.RunFailed("run-1", error = "tool exploded"))
        runCurrent()
        assertEquals(ConsoleState.READY, loop.state.value)

        agent.runId = "run-2"
        loop.say("try again")
        runCurrent()

        assertEquals(
            listOf(SpokenTurn(runId = "run-1", prompt = "do nothing", reply = "")),
            loop.spoken.value,
            "what was asked stays on the record even when nothing came back",
        )
    }

    @Test
    fun `entering a session resets the accumulated conversation along with the live turn`() = runTest {
        // The fetched back-log carries these very lines (the gateway stored them as they were
        // spoken), so an accumulation that survived entry would show the whole session twice.
        val loop = TestScopeLoop(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        loop.say("remember the codeword INDIGO9")
        runCurrent()
        agent.emit(HermesEvent.MessageDelta("run-1", "OK."))
        agent.emit(HermesEvent.RunCompleted("run-1", output = "OK."))
        voice.emit(TtsEvent.Start("req-1", 22_050))
        voice.emit(TtsEvent.End("req-1"))
        runCurrent()

        agent.runId = "run-2"
        loop.say("what was it")
        runCurrent()
        assertFalse(loop.spoken.value.isEmpty(), "the typed turn retired its predecessor")
        agent.emit(HermesEvent.RunCompleted("run-2", output = ""))
        runCurrent()
        assertEquals(ConsoleState.READY, loop.state.value)

        loop.clearTurn()

        assertTrue(loop.spoken.value.isEmpty(), "the fetched back-log replaces the accumulation on entry")
        assertEquals(TurnLog(), loop.turn.value)
    }

    // -- REQ-24: the typed turn, with the capture path on the record ---------------------------

    @Test
    fun `a typed turn is a complete run with the capture path idle throughout, and the log says so`() =
        runTest {
            val loop = TestScopeLoop(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

            loop.say("run a level two diagnostic")
            runCurrent()

            assertEquals(listOf("run a level two diagnostic"), agent.dispatched)
            assertEquals(ConsoleState.THINKING, loop.state.value)
            assertNull(loop.turn.value.wakeAtMillis, "a typed turn has no wake word")
            assertTrue(voice.turnsOpened.isEmpty(), "a typed turn opens no STT turn - STT is excluded")

            // The reply streams and is spoken, exactly as a spoken turn's would be (REQ-24: the
            // debug path exercises the full Hermes -> TTS pipe, minus only the microphone half).
            agent.emit(HermesEvent.MessageDelta("run-1", "Diagnostic complete. "))
            agent.emit(HermesEvent.RunCompleted("run-1", output = "Diagnostic complete."))
            voice.emit(TtsEvent.Start("req-1", 22_050))
            voice.emit(TtsEvent.Audio(ByteArray(320), 22_050))
            voice.emit(TtsEvent.End("req-1"))
            runCurrent()

            assertEquals(1, sinks.single().written.size, "the typed turn's reply is played")
            assertEquals(ConsoleState.READY, loop.state.value)

            // THE ASSERTION (REQ-24): the capture path was idle for the whole turn, and the turn
            // log is the evidence - an agent-side failure reproduced this way excluded STT.
            assertFalse(
                loop.turn.value.captureEngaged,
                "a typed turn's log must show the capture path idle throughout",
            )
            assertTrue(voice.captured.isEmpty(), "not a byte of capture audio left the tablet")
        }

    @Test
    fun `capture audio flowing during a live turn marks the turn log engaged`() = runTest {
        // The contrapositive of the debug guarantee, and what keeps the flag honest: the log may
        // only claim an idle capture path when the path really was idle.
        val loop = TestScopeLoop(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        // Frames on an idle console mark nothing: there is no turn for them to belong to.
        capture.speak(silence())
        runCurrent()
        assertFalse(loop.turn.value.captureEngaged)

        wake.fire()
        runCurrent()
        capture.speak(silence())
        runCurrent()

        assertTrue(
            loop.turn.value.captureEngaged,
            "a frame through a live turn puts the capture path on the record",
        )
    }

    @Test
    fun `no capture audio leaves the tablet until the wake word lands`() = runTest {
        // REQ-01's other half: the wake word runs on-device, so a console nobody has spoken to is
        // not streaming the room to an STT server.
        val loop = TestScopeLoop(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        capture.speak(ByteArray(640) { 7 })
        runCurrent()
        assertTrue(voice.captured.isEmpty(), "an idle console sends no audio anywhere")
        assertEquals(ConsoleState.READY, loop.state.value)

        wake.fire()
        runCurrent()
        capture.speak(ByteArray(640) { 9 })
        runCurrent()

        assertEquals(1, voice.captured.size, "once woken, capture goes to STT")
        assertEquals(9, voice.captured.single()[0])
    }

    // -- REQ-39: listening opens and closes out loud; dispatch is deliberately silent ----------

    @Test
    fun `a spoken turn cues the open sequence at listen-open, silence at dispatch, and the close beep at turn end`() =
        runTest {
            val loop = TestScopeLoop(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

            wake.fire()
            runCurrent()
            assertEquals(
                listOf(LcarsCue.LISTENING_OPEN),
                cued,
                "one figure at listen-open - the wake's acknowledgement folds into it, fired on the STT turn's ack",
            )

            voice.hear(0, "status report", endpointed = true, isFinal = true)
            runCurrent()
            assertEquals(
                listOf(LcarsCue.LISTENING_OPEN),
                cued,
                "dispatch is deliberately silent - the beep after a spoken utterance is gone",
            )

            agent.emit(HermesEvent.MessageDelta("run-1", "Acknowledged. "))
            agent.emit(HermesEvent.RunCompleted("run-1", output = "Acknowledged."))
            voice.emit(TtsEvent.Start("req-1", 22_050))
            voice.emit(TtsEvent.End("req-1"))
            runCurrent()

            assertEquals(ConsoleState.READY, loop.state.value)
            assertEquals(
                listOf(LcarsCue.LISTENING_OPEN, LcarsCue.LISTENING_CLOSED),
                cued,
                "the close beep is the console stopping listening, at the turn's end and nowhere else",
            )
        }

    @Test
    fun `a failed run speaks its error voice, and the turn still closes out loud`() = runTest {
        val loop = TestScopeLoop(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        wake.fire()
        voice.hear(0, "status report", endpointed = true, isFinal = true)
        runCurrent()

        agent.emit(HermesEvent.RunFailed("run-1", error = "core breach"))
        runCurrent()

        assertEquals(ConsoleState.READY, loop.state.value)
        assertEquals(
            listOf(LcarsCue.LISTENING_OPEN, LcarsCue.ERROR, LcarsCue.LISTENING_CLOSED),
            cued,
            "a dead run is audible - and the console still stops listening out loud after it",
        )
    }

    // -- T-50: a dead Hermes ends as the error voice at READY, never a process death (REQ-39/26) --

    @Test
    fun `a session that cannot be created fails the turn as the error voice - nothing dispatches unscoped`() =
        runTest {
            // REQ-12: the factory behind the provider is the composition root's createSession, and
            // a gateway that refuses it surfaces here as the provider throwing. The turn must die
            // on the existing error path - state, cue, turn log - with dispatch never reached:
            // there is no unscoped fallback left to reach it with (REQ-03).
            val loop = TestScopeLoop(
                CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                sessionId = { throw IOException("session create refused") },
            )

            wake.fire()
            voice.hear(0, "status report", endpointed = true, isFinal = true)
            runCurrent()

            assertEquals(ConsoleState.READY, loop.state.value, "a failed creation returns the console to READY")
            assertEquals(
                listOf(LcarsCue.LISTENING_OPEN, LcarsCue.ERROR, LcarsCue.LISTENING_CLOSED),
                cued,
                "a session that cannot be made is the error voice, then the console stops listening out loud",
            )
            assertEquals("session create refused", loop.turn.value.error, "the failure is on the turn log")
            assertTrue(agent.dispatched.isEmpty(), "no run was dispatched - with a session or without one")
        }

    @Test
    fun `Hermes unreachable at dispatch ends the turn as the error voice at READY, not a dead process`() =
        runTest {
            // REQ-26's no-death-in-logcat clause: the dispatch that used to throw straight out of the
            // turn coroutine and take the process with it. Now it is a fault the room hears and the
            // console recovers from - the failure that the whole green suite missed because no fake
            // ever refused to dispatch.
            val loop = TestScopeLoop(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

            agent.dispatchError = IOException("connection refused")
            wake.fire()
            voice.hear(0, "status report", endpointed = true, isFinal = true)
            runCurrent()

            // The turn ended, out loud and at READY - not a coroutine that crashed the loop.
            assertEquals(ConsoleState.READY, loop.state.value, "a dead dispatch returns the console to READY")
            assertEquals(
                listOf(LcarsCue.LISTENING_OPEN, LcarsCue.ERROR, LcarsCue.LISTENING_CLOSED),
                cued,
                "an unreachable Hermes is the error voice, then the console stops listening out loud",
            )
            assertEquals("connection refused", loop.turn.value.error, "the failure is on the turn log")
            assertTrue(agent.dispatched.isEmpty(), "nothing was dispatched - the connection never came up")

            // And the loop is alive, not wedged: the next turn works when Hermes comes back.
            agent.dispatchError = null
            agent.runId = "run-2"
            wake.fire(atMillis = 99)
            voice.hear(1, "try again", endpointed = true, isFinal = true)
            runCurrent()
            agent.emit(HermesEvent.MessageDelta("run-2", "Back online."))
            agent.emit(HermesEvent.RunCompleted("run-2", output = "Back online."))
            voice.emit(TtsEvent.Start("req-2", 22_050))
            voice.emit(TtsEvent.End("req-2"))
            runCurrent()
            assertEquals(listOf("try again"), agent.dispatched, "the loop survived the failure and dispatched the next turn")
            assertEquals(ConsoleState.READY, loop.state.value)
        }

    @Test
    fun `the event stream dropping mid-reply ends the turn as the error voice at READY`() = runTest {
        // The other half of REQ-26's clause: a run that dispatched and started speaking, then had its
        // SSE stream drop under it. The collect throws where before it took the process down; now the
        // console yields the reply it can no longer finish and says so.
        val loop = TestScopeLoop(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        wake.fire()
        voice.hear(0, "what is the warp core status", endpointed = true, isFinal = true)
        runCurrent()
        agent.emit(HermesEvent.MessageDelta("run-1", "The warp core is "))
        voice.emit(TtsEvent.Start("req-1", 22_050))
        voice.emit(TtsEvent.Audio(ByteArray(320), 22_050))
        runCurrent()
        assertEquals(ConsoleState.SPEAKING, loop.state.value, "the console is mid-reply when the stream dies")

        // The stream drops. The buffered delta above already spoke; the drop rethrows after it.
        agent.failStream("run-1", IOException("stream closed"))
        runCurrent()

        assertEquals(ConsoleState.READY, loop.state.value, "a dropped stream returns the console to READY")
        assertTrue(cued.contains(LcarsCue.ERROR), "the dropped run is audible")
        assertEquals(LcarsCue.LISTENING_CLOSED, cued.last(), "and the console stops listening out loud after it")
        assertEquals("stream closed", loop.turn.value.error, "the failure is on the turn log")
        assertEquals(
            listOf("req-1"),
            voice.ttsCancelled,
            "the synthesiser is told to stop making a reply whose run just died",
        )

        // The loop recovers: a fresh turn runs to completion.
        agent.runId = "run-2"
        wake.fire(atMillis = 99)
        voice.hear(1, "again", endpointed = true, isFinal = true)
        runCurrent()
        agent.emit(HermesEvent.RunCompleted("run-2", output = ""))
        runCurrent()
        assertEquals(ConsoleState.READY, loop.state.value, "the loop survived the mid-stream failure")
    }

    @Test
    fun `a typed turn is silent end to end - the console never listened, so nothing opens and nothing closes`() =
        runTest {
            val loop = TestScopeLoop(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

            loop.say("run a diagnostic")
            runCurrent()
            agent.emit(HermesEvent.MessageDelta("run-1", "Running. "))
            agent.emit(HermesEvent.RunCompleted("run-1", output = "Running."))
            voice.emit(TtsEvent.Start("req-1", 22_050))
            voice.emit(TtsEvent.End("req-1"))
            runCurrent()

            assertEquals(ConsoleState.READY, loop.state.value)
            assertTrue(
                cued.isEmpty(),
                "REQ-39's vocabulary has no voice for a typed turn: no open, no dispatch cue, no close - got $cued",
            )
        }

    @Test
    fun `a barge-in is acknowledged out loud, then the new turn's listening opens`() = runTest {
        val loop = TestScopeLoop(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        // A turn, up to the point where the console is talking.
        wake.fire()
        voice.hear(0, "what is the warp core status", endpointed = true)
        runCurrent()
        agent.emit(HermesEvent.MessageDelta("run-1", "The warp core is stable. "))
        voice.emit(TtsEvent.Start("req-1", 22_050))
        voice.emit(TtsEvent.Audio(ByteArray(320), 22_050))
        runCurrent()
        assertEquals(ConsoleState.SPEAKING, loop.state.value)

        cued.clear()
        agent.runId = "run-2"
        repeat(5) { capture.speak(speech()) }
        runCurrent()

        assertEquals(
            listOf(LcarsCue.BARGE_IN, LcarsCue.LISTENING_OPEN),
            cued,
            "the yield is audible the moment the console shuts up, then the interrupter's turn opens - " +
                "and no close beep anywhere: the console never stopped listening",
        )
        assertEquals(ConsoleState.LISTENING, loop.state.value)
    }

    @Test
    fun `a completed run cues no error, and a cue sink that throws cannot break the loop`() = runTest {
        val loop = TestScopeLoop(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        wake.fire()
        voice.hear(0, "all stop", endpointed = true, isFinal = true)
        runCurrent()

        // The player dies mid-turn. A cue is decoration: the turn must complete regardless.
        cueFailure = IllegalStateException("track released")
        agent.emit(HermesEvent.MessageDelta("run-1", "Answering. "))
        agent.emit(HermesEvent.RunCompleted("run-1", output = "Answering."))
        voice.emit(TtsEvent.Start("req-1", 22_050))
        voice.emit(TtsEvent.End("req-1"))
        runCurrent()

        assertEquals(ConsoleState.READY, loop.state.value, "the turn survived the dead cue sink")
        assertFalse(cued.contains(LcarsCue.ERROR), "a clean completion has no error voice")
    }

    // -- REQ-40: the follow-up window ----------------------------------------------------------

    @Test
    fun `after a spoken reply the console keeps listening - a follow-up is the next turn, same session, no wake word`() =
        runTest {
            val loop = TestScopeLoop(
                CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                sessionId = { "session-a" },
                followUpWindowMillis = { 10_000 },
            )

            wake.fire()
            runCurrent()
            voice.hear(0, "what time is it", endpointed = true, isFinal = true)
            runCurrent()
            agent.emit(HermesEvent.MessageDelta("run-1", "It is 0900."))
            agent.emit(HermesEvent.RunCompleted("run-1", output = "It is 0900."))
            voice.emit(TtsEvent.Start("req-1", 22_050))
            voice.emit(TtsEvent.End("req-1"))
            runCurrent()

            // THE WINDOW. The reply is done and the console has not retreated behind the wake
            // word: it is listening, the mic still streams to STT, and the window's own fresh
            // STT turn fences the finished reply's tail out of the transcript (D-18).
            assertEquals(ConsoleState.LISTENING, loop.state.value, "a finished reply keeps the floor open")
            assertEquals(listOf(1, 2), voice.turnsOpened, "the window is its own STT turn")
            capture.speak(silence())
            runCurrent()
            assertEquals(1, voice.captured.size, "the open window streams capture to STT")

            // A follow-up mid-window - and no second wake ever fires. It dispatches as the next
            // turn in the same session, and the finished exchange retires onto the record (T-44).
            agent.runId = "run-2"
            advanceTimeBy(3_000)
            runCurrent()
            voice.hear(1, "and in tokyo", endpointed = true, isFinal = true)
            runCurrent()
            assertEquals(listOf("what time is it", "and in tokyo"), agent.dispatched)
            assertEquals(listOf("session-a", "session-a"), agent.sessions, "the follow-up stays in the session")
            assertEquals(ConsoleState.THINKING, loop.state.value)
            assertEquals(
                listOf(SpokenTurn(runId = "run-1", prompt = "what time is it", reply = "It is 0900.")),
                loop.spoken.value,
                "the follow-up takes the wall; the finished exchange joins the spoken record",
            )
            assertEquals("and in tokyo", loop.turn.value.prompt)

            // The second reply completes - and the window re-arms (REQ-40).
            agent.emit(HermesEvent.MessageDelta("run-2", "It is 1700 in Tokyo."))
            agent.emit(HermesEvent.RunCompleted("run-2", output = "It is 1700 in Tokyo."))
            voice.emit(TtsEvent.Start("req-2", 22_050))
            voice.emit(TtsEvent.End("req-2"))
            runCurrent()
            assertEquals(ConsoleState.LISTENING, loop.state.value, "the window re-arms after each exchange")
            assertEquals(listOf(1, 2, 3), voice.turnsOpened)

            // Silence: the window closes and the console is wake-gated again.
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(ConsoleState.READY, loop.state.value)
            assertEquals(
                listOf(
                    LcarsCue.LISTENING_OPEN,
                    LcarsCue.LISTENING_OPEN,
                    LcarsCue.LISTENING_OPEN,
                    LcarsCue.LISTENING_CLOSED,
                ),
                cued,
                "listening opened out loud three times - the wake, the window, the re-armed window - " +
                    "and closed exactly once, when the console really stopped",
            )
        }

    @Test
    fun `silence closes the window at the configured length - and the knob is read fresh at each reply`() =
        runTest {
            var windowMillis = 5_000L
            val loop = TestScopeLoop(
                CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                followUpWindowMillis = { windowMillis },
            )

            wake.fire()
            runCurrent()
            voice.hear(0, "what time is it", endpointed = true, isFinal = true)
            runCurrent()
            agent.emit(HermesEvent.MessageDelta("run-1", "It is 0900."))
            agent.emit(HermesEvent.RunCompleted("run-1", output = "It is 0900."))
            voice.emit(TtsEvent.Start("req-1", 22_050))
            voice.emit(TtsEvent.End("req-1"))
            runCurrent()
            assertEquals(ConsoleState.LISTENING, loop.state.value)

            // One tick short of the window: still listening, still nothing closed.
            advanceTimeBy(4_999)
            runCurrent()
            assertEquals(ConsoleState.LISTENING, loop.state.value)
            assertFalse(cued.contains(LcarsCue.LISTENING_CLOSED))

            // The window's end: the close beep, READY, and not a byte more off-device.
            advanceTimeBy(1)
            runCurrent()
            assertEquals(ConsoleState.READY, loop.state.value, "silence closes at the configured length")
            assertEquals(LcarsCue.LISTENING_CLOSED, cued.last())
            capture.speak(silence())
            runCurrent()
            assertTrue(voice.captured.isEmpty(), "a closed window streams nothing off-device")
            assertEquals("what time is it", loop.turn.value.prompt, "an unanswered window retires nothing")

            // The knob turns between exchanges: the very next window observes the new length,
            // with nothing rebuilt - the length is read at the reply's end, not at construction.
            windowMillis = 2_000L
            agent.runId = "run-2"
            wake.fire(atMillis = 50)
            runCurrent()
            voice.hear(1, "and in tokyo", endpointed = true, isFinal = true)
            runCurrent()
            agent.emit(HermesEvent.MessageDelta("run-2", "It is 1700."))
            agent.emit(HermesEvent.RunCompleted("run-2", output = "It is 1700."))
            voice.emit(TtsEvent.Start("req-2", 22_050))
            voice.emit(TtsEvent.End("req-2"))
            runCurrent()
            assertEquals(ConsoleState.LISTENING, loop.state.value)
            advanceTimeBy(1_999)
            runCurrent()
            assertEquals(ConsoleState.LISTENING, loop.state.value)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(ConsoleState.READY, loop.state.value, "the changed knob is the observed window")
        }

    // -- REQ-21: the grace beat before the follow-up cue ---------------------------------------

    @Test
    fun `the follow-up cue waits a grace beat after the reply's last audio - the floor does not`() = runTest {
        // The cue used to sound in the same instant playback stopped, which in the room reads as
        // the console demanding the next question before the reply has landed. The beat delays
        // only the announcement: the window's STT turn opens immediately, because a floor that
        // waited out the grace would stamp a fast reply with the finished turn's id and fence it
        // out - the very lost-reply shape T-24 exists to close.
        val loop = TestScopeLoop(
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            followUpWindowMillis = { 10_000 },
            followUpCueGraceMillis = 600,
        )

        wake.fire()
        runCurrent()
        voice.hear(0, "what time is it", endpointed = true, isFinal = true)
        runCurrent()
        agent.emit(HermesEvent.MessageDelta("run-1", "It is 0900."))
        agent.emit(HermesEvent.RunCompleted("run-1", output = "It is 0900."))
        voice.emit(TtsEvent.Start("req-1", 22_050))
        voice.emit(TtsEvent.End("req-1"))
        runCurrent()

        // The floor is open the moment the reply ends: state, STT turn and capture all say so.
        assertEquals(ConsoleState.LISTENING, loop.state.value, "the window opens at the reply's end")
        assertEquals(listOf(1, 2), voice.turnsOpened, "the window's STT turn does not wait out the beat")
        capture.speak(silence())
        runCurrent()
        assertEquals(1, voice.captured.size, "capture streams through the beat - only the announcement waits")

        // One tick short of the grace: the room is still quiet.
        advanceTimeBy(599)
        runCurrent()
        assertEquals(listOf(LcarsCue.LISTENING_OPEN), cued, "no second open cue inside the beat")

        // The beat ends: the open figure sounds, and the window is announced.
        advanceTimeBy(1)
        runCurrent()
        assertEquals(
            listOf(LcarsCue.LISTENING_OPEN, LcarsCue.LISTENING_OPEN),
            cued,
            "the follow-up cue sounds exactly a grace beat after playback end",
        )
        assertEquals(ConsoleState.LISTENING, loop.state.value)
    }

    @Test
    fun `a reply spoken into the grace beat is heard - dispatched when the beat ends, never fenced out`() =
        runTest {
            // The property the immediate STT turn buys: somebody answering on the reply's heels is
            // already inside the new turn, so nothing they said is stamped old and dropped. The
            // dispatch simply waits out the beat with the cue.
            val loop = TestScopeLoop(
                CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                followUpWindowMillis = { 10_000 },
                followUpCueGraceMillis = 600,
            )

            wake.fire()
            runCurrent()
            voice.hear(0, "what time is it", endpointed = true, isFinal = true)
            runCurrent()
            agent.emit(HermesEvent.MessageDelta("run-1", "It is 0900."))
            agent.emit(HermesEvent.RunCompleted("run-1", output = "It is 0900."))
            voice.emit(TtsEvent.Start("req-1", 22_050))
            voice.emit(TtsEvent.End("req-1"))
            runCurrent()
            assertEquals(ConsoleState.LISTENING, loop.state.value)

            // Mid-beat, the follow-up lands, endpointed and final - and holds until the beat ends.
            agent.runId = "run-2"
            advanceTimeBy(300)
            runCurrent()
            voice.hear(1, "and in tokyo", endpointed = true, isFinal = true)
            runCurrent()
            assertEquals(listOf("what time is it"), agent.dispatched, "the beat is not over yet")

            advanceTimeBy(300)
            runCurrent()
            assertEquals(
                listOf("what time is it", "and in tokyo"),
                agent.dispatched,
                "the utterance spoken into the beat dispatches whole when it ends",
            )
            assertEquals(ConsoleState.THINKING, loop.state.value)
        }

    @Test
    fun `a zero follow-up window with a grace configured still opens no floor and sounds no cue`() = runTest {
        // REQ-21's second clause: the beat must not conjure a window out of a knob set to zero -
        // the turn closes at the reply's end exactly as it always did, and no cue arrives later.
        val loop = TestScopeLoop(
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            followUpWindowMillis = { 0 },
            followUpCueGraceMillis = 600,
        )

        wake.fire()
        runCurrent()
        voice.hear(0, "what time is it", endpointed = true, isFinal = true)
        runCurrent()
        agent.emit(HermesEvent.MessageDelta("run-1", "It is 0900."))
        agent.emit(HermesEvent.RunCompleted("run-1", output = "It is 0900."))
        voice.emit(TtsEvent.Start("req-1", 22_050))
        voice.emit(TtsEvent.End("req-1"))
        runCurrent()

        assertEquals(ConsoleState.READY, loop.state.value, "a zero window closes the turn at the reply's end")
        assertEquals(listOf(1), voice.turnsOpened, "no window, no second STT turn")
        assertEquals(listOf(LcarsCue.LISTENING_OPEN, LcarsCue.LISTENING_CLOSED), cued)

        // And nothing sounds after the beat would have elapsed - there is no window to announce.
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(LcarsCue.LISTENING_OPEN, LcarsCue.LISTENING_CLOSED), cued)
        assertEquals(ConsoleState.READY, loop.state.value)
    }

    @Test
    fun `a wake that yields no endpointed utterance is back to READY within 15 s, out loud`() = runTest {
        val loop = TestScopeLoop(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        wake.fire()
        runCurrent()
        assertEquals(ConsoleState.LISTENING, loop.state.value)

        // Nearly fifteen seconds of nothing but an unendpointed mumble: the floor stays open.
        voice.hear(0, "uh", endpointed = false)
        advanceTimeBy(14_999)
        runCurrent()
        assertEquals(ConsoleState.LISTENING, loop.state.value)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(ConsoleState.READY, loop.state.value, "an unanswered wake is bounded (REQ-40)")
        assertEquals(listOf(LcarsCue.LISTENING_OPEN, LcarsCue.LISTENING_CLOSED), cued)
        assertTrue(agent.dispatched.isEmpty(), "nothing was said, so nothing dispatches")
        capture.speak(silence())
        runCurrent()
        assertTrue(voice.captured.isEmpty(), "closed means no capture leaves the tablet")

        // And the console is wake-gated again, not wedged: the next wake opens a fresh turn.
        wake.fire(atMillis = 99)
        runCurrent()
        assertEquals(ConsoleState.LISTENING, loop.state.value)
        assertEquals(listOf(1, 2), voice.turnsOpened)
    }

    // -- T-24 / REQ-22: the window bounds silence, not speech ----------------------------------

    @Test
    fun `partials arriving past the wake window's length hold the floor - the endpoint dispatches the whole thought`() =
        runTest {
            // The inbox bug, in its exact shape: the 15 s bound used to cover the whole utterance,
            // so anyone still talking at the bound was cut off mid-sentence - the close beep, READY,
            // and the transcript dropped. The bound's job is a floor nobody is taking (REQ-40's
            // privacy half); while the transcript is moving, somebody is plainly taking it.
            val loop = TestScopeLoop(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

            wake.fire()
            runCurrent()
            assertEquals(ConsoleState.LISTENING, loop.state.value)

            voice.hear(0, "set a course", endpointed = false)
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(ConsoleState.LISTENING, loop.state.value, "a moving transcript holds the floor")

            voice.hear(0, "set a course for andoria", endpointed = false)
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()

            // Twenty seconds in - five past the old bound - and still mid-sentence, still open.
            assertEquals(
                ConsoleState.LISTENING,
                loop.state.value,
                "someone mid-sentence is never cut off by the window's timeout",
            )
            assertEquals(listOf(LcarsCue.LISTENING_OPEN), cued, "no close beep over a person still talking")

            voice.hear(0, "set a course for andoria warp six", endpointed = true, isFinal = true)
            runCurrent()
            assertEquals(
                listOf("set a course for andoria warp six"),
                agent.dispatched,
                "the utterance dispatches whole, exactly as it was finally heard",
            )
            assertEquals(ConsoleState.THINKING, loop.state.value)
        }

    @Test
    fun `the follow-up window shares the mechanism - revisions past the knob's length keep it open`() =
        runTest {
            val loop = TestScopeLoop(
                CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                followUpWindowMillis = { 5_000 },
            )

            wake.fire()
            runCurrent()
            voice.hear(0, "what time is it", endpointed = true, isFinal = true)
            runCurrent()
            agent.emit(HermesEvent.MessageDelta("run-1", "It is 0900."))
            agent.emit(HermesEvent.RunCompleted("run-1", output = "It is 0900."))
            voice.emit(TtsEvent.Start("req-1", 22_050))
            voice.emit(TtsEvent.End("req-1"))
            runCurrent()
            assertEquals(ConsoleState.LISTENING, loop.state.value, "the follow-up window is open")

            agent.runId = "run-2"
            voice.hear(1, "and", endpointed = false)
            runCurrent()
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals(ConsoleState.LISTENING, loop.state.value)

            voice.hear(1, "and in", endpointed = false)
            runCurrent()
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals(
                ConsoleState.LISTENING,
                loop.state.value,
                "eight seconds into a five-second knob, the mover holds the window",
            )

            voice.hear(1, "and in tokyo", endpointed = true, isFinal = true)
            runCurrent()
            assertEquals(listOf("what time is it", "and in tokyo"), agent.dispatched)
            assertEquals(ConsoleState.THINKING, loop.state.value)
        }

    @Test
    fun `a thought abandoned mid-sentence closes the floor one whole window after its last movement`() = runTest {
        // The bound is not gone, it is re-anchored: silence is measured from the last revision,
        // so a mumble that trails off still closes - the close cue, READY, nothing dispatched -
        // exactly one windowful of quiet later. This is REQ-40's open-microphone bound surviving
        // REQ-22 intact.
        val loop = TestScopeLoop(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        wake.fire()
        runCurrent()
        voice.hear(0, "set a", endpointed = false)
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        voice.hear(0, "set a course", endpointed = false)
        runCurrent()

        // 14 999 ms after the last movement: still open.
        advanceTimeBy(14_999)
        runCurrent()
        assertEquals(ConsoleState.LISTENING, loop.state.value)

        // One more tick makes a full window of silence since anyone last said anything.
        advanceTimeBy(1)
        runCurrent()
        assertEquals(ConsoleState.READY, loop.state.value, "silence since the last movement is what closes")
        assertEquals(LcarsCue.LISTENING_CLOSED, cued.last(), "and it closes out loud")
        assertTrue(agent.dispatched.isEmpty(), "a thought never endpointed dispatches nothing")
        capture.speak(silence())
        runCurrent()
        assertTrue(voice.captured.isEmpty(), "closed means no capture leaves the tablet")
    }

    // -- T-25 / REQ-23: speech at the microphone holds the floor, under an absolute ceiling ------

    @Test
    fun `speech at the microphone holds the window open while the transcript never moves`() = runTest {
        // The wall falsified T-24's signal: the shipped STT sidecar emits nothing mid-utterance -
        // it batches and decodes only after an 800 ms pause - so a continuous talker produces zero
        // transcript movement and the re-arm never fired. What does exist while they talk is their
        // voice on the capture stream, and the same measured -35 dBFS gate barge-in trusts.
        val loop = TestScopeLoop(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        wake.fire()
        runCurrent()
        assertEquals(ConsoleState.LISTENING, loop.state.value)

        repeat(3) {
            word()
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(ConsoleState.LISTENING, loop.state.value, "an audible speaker holds the floor")
        }

        // Thirty seconds in - double the old bound - the pause finally comes, the sidecar flushes,
        // and the whole thought dispatches.
        voice.hear(0, "set a course for andoria warp six", endpointed = true, isFinal = true)
        runCurrent()
        assertEquals(listOf("set a course for andoria warp six"), agent.dispatched)
        assertEquals(ConsoleState.THINKING, loop.state.value)
        assertEquals(listOf(LcarsCue.LISTENING_OPEN), cued, "no close beep ever sounded over the speaker")
    }

    @Test
    fun `the follow-up window is held by speech the same way`() = runTest {
        val loop = TestScopeLoop(
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            followUpWindowMillis = { 5_000 },
        )

        wake.fire()
        runCurrent()
        voice.hear(0, "what time is it", endpointed = true, isFinal = true)
        runCurrent()
        agent.emit(HermesEvent.MessageDelta("run-1", "It is 0900."))
        agent.emit(HermesEvent.RunCompleted("run-1", output = "It is 0900."))
        voice.emit(TtsEvent.Start("req-1", 22_050))
        voice.emit(TtsEvent.End("req-1"))
        runCurrent()
        assertEquals(ConsoleState.LISTENING, loop.state.value, "the follow-up window is open")

        agent.runId = "run-2"
        repeat(2) {
            word()
            runCurrent()
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals(ConsoleState.LISTENING, loop.state.value, "the follow-up shares the mechanism")
        }

        // Eight seconds into a five-second knob, held by voice alone; the pause lands the flush.
        voice.hear(1, "and in tokyo", endpointed = true, isFinal = true)
        runCurrent()
        assertEquals(listOf("what time is it", "and in tokyo"), agent.dispatched)
        assertEquals(ConsoleState.THINKING, loop.state.value)
    }

    @Test
    fun `what never stops making noise cannot hold the microphone open forever`() = runTest {
        // REQ-40's privacy bound, surviving REQ-23: the re-arm trusts an energy gate, and a noise
        // source above it that never pauses - a vacuum at the wrong distance, a loud room - must
        // not stream the room to the STT server indefinitely. The ceiling is the window's own
        // bound plus a minute of headroom - 75 s for the 15 s wake window - and it is not
        // re-armable: it closes the floor whatever the gate is saying.
        val loop = TestScopeLoop(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        wake.fire()
        runCurrent()

        repeat(7) {
            word()
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(ConsoleState.LISTENING, loop.state.value)
        }

        // t = 70 s and the noise re-arms once more - but the ceiling at 75 s is not re-armable.
        word()
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(ConsoleState.READY, loop.state.value, "the ceiling closes what the gate cannot")
        assertEquals(LcarsCue.LISTENING_CLOSED, cued.last(), "out loud, like any close")
        assertTrue(agent.dispatched.isEmpty(), "noise is not a turn")
        voice.captured.clear()
        capture.speak(silence())
        runCurrent()
        assertTrue(voice.captured.isEmpty(), "closed means no capture leaves the tablet")
    }

    @Test
    fun `a long follow-up knob is never truncated by the ceiling - silence closes at the configured bound`() =
        runTest {
            // The ceiling is headroom past the bound, not a flat cap: the knob parses up to 120 s,
            // and a flat minute over this window would close a silent floor at half its configured
            // length with nothing anywhere saying so.
            val loop = TestScopeLoop(
                CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                followUpWindowMillis = { 120_000 },
            )

            wake.fire()
            runCurrent()
            voice.hear(0, "what time is it", endpointed = true, isFinal = true)
            runCurrent()
            agent.emit(HermesEvent.MessageDelta("run-1", "It is 0900."))
            agent.emit(HermesEvent.RunCompleted("run-1", output = "It is 0900."))
            voice.emit(TtsEvent.Start("req-1", 22_050))
            voice.emit(TtsEvent.End("req-1"))
            runCurrent()
            assertEquals(ConsoleState.LISTENING, loop.state.value, "the two-minute window is open")

            advanceTimeBy(119_999)
            runCurrent()
            assertEquals(ConsoleState.LISTENING, loop.state.value, "the knob's whole length is honoured")

            advanceTimeBy(1)
            runCurrent()
            assertEquals(ConsoleState.READY, loop.state.value, "silence closes at the configured bound")
            assertEquals(LcarsCue.LISTENING_CLOSED, cued.last())
        }

    // -- T-51: a turn_start that never acks cannot wedge the console -----------------------------

    @Test
    fun `a wake whose turn_start is never acked - the link down - is bounded back to READY within 15 s`() =
        runTest {
            // The link is down at wake, or the socket drops between the turn_start frame and the
            // turn_started ack. Before T-51 turnStart() suspended outside the window's bound, so
            // the console latched LISTENING forever with capture streaming off-device to a server
            // that never opened a turn - the very privacy bug REQ-40's 15 s bound exists to prevent.
            val loop = TestScopeLoop(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            voice.suspendTurnStart = true

            wake.fire()
            runCurrent()
            assertEquals(ConsoleState.LISTENING, loop.state.value, "the wake latched listening")
            capture.speak(silence())
            runCurrent()
            assertEquals(1, voice.captured.size, "capture is streaming off-device while it waits for the ack")

            // One tick short of the bound: the un-acked floor is still held open.
            advanceTimeBy(14_999)
            runCurrent()
            assertEquals(ConsoleState.LISTENING, loop.state.value)

            // The bound: the close cue, READY, and not a byte more off-device.
            advanceTimeBy(1)
            runCurrent()
            assertEquals(ConsoleState.READY, loop.state.value, "an un-acked turn_start is bounded (REQ-40)")
            assertEquals(LcarsCue.LISTENING_CLOSED, cued.last(), "the close cue played")
            voice.captured.clear()
            capture.speak(silence())
            runCurrent()
            assertTrue(voice.captured.isEmpty(), "streamingToStt is off - capture no longer leaves the tablet")

            // Not wedged: with the link back, the next wake opens a fresh turn.
            voice.suspendTurnStart = false
            wake.fire(atMillis = 99)
            runCurrent()
            assertEquals(ConsoleState.LISTENING, loop.state.value)
        }

    @Test
    fun `a redial cancelling the pending turn_start ack closes listening, it does not silently latch it`() =
        runTest {
            // The socket drops between wake and the turn_started ack and the client redials; its
            // drainStaleState cancels the pending turn_start (REQ-10). That cancellation surfaces in
            // the turn coroutine as a CancellationException even though the turn is still alive -
            // and left to fall through startTurn's cancellation re-throw it would silently kill the
            // coroutine with LISTENING latched and capture still streaming off-device to nobody.
            val loop = TestScopeLoop(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            voice.suspendTurnStart = true

            wake.fire()
            runCurrent()
            assertEquals(ConsoleState.LISTENING, loop.state.value)

            // The redial cancels the pending ack, well within the 15 s window.
            voice.cancelPendingTurnStart()
            runCurrent()

            assertEquals(
                ConsoleState.READY,
                loop.state.value,
                "a cancelled ack closes listening; it must not silently latch LISTENING",
            )
            assertEquals(LcarsCue.LISTENING_CLOSED, cued.last(), "the close cue played")
            capture.speak(silence())
            runCurrent()
            assertTrue(voice.captured.isEmpty(), "streamingToStt is off - capture no longer leaves the tablet")

            // Not wedged: with the link back up, the next wake opens a fresh turn.
            voice.suspendTurnStart = false
            agent.runId = "run-2"
            wake.fire(atMillis = 99)
            runCurrent()
            assertEquals(ConsoleState.LISTENING, loop.state.value)
        }

    // -- REQ-19: ending a visit aborts whatever the loop is doing ------------------------------

    @Test
    fun `abort closes an open listening window - READY, silent, and later speech dispatches nothing`() =
        runTest {
            // The visit ends while the floor is open (REQ-19): the root's endVisit calls the seam
            // (T-20), and the window must genuinely die - a collector that survived would dispatch
            // the next endpointed utterance into the session everyone just left.
            val loop = TestScopeLoop(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

            wake.fire()
            runCurrent()
            assertEquals(ConsoleState.LISTENING, loop.state.value)

            loop.abort()
            runCurrent()

            assertEquals(ConsoleState.READY, loop.state.value, "the abort returns the loop to READY")
            capture.speak(silence())
            runCurrent()
            assertTrue(voice.captured.isEmpty(), "capture no longer leaves the tablet")

            // What was being said lands only now, endpointed and final - into a window that is
            // gone. The turn coroutine was cancelled, so there is no collector left to believe it.
            voice.hear(0, "computer are you still there", endpointed = true, isFinal = true)
            runCurrent()
            assertTrue(agent.dispatched.isEmpty(), "a transcript after the abort dispatches nothing")
            assertEquals(ConsoleState.READY, loop.state.value)

            // No close beep: the departure has its own voice at the root (T-20), and the loop
            // announcing it too would sound one ending twice.
            assertEquals(listOf(LcarsCue.LISTENING_OPEN), cued, "the abort itself is silent")

            // Not wedged: the next visit's wake opens a fresh turn.
            wake.fire(atMillis = 99)
            runCurrent()
            assertEquals(ConsoleState.LISTENING, loop.state.value)
            assertEquals(listOf(1, 2), voice.turnsOpened)
        }

    @Test
    fun `abort mid-reply stops the speaker, the synthesis and the run - and the dead turn's tail is inert`() =
        runTest {
            // REQ-19's other half: "any listening window or turn in flight". Leaving via the rail
            // mid-reply (T-21) lands here - the same three stops as a barge-in, because the same
            // two pipes are producing a reply nobody is staying to hear. What does not follow is
            // the re-arm: barge-in hands the floor to the interrupter; a departure hands it to no one.
            val loop = TestScopeLoop(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

            wake.fire()
            voice.hear(0, "what is the warp core status", endpointed = true)
            runCurrent()
            agent.emit(HermesEvent.MessageDelta("run-1", "The warp core is stable. "))
            voice.emit(TtsEvent.Start("req-1", 22_050))
            voice.emit(TtsEvent.Audio(ByteArray(320), 22_050))
            runCurrent()
            assertEquals(ConsoleState.SPEAKING, loop.state.value)

            loop.abort()
            runCurrent()

            assertEquals(1, sinks.single().stops, "the local track must be flushed and stopped")
            assertEquals(listOf("req-1"), voice.ttsCancelled, "VoiceManager must stop synthesising")
            assertEquals(listOf("run-1"), agent.stopped, "Hermes must stop the run")
            assertEquals(ConsoleState.READY, loop.state.value)
            assertFalse(cued.contains(LcarsCue.ERROR), "an abort is the loop's own doing, not a fault")

            // The cancelled turn's tail arrives - a late delta, residual PCM, the dead request's
            // audio_end - and none of it does anything: not spoken, not played, ends nothing.
            agent.emit(HermesEvent.MessageDelta("run-1", "and the deflector is at full."))
            voice.emit(TtsEvent.Audio(ByteArray(160), 22_050))
            voice.emit(TtsEvent.End("req-1"))
            runCurrent()
            assertTrue(
                voice.ttsOut.none { it.text.contains("deflector") },
                "a token from the aborted run must never be sent to be spoken",
            )
            assertTrue(voice.ttsOut.none { it.done }, "the dead request is never flushed with done")
            assertEquals(1, sinks.single().written.size, "not one more byte of the aborted reply is spoken")
            assertEquals(ConsoleState.READY, loop.state.value, "the dead request's audio_end ends nothing")
        }

    @Test
    fun `abort in the follow-up window leaves the completed run alone - and the window's remainder is dead`() =
        runTest {
            // REQ-19's own acceptance names this window: the reply finished, the floor is open for
            // a follow-up, and the visit ends. The turn log still names the finished run - a naive
            // abort would reach back and "stop" a run that already reached its terminal.
            val loop = TestScopeLoop(
                CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                followUpWindowMillis = { 10_000 },
            )

            wake.fire()
            runCurrent()
            voice.hear(0, "what time is it", endpointed = true, isFinal = true)
            runCurrent()
            agent.emit(HermesEvent.MessageDelta("run-1", "It is 0900."))
            agent.emit(HermesEvent.RunCompleted("run-1", output = "It is 0900."))
            voice.emit(TtsEvent.Start("req-1", 22_050))
            voice.emit(TtsEvent.End("req-1"))
            runCurrent()
            assertEquals(ConsoleState.LISTENING, loop.state.value, "the follow-up window is open")

            loop.abort()
            runCurrent()

            assertEquals(ConsoleState.READY, loop.state.value)
            assertTrue(agent.stopped.isEmpty(), "the run completed on its own - there is nothing to stop")
            assertTrue(voice.ttsCancelled.isEmpty(), "its synthesis already ended - there is nothing to cancel")

            // Speech in what remained of the window creates no turn, in this session or any other
            // (REQ-19). The wall is left standing: clearing it is the root's move, ordered after
            // this one.
            voice.hear(1, "and in tokyo", endpointed = true, isFinal = true)
            runCurrent()
            assertEquals(listOf("what time is it"), agent.dispatched)
            assertEquals("what time is it", loop.turn.value.prompt, "the abort does not clear the wall - the root does")
        }

    @Test
    fun `abort on an idle console is a no-op`() = runTest {
        // T-20's other clause reaches the loop as this: ending an already-ready visit must not
        // reach back and stop the previous turn's completed run, or stop, or sound, anything.
        val loop = TestScopeLoop(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        loop.say("run a diagnostic")
        runCurrent()
        agent.emit(HermesEvent.RunCompleted("run-1", output = ""))
        runCurrent()
        assertEquals(ConsoleState.READY, loop.state.value)

        loop.abort()
        runCurrent()

        assertEquals(ConsoleState.READY, loop.state.value)
        assertTrue(agent.stopped.isEmpty(), "the finished run is not re-stopped")
        assertTrue(cued.isEmpty(), "nothing was in flight; nothing sounds")
    }
}
