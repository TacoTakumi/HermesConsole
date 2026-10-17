package com.hermesconsole.app

import com.hermesconsole.audio.AudioCapture
import com.hermesconsole.audio.AudioConfig
import com.hermesconsole.audio.AudioSink
import com.hermesconsole.audio.TtsPlayer
import com.hermesconsole.hermesclient.HermesEvent
import com.hermesconsole.hermesclient.HermesMessage
import com.hermesconsole.lcars.LcarsColors
import com.hermesconsole.lcars.LcarsCue
import com.hermesconsole.voiceclient.Transcript
import com.hermesconsole.voiceclient.TranscriptSegment
import com.hermesconsole.voiceclient.TtsEvent
import com.hermesconsole.wakeword.WakeWord
import com.hermesconsole.wakeword.WakeWordSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T-15 / REQ-07: whose wall is it, across a whole visit?
 *
 * The M-02 wall run failed one exit item with 150 green tests standing behind it, and the reason is
 * the subject of this file. "The ready screen is shown" is a condition across two owners - the
 * store's pane and the loop's turn log - and it lived as an inline expression inside a composable,
 * where the only thing a JVM test could reach was one of its four terms. So the suite happily
 * asserted "the store is null" and let that stand for the acceptance's actual words, while the wall
 * went on rendering the last visitor's conversation over an attract state that believed it was
 * showing.
 *
 * These tests drive [attractHolds] - the very function the shell now calls - over the real pieces
 * the wall is assembled from: a real [VoiceLoop] taking a real turn, a real [SessionBacklog], a real
 * [IdleController] counting down, and the two real seams the composition root wires between them
 * ([endVisitWhenIdle], [SessionBacklog.endVisit]). Nothing here is a copy of the wiring; if the
 * visit does not actually end, these fail.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConsoleShellTest {

    private companion object {
        const val TIMEOUT = 30_000L
        val STORED = listOf(
            HermesMessage(role = "user", content = "how are the shields"),
            HermesMessage(role = "assistant", content = "holding"),
        )
    }

    // -- the whole visit ------------------------------------------------------------------------

    @Test
    fun `a visit entered, spoken and timed out gives the wall back to the ready screen`() = runTest {
        // The scenario the wall run walked through by hand, on the JVM: a stored session entered
        // from the ready screen, one turn spoken into it, then nobody in the room for an interval.
        val wall = wall(sessionId = "stored-a")
        assertTrue(wall.attract(), "before anyone touches it, the console is standing by")

        wall.sessions.select("stored-a")
        runCurrent()
        assertEquals(STORED, wall.backlog(), "the entered session's stored lines take the wall")
        assertFalse(wall.attract(), "an entered session owns the wall, not the attract loop")

        wall.speakATurn(said = "report status", replied = "All systems nominal.")
        assertEquals("All systems nominal.", wall.loop.turn.value.reply, "the turn finished on the wall")
        assertFalse(wall.attract(), "a finished exchange still holds the wall")

        advanceTimeBy(TIMEOUT + 1)
        runCurrent()

        assertTrue(
            wall.attract(),
            "the interval elapsed with nobody engaged: every owner hands the wall back, not just the store",
        )
        assertNull(wall.sessions.current.value, "and the next turn has nowhere to land until someone enters again")
        assertEquals(listOf(LcarsCue.SESSION_ENDED), wall.cued, "one full stop, sounded once")

        wall.loop.close()
    }

    @Test
    fun `a wake that never became a session is taken off the wall by the same return`() = runTest {
        // REQ-14, in the shape the wall renders it: nothing was created, so the store's pane is
        // merely open-and-empty and every word on display is in the loop's live turn. A return that
        // cleared only the store would leave the wall reading as occupied with the session gone.
        val wall = wall(sessionId = "never-used")
        wall.speakATurn(said = "are you there", replied = "Standing by.")
        assertFalse(wall.attract(), "the words are on the wall, session or no session")

        advanceTimeBy(TIMEOUT + 1)
        runCurrent()

        assertTrue(wall.attract(), "the ready screen is shown")
        assertEquals(listOf(LcarsCue.SESSION_ENDED), wall.cued, "and the room hears the visit end")

        wall.loop.close()
    }

    // -- T-21 / REQ-18: the rail's sessions segment is a departure -------------------------------

    @Test
    fun `the sessions segment ends the visit - and the next wake starts a fresh session, never the left one`() =
        runTest {
            // REQ-18: leaving for the main screen IS leaving the session. The segment's tap fires
            // the same root decision as the idle return - the Wall.endVisit handle below is that
            // lambda - and this drives its whole consequence over the real session seam: the loop's
            // provider goes through SessionBacklog.currentOrCreate here, exactly as the composition
            // root wires it, so "a new session" is something this test can watch being minted.
            var minted = 0
            val wall = wall(sessionId = "unused", mint = { "made-${++minted}" })

            wall.sessions.select("stored-a")
            runCurrent()
            wall.speakATurn(said = "report status", replied = "All systems nominal.")
            assertFalse(wall.attract(), "a finished exchange holds the wall")
            assertEquals(listOf("stored-a"), wall.landedIn(), "the visit's turn ran in the entered session")

            // The rail's sessions segment (T-21): not a browse toggled over the turn, a departure.
            wall.endVisit()
            runCurrent()

            assertNull(wall.sessions.current.value, "the store's current session is gone")
            assertNull(wall.backlog(), "the pane is the attract state's again")
            assertTrue(wall.attract(), "the main screen holds - attractHolds, not a summoned overlay")
            assertEquals(listOf(LcarsCue.SESSION_ENDED), wall.cued, "the same single cue as the idle return")

            // The next wake word is a new visit: its turn lands in a session made now, never in
            // the one everybody left. Continuing that one means selecting it again from the list.
            wall.speakATurn(said = "fresh start", replied = "Acknowledged.")
            assertEquals(
                listOf("stored-a", "made-1"),
                wall.landedIn(),
                "the departure means the next turn cannot land in the left session",
            )

            wall.loop.close()
        }

    // -- T-02 / REQ-04: the comms slot becomes system access, the way home -----------------------

    @Test
    fun `no rail segment is labeled comms - system access stands in its place`() {
        val labels = railSegments(
            attract = false,
            onSystemAccess = {}, onSessions = {}, onConfigure = {}, onApps = {},
        ).mapNotNull { it.label }

        assertTrue("system access" in labels, "the rail offers the way home by name: $labels")
        assertTrue(labels.none { "comms" in it }, "a comms label survives on the rail: $labels")
    }

    @Test
    fun `during a conversation the system access segment drives the end-visit path`() {
        var departures = 0
        val segments = railSegments(
            attract = false,
            onSystemAccess = { departures++ }, onSessions = {}, onConfigure = {}, onApps = {},
        )

        val home = segments.single { it.label == "system access" }
        checkNotNull(home.onClick, { "mid-conversation, the segment must be tappable" }).invoke()
        assertEquals(1, departures, "the tap is the same end-visit the rail was handed")
    }

    @Test
    fun `on the System Access screen the segment is inert chrome - there is no visit to end`() {
        val segments = railSegments(
            attract = true,
            onSystemAccess = { }, onSessions = {}, onConfigure = {}, onApps = {},
        )

        assertNull(segments.single { it.label == "system access" }.onClick, "a no-op is no onClick at all")
    }

    // -- T-15 / REQ-17: the jump-to-latest control is the conversation's ------------------------

    @Test
    fun `the jump control shows over an overflowing conversation scrolled up off the end`() {
        assertTrue(jumpControlShows(overflowing = true, atBottom = false, attract = false))
        assertFalse(
            jumpControlShows(overflowing = true, atBottom = true, attract = false),
            "at the bottom there is nothing to jump to",
        )
        assertFalse(
            jumpControlShows(overflowing = false, atBottom = true, attract = false),
            "content that fits has no latest to chase",
        )
    }

    @Test
    fun `the jump control never shows while the attract state holds`() {
        assertFalse(
            jumpControlShows(overflowing = true, atBottom = false, attract = true),
            "the System Access screen is never behind on anything - the control is the conversation's",
        )
    }

    // -- T-09 / REQ-02: the sessions segment opens the list from home ---------------------------

    @Test
    fun `from the attract state the sessions segment opens the sessions screen, not a departure`() {
        var opened = 0
        var departed = 0
        val segments = railSegments(
            attract = true,
            onSystemAccess = {}, onSessions = { departed++ }, onConfigure = {}, onApps = {},
            onOpenSessions = { opened++ },
        )

        checkNotNull(segments.single { it.label == "sessions" }.onClick).invoke()
        assertEquals(1, opened, "home's sessions tap presents the list")
        assertEquals(0, departed, "there is no visit to end from home")
    }

    @Test
    fun `mid-conversation the sessions segment is still the departure T-21 made it`() {
        var opened = 0
        var departed = 0
        val segments = railSegments(
            attract = false,
            onSystemAccess = {}, onSessions = { departed++ }, onConfigure = {}, onApps = {},
            onOpenSessions = { opened++ },
        )

        checkNotNull(segments.single { it.label == "sessions" }.onClick).invoke()
        assertEquals(1, departed, "the conversation's tap ends the visit, exactly as today")
        assertEquals(0, opened, "presenting the list mid-conversation is T-10's step, not this one's")
    }

    @Test
    fun `an unconfigured console's sessions segment is inert on home - no list to offer`() {
        val segments = railSegments(
            attract = true,
            onSystemAccess = {}, onSessions = {}, onConfigure = {}, onApps = {},
            onOpenSessions = null,
        )

        assertNull(segments.single { it.label == "sessions" }.onClick)
    }

    // -- T-10 / REQ-03: the mid-conversation sessions tap - the departure, then the list --------

    @Test
    fun `the mid-conversation tap ends the visit first, then presents - never a list over a live turn`() {
        val order = mutableListOf<String>()

        sessionsDeparture(endVisit = { order += "end" }, present = { order += "present" }).invoke()

        assertEquals(listOf("end", "present"), order, "the list opens over the attract state, not over a conversation")
    }

    @Test
    fun `a conversation's sessions tap gives the wall back and the list is presented over home`() = runTest {
        // The T-21 departure scenario, driven through the rail the wall renders: the segment's
        // own onClick carries the composed tap, and its whole consequence runs over the real
        // session seam - the visit genuinely ends, then the screen presents.
        val wall = wall(sessionId = "stored-a")
        wall.sessions.select("stored-a")
        runCurrent()
        wall.speakATurn(said = "report status", replied = "All systems nominal.")
        assertFalse(wall.attract(), "a finished exchange holds the wall")

        var presented = false
        val tap = checkNotNull(
            railSegments(
                attract = false,
                onSystemAccess = {},
                onSessions = sessionsDeparture(endVisit = { wall.endVisit() }, present = { presented = true }),
                onConfigure = {}, onApps = {},
            ).single { it.label == "sessions" }.onClick,
        )

        tap()
        runCurrent()

        assertTrue(wall.attract(), "the same departure as T-21: the attract state genuinely holds")
        assertEquals(listOf(LcarsCue.SESSION_ENDED), wall.cued, "the room hears the one full stop")
        assertTrue(presented, "and the sessions screen is presented over home")

        wall.loop.close()
    }

    // -- T-20 / REQ-19: the departure aborts the loop, whatever it was doing ---------------------

    @Test
    fun `ending a visit mid-listening aborts the loop - READY, the wall back, one full stop`() = runTest {
        // The departure arrives while the floor is open. Before T-20, endVisit cleared only the
        // store: clearWall's clearTurn was refused by a loop that was not READY, so the wall kept
        // the visitor's turn and the window stayed open - dispatching whatever was said next into
        // the session everyone had just left.
        val wall = wall(sessionId = "stored-a")
        wall.sessions.select("stored-a")
        runCurrent()
        assertFalse(wall.attract(), "the entered session owns the wall")

        wall.openTheFloor()
        assertEquals(ConsoleState.LISTENING, wall.loop.state.value, "the floor is open, nobody has spoken yet")

        wall.endVisit()
        runCurrent()

        assertEquals(ConsoleState.READY, wall.loop.state.value, "ending the visit aborts the loop to READY")
        assertTrue(wall.attract(), "the ready screen is shown - the wall cleared, not just the store")
        assertEquals(listOf(LcarsCue.SESSION_ENDED), wall.cued, "one full stop - the abort itself is silent")

        // Speech in what remained of the window creates no turn, in the left session or any other
        // (REQ-19): the window died with the visit.
        wall.speakIntoTheRoom("computer are you still there")
        assertTrue(wall.dispatched().isEmpty(), "nothing further dispatches into the session being left")
        assertEquals(ConsoleState.READY, wall.loop.state.value)

        wall.loop.close()
    }

    @Test
    fun `ending an already-ready visit still emits nothing`() = runTest {
        // The acceptance's other clause, which the abort must not disturb: an ended visit - or no
        // visit at all - ends nothing, out loud or otherwise.
        val wall = wall(sessionId = "unused")

        wall.endVisit()
        runCurrent()
        assertTrue(wall.cued.isEmpty(), "no visit, no full stop")
        assertTrue(wall.attract())
        assertEquals(ConsoleState.READY, wall.loop.state.value)

        // And a visit already handed back by the countdown is not announced a second time.
        wall.speakATurn(said = "report status", replied = "Nominal.")
        advanceTimeBy(TIMEOUT + 1)
        runCurrent()
        assertEquals(listOf(LcarsCue.SESSION_ENDED), wall.cued)

        wall.endVisit()
        runCurrent()
        assertEquals(listOf(LcarsCue.SESSION_ENDED), wall.cued, "an already-returned wall is not announced again")

        wall.loop.close()
    }

    // -- the beats the attract state must not flash through --------------------------------------

    @Test
    fun `an entered session with nothing in it still owns the wall`() = runTest {
        // T-43/T-06: an empty pane is a conversation standing open on purpose - the state a wake
        // word opens before a word has been said - and is the whole reason the store's pane is
        // nullable rather than merely empty.
        assertFalse(
            attractHolds(ConsoleState.READY, TurnLog(), backlog = emptyList(), spoken = emptyList()),
            "an empty pane is an open conversation, not the attract state",
        )
    }

    @Test
    fun `a session mid-conversation holds the wall through the blank beat of the next turn`() = runTest {
        // T-44: the wake retires the finished exchange onto the spoken record and the new turn is
        // still blank. For one frame the turn log says nothing - and the wall must not blink back
        // to STANDING BY in the middle of somebody's conversation.
        assertFalse(
            attractHolds(
                ConsoleState.READY,
                TurnLog(),
                backlog = null,
                spoken = listOf(SpokenTurn(runId = null, prompt = "q", reply = "a")),
            ),
            "the accumulated conversation holds the wall while the next turn is still empty",
        )
    }

    @Test
    fun `a turn in flight holds the wall whatever the store says`() = runTest {
        assertFalse(
            attractHolds(ConsoleState.THINKING, TurnLog(prompt = "report status"), null, emptyList()),
            "an engaged console is never the attract state",
        )
    }

    // -- battery-indicator T-02 / REQ-01, REQ-02: the carved battery segment in the top bar -----

    @Test
    fun `the top bar carries a battery segment between the shortened filler and the voice loop`() {
        // REQ-01: the blank Bluey filler is split into a shortened filler + the battery segment,
        // seated beside the voice-loop block - no labeled control gives up its place.
        val segs = topBarSegments(BatteryState(level = 87, scale = 100, charging = false))

        val battery = segs.single { it.label?.startsWith("BAT") == true }
        val filler = segs.indexOfFirst { it.label == null && it.color == LcarsColors.Bluey }
        val voiceLoop = segs.indexOfFirst { it.label == "voice loop" }

        assertTrue(filler >= 0, "the Bluey filler is carved, not removed: $segs")
        assertTrue(voiceLoop >= 0, "the voice-loop block is still there: $segs")
        assertTrue(
            filler < segs.indexOf(battery) && segs.indexOf(battery) < voiceLoop,
            "the battery segment sits between the filler and the voice loop: $segs",
        )
    }

    @Test
    fun `the battery segment renders the mapped label and colour, and the placeholder for no reading`() {
        // The segment wears exactly what the T-01 mapping returns (REQ-03/04/05/08 as rendered).
        val live = topBarSegments(BatteryState(level = 87, scale = 100, charging = true))
            .single { it.label?.startsWith("BAT") == true }
        assertEquals("BAT 87% +", live.label)
        assertEquals(LcarsColors.GoldenTanoi, live.color)

        val none = topBarSegments(null).single { it.label?.startsWith("BAT") == true }
        assertEquals("BAT --", none.label)
        assertEquals(LcarsColors.Gray, none.color)
    }

    @Test
    fun `the carve leaves the voice-loop block untouched`() {
        val voiceLoop = topBarSegments(null).single { it.label == "voice loop" }
        assertEquals(LcarsColors.AlmondCreme, voiceLoop.color)
    }

    @Test
    fun `the battery segment is chrome - present whatever the battery, independent of any screen`() {
        // topBarSegments takes only the battery: no content/attract input, so the segment is frame
        // chrome that reads identically on every screen the shell wraps (REQ-02). Absence under the
        // screensaver is the shell's existing idle gate - the segment lives in TopFrame, composed
        // only while !idle.
        assertEquals(1, topBarSegments(BatteryState(100, 100, false)).count { it.label?.startsWith("BAT") == true })
        assertEquals(1, topBarSegments(null).count { it.label?.startsWith("BAT") == true })
    }

    // -- harness -----------------------------------------------------------------------------

    /**
     * The wall, assembled the way the composition root assembles it: the loop and the store joined
     * by [SessionBacklog.clearWall], and the visit hung off the session countdown through
     * [endVisitWhenIdle]. The only things faked are the ports a JVM has no hardware or server for.
     */
    private class Wall(
        val loop: VoiceLoop,
        val sessions: SessionBacklog,
        val cued: List<LcarsCue>,
        /**
         * The root's one decision about a visit ending, exactly as [endVisitWhenIdle] is handed it:
         * the idle countdown fires this lambda, and so does anything else that ends a visit (the
         * rail's departure, T-21). The tests call the same reference the watcher holds, so a test
         * that passes here has proven the wiring, not a copy of it.
         */
        val endVisit: () -> Unit,
        private val voice: FakeVoiceLink,
        private val agent: FakeAgent,
        private val wake: FakeWake,
        /**
         * Let what is already scheduled run, with the clock held still. Never `advanceUntilIdle`:
         * an idle countdown is always pending here, so running the scheduler dry would end the
         * visit in the middle of the turn that is meant to be filling the wall. The clock moves in
         * these tests exactly once, where a test says it does.
         */
        private val settle: () -> Unit,
    ) {

        fun backlog(): List<HermesMessage>? = sessions.backlog.value

        /** Every utterance the loop sent to Hermes, across the wall's whole life. */
        fun dispatched(): List<String> = agent.dispatched

        /** The session each of those turns landed in, in dispatch order. */
        fun landedIn(): List<String> = agent.sessions

        /** The wake word alone: the floor opens, and nobody has said anything yet. */
        fun openTheFloor() {
            wake.fire()
            settle()
        }

        /** Somebody talks after the visit ended: an endpointed utterance into whatever window is left. */
        fun speakIntoTheRoom(text: String) {
            voice.hear(index = 1, text = text, endpointed = true, isFinal = true)
            settle()
        }

        /** The shell's own decision, called with exactly what the shell would hand it. */
        fun attract(): Boolean =
            attractHolds(loop.state.value, loop.turn.value, sessions.backlog.value, loop.spoken.value)

        /**
         * One spoken exchange, end to end: wake -> transcript -> run -> reply -> READY. No time
         * passes over the whole of it - every step of a turn is event-driven, which is the reason
         * REQ-15's countdown can be measured from the end of one.
         */
        fun speakATurn(said: String, replied: String) {
            wake.fire()
            settle()
            voice.hear(index = 0, text = said, endpointed = true, isFinal = true)
            settle()
            agent.emit(HermesEvent.MessageDelta(RUN, replied))
            agent.emit(HermesEvent.RunCompleted(RUN))
            settle()
            voice.emit(TtsEvent.Start(REQUEST, 24_000))
            voice.emit(TtsEvent.End(REQUEST))
            settle()
            check(loop.state.value == ConsoleState.READY) { "the turn must finish before the room goes quiet" }
        }
    }

    private fun TestScope.wall(
        sessionId: String,
        /**
         * When set, the loop's provider is the composition root's, verbatim: the entered session
         * or one minted now through [SessionBacklog.currentOrCreate] (REQ-06) - so a test about
         * *which* session a turn lands in drives the real seam. Null keeps the fixed id: most of
         * these tests are about the wall, and a store that never hears about a dispatch is what
         * lets "a wake that never became a session" be set up at all.
         */
        mint: (() -> String)? = null,
    ): Wall {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val wake = FakeWake()
        val voice = FakeVoiceLink()
        val agent = FakeAgent()
        lateinit var sessions: SessionBacklog
        val loop = VoiceLoop(
            capture = FakeCapture(),
            wake = wake,
            voice = voice,
            agent = agent,
            player = TtsPlayer({ rate -> FakeSink(rate) }),
            sessionId = mint?.let { m -> suspend { sessions.currentOrCreate { m() } } } ?: { sessionId },
            scope = scope,
            newRequestId = { REQUEST },
        ).also { it.start() }

        val cued = mutableListOf<LcarsCue>()
        sessions = SessionBacklog(
            fetch = { STORED },
            clearWall = { loop.clearTurn() },
            scope = backgroundScope,
        )
        // The composition root's two watchers, verbatim: the wake opens the pane (T-06), and the
        // screensaver's countdown ends the visit (T-08).
        backgroundScope.launchWokenWatcher(loop.state, sessions)
        val idle = IdleController(
            state = loop.state,
            scope = scope,
            sessionTimeoutMillis = { TIMEOUT },
            // Longer than the visit's, as it ships (REQ-16), and far enough out that nothing here
            // is ever asserting about the wall in the dark: these tests are about the return.
            screensaverTimeoutMillis = { TIMEOUT * 10 },
            now = { testScheduler.currentTime },
        ).also { it.start() }
        val endVisit = { sessions.endVisit(abort = loop::abort, cue = cued::add) }
        backgroundScope.endVisitWhenIdle(idle.visitOver, endVisit)

        return Wall(loop, sessions, cued, endVisit, voice, agent, wake) { runCurrent() }
    }

    /** `WallConsole.start`'s pane watcher, in the one line it is there. */
    private fun CoroutineScope.launchWokenWatcher(state: StateFlow<ConsoleState>, sessions: SessionBacklog) =
        launch {
            state.collect { if (it != ConsoleState.READY) sessions.woken() }
        }

    // -- fakes: the ports a JVM has no hardware or server for --------------------------------

    private class FakeWake : WakeWordSource {
        private val signals = MutableSharedFlow<WakeWord>(extraBufferCapacity = 4)
        fun fire() = check(signals.tryEmit(WakeWord("hey computer", 0L)))
        override fun detections(frames: Flow<ByteArray>): Flow<WakeWord> =
            merge(frames.transform { }, signals)

        override fun reset() = Unit
        override fun close() = Unit
    }

    private class FakeVoiceLink : VoiceLink {
        private val transcriptState = MutableStateFlow(Transcript())
        override val transcript: StateFlow<Transcript> get() = transcriptState

        private val ttsIn = Channel<TtsEvent>(Channel.UNLIMITED)
        override val tts: Flow<TtsEvent> = ttsIn.consumeAsFlow()

        private var openTurn = 0

        override suspend fun turnStart(): Int = ++openTurn
        override fun sendCapture(frame: ByteArray) = Unit
        override fun sendTtsText(requestId: String, text: String, done: Boolean) = Unit
        override fun cancelTts(requestId: String) = Unit

        fun emit(event: TtsEvent) {
            ttsIn.trySend(event)
        }

        fun hear(index: Int, text: String, endpointed: Boolean, isFinal: Boolean) {
            transcriptState.value = Transcript(
                listOf(TranscriptSegment(index, text, isFinal, endpointed, turnId = openTurn)),
            )
        }
    }

    private class FakeAgent : Agent {
        private val events = Channel<HermesEvent>(Channel.UNLIMITED)
        val dispatched = mutableListOf<String>()
        val sessions = mutableListOf<String>()
        override suspend fun dispatch(input: String, sessionId: String): String {
            dispatched += input
            sessions += sessionId
            return RUN
        }
        override fun events(runId: String): Flow<HermesEvent> = kotlinx.coroutines.flow.flow {
            for (event in events) {
                emit(event)
                if (event.isTerminal) break
            }
        }

        override suspend fun stop(runId: String) = Unit
        fun emit(event: HermesEvent) {
            events.trySend(event)
        }
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
}

private const val RUN = "run-1"
private const val REQUEST = "req-1"
