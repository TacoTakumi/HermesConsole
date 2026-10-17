package com.hermesconsole.app

import com.hermesconsole.audio.AudioCapture
import com.hermesconsole.audio.AudioConfig
import com.hermesconsole.audio.AudioSink
import com.hermesconsole.audio.TtsPlayer
import com.hermesconsole.hermesclient.HermesEvent
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T-21 / REQ-22: the console goes dark when nobody needs it, and comes back the instant somebody
 * does.
 *
 * The claim has three edges, and each is a test here. The countdown must only ever run while the
 * console is READY - a long reply is not idleness. Restoration must be *event-shaped*, not
 * polled - a touch or the state leaving READY flips the screen back with no time passing. And the
 * screensaver must be presentation only: the voice loop underneath never learns the screen went
 * dark, which is what keeps a wake word from the idle state a full, ordinary turn.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IdleControllerTest {

    private companion object {
        /** The one test interval, for the tests that care about only one of the two clocks. */
        const val TIMEOUT = 30_000L

        /** Two deliberately different intervals, for the tests that are about their being two (REQ-16). */
        const val SESSION = 20_000L
        const val SCREENSAVER = 50_000L
    }

    // -- the countdown ------------------------------------------------------------------------

    @Test
    fun `the console idles after the timeout, and not a moment before`() = runTest {
        val state = MutableStateFlow(ConsoleState.READY)
        val idle = controller(state)

        advanceTimeBy(TIMEOUT - 1)
        assertFalse(idle.idle.value, "the screensaver must wait out the whole timeout")

        advanceTimeBy(2)
        assertTrue(idle.idle.value, "an untouched READY console must reach the screensaver")
    }

    @Test
    fun `a touch during the countdown restarts it`() = runTest {
        val state = MutableStateFlow(ConsoleState.READY)
        val idle = controller(state)

        advanceTimeBy(TIMEOUT - 1)
        idle.touched()

        advanceTimeBy(TIMEOUT - 1)
        assertFalse(idle.idle.value, "a touch must buy the screen a whole fresh timeout")

        advanceTimeBy(2)
        assertTrue(idle.idle.value, "the countdown must still complete after the reset")
    }

    @Test
    fun `an engaged console never idles, however long the turn runs`() = runTest {
        val state = MutableStateFlow(ConsoleState.READY)
        val idle = controller(state)

        state.value = ConsoleState.SPEAKING
        advanceTimeBy(TIMEOUT * 10)
        assertFalse(idle.idle.value, "a console mid-turn is not idle - the countdown must not run")
    }

    @Test
    fun `the countdown restarts when the turn ends`() = runTest {
        val state = MutableStateFlow(ConsoleState.READY)
        val idle = controller(state)

        state.value = ConsoleState.SPEAKING
        advanceTimeBy(TIMEOUT * 2)

        state.value = ConsoleState.READY
        advanceTimeBy(TIMEOUT - 1)
        assertFalse(idle.idle.value, "the turn's end must start a fresh countdown, not resume a stale one")

        advanceTimeBy(2)
        assertTrue(idle.idle.value)
    }

    // -- restoration --------------------------------------------------------------------------

    @Test
    fun `a touch restores the ready state with no time passing`() = runTest {
        val state = MutableStateFlow(ConsoleState.READY)
        val idle = controller(state)
        advanceTimeBy(TIMEOUT + 1)
        assertTrue(idle.idle.value)

        val before = testScheduler.currentTime
        idle.touched()
        assertFalse(idle.idle.value, "a touch must clear the screensaver synchronously")
        assertEquals(before, testScheduler.currentTime, "restoration must not wait on the clock")
    }

    @Test
    fun `the state leaving READY restores the screen - the wake word's path in`() = runTest {
        val state = MutableStateFlow(ConsoleState.READY)
        val idle = controller(state)
        advanceTimeBy(TIMEOUT + 1)
        assertTrue(idle.idle.value)

        val before = testScheduler.currentTime
        state.value = ConsoleState.LISTENING
        runCurrent()
        assertFalse(idle.idle.value, "the loop engaging must wake the screen")
        assertEquals(before, testScheduler.currentTime, "restoration must not wait on the clock")
    }

    @Test
    fun `restored() reports the latency once, and only for a real restore`() = runTest {
        val state = MutableStateFlow(ConsoleState.READY)
        val idle = controller(state)

        assertNull(idle.restored(), "nothing to restore yet - the console has never idled")

        advanceTimeBy(TIMEOUT + 1)
        idle.touched()
        val latency = idle.restored()
        assertNotNull(latency, "a restore was pending - its latency is the verify's evidence")
        assertNull(idle.restored(), "the same restore must not be reported twice")
    }

    // -- the visit ends with the idleness (T-08) -------------------------------------------------

    @Test
    fun `the idle interval ends the visit, so the wall comes back to the ready screen`() = runTest {
        // REQ-07: the same silence that darkens the screen also closes the conversation. One clock
        // decides both, so nobody can walk up to a dark wall, touch it, and find themselves mid-way
        // through a stranger's visit.
        val state = MutableStateFlow(ConsoleState.READY)
        val visit = visitEndingWith(controller(state))
        visit.sessions.select("a")
        runCurrent()

        advanceTimeBy(TIMEOUT - 1)
        assertEquals("a", visit.sessions.current.value, "the visit must last out the whole interval")

        advanceTimeBy(2)
        assertNull(visit.sessions.current.value, "the elapsed interval must leave the session")
        assertNull(visit.sessions.backlog.value, "and hand the wall back to the attract state")
    }

    @Test
    fun `a turn running longer than the interval never ends the visit`() = runTest {
        // REQ-08: a console mid-turn is in use, however long the reply runs - and the screensaver's
        // countdown already knows that, which is the whole reason the visit hangs off it. The
        // person waiting on a two-minute answer must still be in their session when it arrives.
        val state = MutableStateFlow(ConsoleState.READY)
        val idle = controller(state)
        val visit = visitEndingWith(idle)
        visit.sessions.select("a")
        runCurrent()

        state.value = ConsoleState.SPEAKING
        advanceTimeBy(TIMEOUT * 10)

        assertFalse(idle.idle.value, "the countdown must not run under a turn")
        assertEquals("a", visit.sessions.current.value, "the session must still be current when the reply lands")
    }

    @Test
    fun `the session return and the screensaver each run off their own interval`() = runTest {
        // T-16, REQ-16 - which supersedes REQ-10, and it was the wall that superseded it. One clock
        // for both makes the visual half of the return *unobservable*: at 5/5 the screen goes black
        // in the same instant the conversation is handed back, so the wall darkens over a ready
        // screen nobody ever sees. Two clocks, and a person standing there watches it tidy itself
        // back to the session list and beep, and go dark two minutes later.
        val state = MutableStateFlow(ConsoleState.READY)
        val idle = controller(state, session = { SESSION }, screensaver = { SCREENSAVER })
        val visit = visitEndingWith(idle)
        visit.sessions.select("a")
        runCurrent()

        advanceTimeBy(SESSION - 1)
        assertEquals("a", visit.sessions.current.value, "the visit must wait out its own interval")

        advanceTimeBy(2)
        assertNull(visit.sessions.current.value, "and it ends on that one, not the screen's")
        assertEquals(listOf(LcarsCue.SESSION_ENDED), visit.cued, "sounding the full stop as it goes")
        assertFalse(idle.idle.value, "onto a lit wall: the return is something a person can watch happen")

        advanceTimeBy(SCREENSAVER - SESSION - 1)
        assertFalse(idle.idle.value, "the screensaver waits out its own, longer interval")

        advanceTimeBy(2)
        assertTrue(idle.idle.value, "and only then does the wall go dark, over a ready screen")
    }

    @Test
    fun `both clocks restart together, so the visit after a return can end too`() = runTest {
        // The failure a second StateFlow invites: `visitOver` latched true would make the first
        // return the only one this console ever performs, because the collector filtering for true
        // never sees a rising edge again. A wake word is what un-latches it, and it un-latches both.
        val state = MutableStateFlow(ConsoleState.READY)
        val idle = controller(state, session = { SESSION }, screensaver = { SCREENSAVER })
        val visit = visitEndingWith(idle)
        visit.sessions.select("first")
        runCurrent()
        advanceTimeBy(SCREENSAVER + 1)
        assertNull(visit.sessions.current.value, "the first visit ended")
        assertTrue(idle.idle.value, "and the wall went dark behind it")

        // Somebody walks up and speaks: a second visit, on the same console, from the dark.
        state.value = ConsoleState.LISTENING
        runCurrent()
        assertFalse(idle.idle.value, "the wake word brings the screen back")
        state.value = ConsoleState.READY
        visit.sessions.select("second")
        runCurrent()

        advanceTimeBy(SESSION + 1)
        assertNull(visit.sessions.current.value, "and the second visit ends on its own interval, like the first")
        assertEquals(
            listOf(LcarsCue.SESSION_ENDED, LcarsCue.SESSION_ENDED),
            visit.cued,
            "two visits, two full stops",
        )
    }

    @Test
    fun `a conversation paced under the interval never times out mid-visit`() = runTest {
        // T-13, REQ-15: the interval is measured from the end of the most recent turn in either
        // direction, not from the visit's start - so a person who thinks between questions keeps
        // their session. Three turns at just under the interval is nearly three intervals of
        // wall-clock inside one visit; a countdown started at the door would have ended it twice.
        val state = MutableStateFlow(ConsoleState.READY)
        val visit = visitEndingWith(controller(state))
        visit.sessions.select("a")
        runCurrent()

        repeat(3) { turn ->
            state.value = ConsoleState.LISTENING
            runCurrent()
            state.value = ConsoleState.READY
            runCurrent()

            advanceTimeBy(TIMEOUT - 1)
            assertEquals("a", visit.sessions.current.value, "the pause after turn ${turn + 1} must not end the visit")
            assertEquals(emptyList<LcarsCue>(), visit.cued, "and nothing must have been announced mid-conversation")
        }

        advanceTimeBy(2)
        assertTrue(
            testScheduler.currentTime > TIMEOUT,
            "the visit outlasted a single interval - which is the whole claim",
        )
        assertNull(visit.sessions.current.value, "the return still comes: one interval after the last turn")
        assertEquals(listOf(LcarsCue.SESSION_ENDED), visit.cued)
    }

    // -- the room hears it, once (T-12) ----------------------------------------------------------

    @Test
    fun `the idle return sounds the session-ended cue, exactly once`() = runTest {
        // REQ-09: an audible full stop, so a person walking past a wall that has just tidied itself
        // away knows the console did something rather than nothing. One emission per return - a
        // repeat every interval would turn an empty room into a metronome.
        val state = MutableStateFlow(ConsoleState.READY)
        val visit = visitEndingWith(controller(state))
        visit.sessions.select("a")
        runCurrent()

        advanceTimeBy(TIMEOUT + 1)
        assertEquals(listOf(LcarsCue.SESSION_ENDED), visit.cued, "the return is one cue, and its own")

        advanceTimeBy(TIMEOUT * 3)
        assertEquals(listOf(LcarsCue.SESSION_ENDED), visit.cued, "a wall left dark must not keep beeping")
    }

    @Test
    fun `a pane that never made a session is returned, and sounds the same one cue`() = runTest {
        // REQ-14: the wake word opens the pane before any session exists (T-06), so a person who
        // says the name and then nothing leaves a conversation standing open with nothing in it.
        // That is still a visit, and it still ends - out loud, and with no session ever created.
        val state = MutableStateFlow(ConsoleState.READY)
        val visit = visitEndingWith(controller(state))

        state.value = ConsoleState.LISTENING
        visit.sessions.woken()
        state.value = ConsoleState.READY
        runCurrent()
        assertEquals(emptyList<Any>(), visit.sessions.backlog.value, "the pane is open, on nothing")
        assertNull(visit.sessions.current.value, "and nothing was created for it")

        advanceTimeBy(TIMEOUT + 1)
        assertNull(visit.sessions.backlog.value, "the empty pane goes back to the ready screen too")
        assertEquals(listOf(LcarsCue.SESSION_ENDED), visit.cued, "and says so with the same single cue")
    }

    @Test
    fun `the interval elapsing on the ready screen sounds nothing`() = runTest {
        // The other half of REQ-09's "per idle return": with no pane open there is no visit to end,
        // so the console darkening a ready screen is silent. Otherwise an untouched wall would
        // announce itself into an empty room every five minutes, all night.
        val state = MutableStateFlow(ConsoleState.READY)
        val idle = controller(state)
        val visit = visitEndingWith(idle)

        advanceTimeBy(TIMEOUT + 1)
        assertTrue(idle.idle.value, "the screensaver still comes up - it is the visit that is absent")
        assertEquals(emptyList<LcarsCue>(), visit.cued, "nothing was left, so nothing is announced")
    }

    @Test
    fun `a saved interval governs the next idle stretch, with nothing rebuilt`() = runTest {
        // REQ-17's last clause, and the reason the intervals are suppliers rather than Longs handed
        // over at assembly: MainActivity builds these clocks in onCreate, so an interval fixed there
        // could only be changed by a reinstall. Read at the top of each countdown instead, and the
        // value somebody just saved is the one the very next silence is measured against.
        val state = MutableStateFlow(ConsoleState.READY)
        var configured = SESSION
        val idle = controller(state, session = { configured })
        val visit = visitEndingWith(idle)
        visit.sessions.select("a")
        runCurrent()

        // The save itself: the settings screen writes, and leaving it is a touch on the console.
        configured = SESSION * 2
        idle.touched()
        runCurrent()

        advanceTimeBy(SESSION + 1)
        assertEquals("a", visit.sessions.current.value, "the old interval must not still be running")

        advanceTimeBy(SESSION)
        assertNull(visit.sessions.current.value, "the visit ends on the interval that was just saved")
    }

    @Test
    fun `the shipped intervals are five minutes for the visit and seven for the screen`() {
        // REQ-16's own numbers, and the ones the wall actually runs: the composition root injects
        // nothing, so what ships is these two constants. The two-minute gap is the point of having
        // two - it is the window in which the return is visible from across the room.
        assertEquals(
            300_000L,
            IdleController.DEFAULT_SESSION_TIMEOUT_MILLIS,
            "five minutes before the conversation is handed back",
        )
        assertEquals(
            420_000L,
            IdleController.DEFAULT_SCREENSAVER_TIMEOUT_MILLIS,
            "seven before the wall goes dark",
        )
    }

    // -- the screensaver is presentation only ---------------------------------------------------

    @Test
    fun `a wake word from the idle state still completes a full voice turn`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val wake = FakeWake()
        val voice = FakeVoiceLink()
        val agent = FakeAgent()
        val loop = VoiceLoop(
            capture = FakeCapture(),
            wake = wake,
            voice = voice,
            agent = agent,
            player = TtsPlayer({ rate -> FakeSink(rate) }),
            sessionId = { "session-idle" },
            scope = scope,
            newRequestId = { "req-1" },
        ).also { it.start() }
        val idle = controller(loop.state)

        advanceTimeBy(TIMEOUT + 1)
        assertTrue(idle.idle.value, "the console must be dark before the wake word lands")

        // The wake word, spoken at a dark screen. The loop never knew the screen was off, so this
        // is an ordinary turn: wake -> transcript -> run -> spoken reply -> READY.
        wake.fire()
        runCurrent()
        assertFalse(idle.idle.value, "the wake word must bring the screen back")
        assertEquals(ConsoleState.LISTENING, loop.state.value)

        voice.hear(index = 0, text = "report status", endpointed = true, isFinal = true)
        advanceUntilIdle()
        agent.emit(HermesEvent.MessageDelta("run-1", "All systems nominal."))
        agent.emit(HermesEvent.RunCompleted("run-1"))
        advanceUntilIdle()
        voice.emit(TtsEvent.Start("req-1", 24_000))
        voice.emit(TtsEvent.End("req-1"))
        advanceUntilIdle()

        assertEquals(ConsoleState.READY, loop.state.value, "the turn begun in the dark must run to completion")
        assertTrue(
            voice.ttsOut.any { it.text == "All systems nominal." },
            "the reply must have gone out to be spoken",
        )

        loop.close()
    }

    // -- harness ------------------------------------------------------------------------------

    /** What the console's idleness leaves behind: the store it left, and what the room heard. */
    private class Visit(val sessions: SessionBacklog, val cued: List<LcarsCue>)

    /**
     * A visit ended by the console's idleness, wired the two ways the composition root wires it -
     * through [endVisitWhenIdle] and [SessionBacklog.endVisit], not copies of them - so what these
     * tests drive is the real seam. The fetch is a stub: which lines a session restores is
     * [SessionBacklogTest]'s subject, and none of it survives the visit anyway.
     */
    private fun kotlinx.coroutines.test.TestScope.visitEndingWith(idle: IdleController): Visit {
        val cued = mutableListOf<LcarsCue>()
        val sessions = SessionBacklog(fetch = { emptyList() }, scope = backgroundScope)
        // No abort to perform: the countdown only elapses at READY, so a visit this harness ends
        // has nothing in flight - the departure mid-listening is ConsoleShellTest's subject (T-20).
        backgroundScope.endVisitWhenIdle(idle.visitOver) { sessions.endVisit(abort = {}, cue = cued::add) }
        return Visit(sessions, cued)
    }

    /** Both clocks at the one interval unless a test is specifically about their being two. */
    private fun kotlinx.coroutines.test.TestScope.controller(
        state: StateFlow<ConsoleState>,
        session: () -> Long = { TIMEOUT },
        screensaver: () -> Long = { TIMEOUT },
    ) = IdleController(
        state = state,
        scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        sessionTimeoutMillis = session,
        screensaverTimeoutMillis = screensaver,
        now = { testScheduler.currentTime },
    ).also { it.start() }

    // -- fakes: the loop's ports, driven by the test ------------------------------------------

    private class FakeWake : WakeWordSource {
        private val signals = MutableSharedFlow<WakeWord>(extraBufferCapacity = 4)
        fun fire() = check(signals.tryEmit(WakeWord("hey computer", 0L)))
        override fun detections(frames: Flow<ByteArray>): Flow<WakeWord> =
            merge(frames.transform { }, signals)
        override fun reset() = Unit
        override fun close() = Unit
    }

    private class FakeVoiceLink : VoiceLink {
        data class TtsFrame(val requestId: String, val text: String, val done: Boolean)

        private val transcriptState = MutableStateFlow(Transcript())
        override val transcript: StateFlow<Transcript> get() = transcriptState

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

        fun hear(index: Int, text: String, endpointed: Boolean, isFinal: Boolean) {
            transcriptState.value = Transcript(
                listOf(TranscriptSegment(index, text, isFinal, endpointed, turnId = openTurn)),
            )
        }
    }

    private class FakeAgent : Agent {
        private val events = Channel<HermesEvent>(Channel.UNLIMITED)
        override suspend fun dispatch(input: String, sessionId: String): String = "run-1"
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
