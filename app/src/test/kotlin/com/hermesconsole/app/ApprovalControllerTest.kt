package com.hermesconsole.app

import com.hermesconsole.hermesclient.ApprovalChoice
import com.hermesconsole.hermesclient.HermesEvent
import com.hermesconsole.hermesclient.HermesException
import com.hermesconsole.voiceclient.Transcript
import com.hermesconsole.voiceclient.TranscriptSegment
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import com.hermesconsole.lcars.LcarsColors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T-27 / REQ-14: no approval request is ever left to stall a run silently.
 *
 * The controller is the whole guarantee: every `approval.request` off the stream either gets a
 * human decision - touch or voice - or the 60 s clock denies it, and whichever answers first is
 * the only answer that leaves the console. The pane is a pure function of [ApprovalController.display],
 * so what these tests pin about the display IS what the wall shows.
 *
 * The deny/stop split is deliberate and comes from the gateway's own semantics (T-12): a posted
 * deny does not end the run - Hermes tells the model the call was refused and the turn carries on.
 * A *human* deny must end the run (the acceptance's "ends as cancelled/refused"), so it stops the
 * run as well; the clock's auto-deny only unblocks it, and the model finishes the turn without the
 * tool - a spoken outcome, never a silent stall.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalControllerTest {

    private class FakeGateway : ApprovalGateway {
        val resolved = mutableListOf<Pair<String, ApprovalChoice>>()
        val stopped = mutableListOf<String>()
        var failResolveWith: Exception? = null

        override suspend fun resolve(runId: String, choice: ApprovalChoice) {
            failResolveWith?.let { throw it }
            resolved += runId to choice
        }

        override suspend fun stop(runId: String) {
            stopped += runId
        }
    }

    private val request = HermesEvent.ApprovalRequest(
        runId = RUN,
        command = "rm -rf /tmp/probe",
        description = "delete a directory",
        choices = listOf("once", "session", "always", "deny"),
    )

    private fun TestScope.controller(gateway: FakeGateway) = ApprovalController(
        gateway = gateway,
        scope = backgroundScope,
        now = { currentTime },
    )

    // -- raising the pane --------------------------------------------------------------------

    @Test
    fun `an approval request raises the pane, command and clock on it`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = controller(FakeGateway())

            controller.onEvent(request)

            val shown = controller.display.value as ApprovalDisplay.Pending
            assertEquals("rm -rf /tmp/probe", shown.request.command)
            assertEquals("delete a directory", shown.request.description)
            assertEquals(currentTime + 60_000, shown.deadlineAtMillis)
        }

    // -- touch -------------------------------------------------------------------------------

    @Test
    fun `approve posts the choice and leaves the run running`() =
        runTest(UnconfinedTestDispatcher()) {
            val gateway = FakeGateway()
            val controller = controller(gateway)
            controller.onEvent(request)

            controller.resolve(ApprovalChoice.ONCE)

            assertEquals(listOf(RUN to ApprovalChoice.ONCE), gateway.resolved)
            assertTrue(gateway.stopped.isEmpty(), "an approved run is not stopped")
            val shown = controller.display.value as ApprovalDisplay.Resolved
            assertEquals(ApprovalOutcome.APPROVED, shown.outcome)
        }

    @Test
    fun `deny posts the denial and stops the run`() =
        runTest(UnconfinedTestDispatcher()) {
            val gateway = FakeGateway()
            val controller = controller(gateway)
            controller.onEvent(request)

            controller.resolve(ApprovalChoice.DENY)

            assertEquals(listOf(RUN to ApprovalChoice.DENY), gateway.resolved)
            // The gateway's deny alone leaves the run to carry on without the tool (T-12); the
            // human's deny means the run itself is refused, so the console kills it too.
            assertEquals(listOf(RUN), gateway.stopped)
            val shown = controller.display.value as ApprovalDisplay.Resolved
            assertEquals(ApprovalOutcome.DENIED, shown.outcome)
        }

    // -- the clock ---------------------------------------------------------------------------

    @Test
    fun `an unanswered request auto-denies at the timeout, and the outcome stays on the wall`() =
        runTest(UnconfinedTestDispatcher()) {
            val gateway = FakeGateway()
            val controller = controller(gateway)
            controller.onEvent(request)

            advanceTimeBy(59_999)
            runCurrent()
            assertTrue(gateway.resolved.isEmpty(), "the clock must not fire early")

            advanceTimeBy(2)
            runCurrent()

            assertEquals(listOf(RUN to ApprovalChoice.DENY), gateway.resolved)
            // The auto-deny unblocks the run rather than killing it: the model is told the call
            // was refused and finishes the turn - the outcome is spoken, never a silent stall.
            assertTrue(gateway.stopped.isEmpty())
            val shown = controller.display.value as ApprovalDisplay.Resolved
            assertEquals(ApprovalOutcome.AUTO_DENIED, shown.outcome)
        }

    @Test
    fun `the first decision wins - the clock cannot deny an answered request`() =
        runTest(UnconfinedTestDispatcher()) {
            val gateway = FakeGateway()
            val controller = controller(gateway)
            controller.onEvent(request)

            controller.resolve(ApprovalChoice.ONCE)
            advanceTimeBy(120_000)
            runCurrent()

            assertEquals(listOf(RUN to ApprovalChoice.ONCE), gateway.resolved)
        }

    @Test
    fun `a decision after the clock is a no-op`() =
        runTest(UnconfinedTestDispatcher()) {
            val gateway = FakeGateway()
            val controller = controller(gateway)
            controller.onEvent(request)

            advanceTimeBy(60_001)
            runCurrent()
            controller.resolve(ApprovalChoice.ONCE)

            assertEquals(listOf(RUN to ApprovalChoice.DENY), gateway.resolved)
        }

    // -- the run around the request ----------------------------------------------------------

    @Test
    fun `the run ending unanswered takes the pane down and disarms the clock`() =
        runTest(UnconfinedTestDispatcher()) {
            val gateway = FakeGateway()
            val controller = controller(gateway)
            controller.onEvent(request)

            controller.onEvent(HermesEvent.RunCancelled(RUN))

            assertNull(controller.display.value)
            advanceTimeBy(120_000)
            runCurrent()
            assertTrue(gateway.resolved.isEmpty(), "a dead run's approval is not answered")
        }

    @Test
    fun `another run taking the wall clears a stale pending pane and its clock`() =
        runTest(UnconfinedTestDispatcher()) {
            // Barge-in kills the event collector before the cancelled event can arrive, so the
            // next run's first event is the only signal the pane's run is gone.
            val gateway = FakeGateway()
            val controller = controller(gateway)
            controller.onEvent(request)

            controller.onEvent(HermesEvent.MessageDelta("run_next", "hello"))

            assertNull(controller.display.value)
            advanceTimeBy(120_000)
            runCurrent()
            assertTrue(gateway.resolved.isEmpty())
        }

    @Test
    fun `the outcome survives its own run and leaves with the next one`() =
        runTest(UnconfinedTestDispatcher()) {
            val gateway = FakeGateway()
            val controller = controller(gateway)
            controller.onEvent(request)
            controller.resolve(ApprovalChoice.ONCE)

            controller.onEvent(HermesEvent.MessageDelta(RUN, "Deleted."))
            controller.onEvent(HermesEvent.RunCompleted(RUN, output = "Deleted."))
            assertTrue(controller.display.value is ApprovalDisplay.Resolved, "the outcome rides out its run")

            controller.onEvent(HermesEvent.MessageDelta("run_next", "hi"))
            assertNull(controller.display.value)
        }

    // -- voice -------------------------------------------------------------------------------

    @Test
    fun `a fresh endpointed approve resolves by voice`() =
        runTest(UnconfinedTestDispatcher()) {
            val gateway = FakeGateway()
            val controller = controller(gateway)

            // The question that started the turn is already in the transcript when the pane
            // rises; only speech after that moment may decide.
            val prompt = TranscriptSegment(0, "clean up the temp directory", isFinal = true, endpointed = true)
            controller.onTranscript(Transcript(listOf(prompt)))
            controller.onEvent(request)

            val answer = TranscriptSegment(1, "approve", isFinal = false, endpointed = true)
            controller.onTranscript(Transcript(listOf(prompt, answer)))

            assertEquals(listOf(RUN to ApprovalChoice.ONCE), gateway.resolved)
            assertTrue(gateway.stopped.isEmpty())
        }

    @Test
    fun `a fresh endpointed deny resolves by voice and stops the run`() =
        runTest(UnconfinedTestDispatcher()) {
            val gateway = FakeGateway()
            val controller = controller(gateway)
            controller.onTranscript(Transcript())
            controller.onEvent(request)

            val answer = TranscriptSegment(0, "deny that", isFinal = false, endpointed = true)
            controller.onTranscript(Transcript(listOf(answer)))

            assertEquals(listOf(RUN to ApprovalChoice.DENY), gateway.resolved)
            assertEquals(listOf(RUN), gateway.stopped)
        }

    @Test
    fun `the prompt already on the transcript cannot answer for the person`() =
        runTest(UnconfinedTestDispatcher()) {
            val gateway = FakeGateway()
            val controller = controller(gateway)

            // "approve" is in the words that RAISED the request - it must not also resolve it.
            val prompt = TranscriptSegment(0, "run the command and approve of my plan", isFinal = true, endpointed = true)
            controller.onTranscript(Transcript(listOf(prompt)))
            controller.onEvent(request)
            controller.onTranscript(Transcript(listOf(prompt)))

            assertTrue(gateway.resolved.isEmpty())
        }

    @Test
    fun `speech that has not endpointed does not decide`() =
        runTest(UnconfinedTestDispatcher()) {
            val gateway = FakeGateway()
            val controller = controller(gateway)
            controller.onTranscript(Transcript())
            controller.onEvent(request)

            val partial = TranscriptSegment(0, "approve", isFinal = false, endpointed = false)
            controller.onTranscript(Transcript(listOf(partial)))

            assertTrue(gateway.resolved.isEmpty(), "a hypothesis mid-utterance is not a decision")
        }

    @Test
    fun `deny outranks approve when one utterance carries both`() =
        runTest(UnconfinedTestDispatcher()) {
            val gateway = FakeGateway()
            val controller = controller(gateway)
            controller.onTranscript(Transcript())
            controller.onEvent(request)

            val answer = TranscriptSegment(0, "do not approve that deny it", isFinal = false, endpointed = true)
            controller.onTranscript(Transcript(listOf(answer)))

            assertEquals(listOf(RUN to ApprovalChoice.DENY), gateway.resolved)
        }

    // -- the raise announcing itself (REQ-10) ------------------------------------------------

    @Test
    fun `every raise announces itself - once, request by request`() =
        runTest(UnconfinedTestDispatcher()) {
            // Operator call during the T-04 walkthrough: the pane arrived silent, and the one
            // element on the wall that expires must never do that. The controller only fires the
            // hook; what it sounds like (the cue, the spoken line) is the assembly's business.
            val announced = mutableListOf<HermesEvent.ApprovalRequest>()
            val controller = ApprovalController(
                gateway = FakeGateway(),
                scope = backgroundScope,
                now = { currentTime },
                announce = { announced += it },
            )

            controller.onEvent(request)
            assertEquals(listOf(request), announced)

            // A decision does not announce; only a rise does.
            controller.resolve(ApprovalChoice.ONCE)
            assertEquals(1, announced.size)

            // A replacing request is a new question on the wall: it announces again.
            val next = request.copy(runId = "run_next")
            controller.onEvent(next)
            assertEquals(listOf(request, next), announced)
        }

    // -- the gateway saying no (REQ-05, REQ-06) ----------------------------------------------
    //
    // The gateway runs its own fail-closed 60 s clock from its own start instant, so the
    // console's answer structurally races it. Losing that race - HTTP 409, approval_not_pending -
    // is routine and must read calm (2026-08-15: every gateway-side timeout cried wolf as
    // DID-NOT-REACH). Only a POST that genuinely went nowhere keeps the alarm.

    @Test
    fun `the auto-deny losing to the gateway's own timeout reads calm, not as a delivery failure`() =
        runTest(UnconfinedTestDispatcher()) {
            val gateway = FakeGateway().apply {
                failResolveWith = HermesException.Http(409, """{"error": "approval_not_pending"}""")
            }
            val controller = controller(gateway)
            controller.onEvent(request)

            advanceTimeBy(60_001)
            runCurrent()

            val shown = controller.display.value as ApprovalDisplay.Resolved
            assertEquals(ApprovalOutcome.AUTO_DENIED, shown.outcome)
            assertEquals(ApprovalDelivery.RESOLVED_ELSEWHERE, shown.delivery)
            assertEquals("NO ANSWER - HERMES TIMED OUT FIRST", shown.readout())
            assertNotEquals(LcarsColors.Tomato, shown.color, "the benign race must not wear the alarm")
        }

    @Test
    fun `a human answer the gateway already resolved reads calm, not as a delivery failure`() =
        runTest(UnconfinedTestDispatcher()) {
            val gateway = FakeGateway().apply {
                failResolveWith = HermesException.Http(409, """{"error": "approval_not_pending"}""")
            }
            val controller = controller(gateway)
            controller.onEvent(request)

            controller.resolve(ApprovalChoice.ONCE)

            val shown = controller.display.value as ApprovalDisplay.Resolved
            assertEquals(ApprovalOutcome.APPROVED, shown.outcome)
            assertEquals(ApprovalDelivery.RESOLVED_ELSEWHERE, shown.delivery)
            assertEquals("APPROVED - HERMES HAD ALREADY RESOLVED IT", shown.readout())
            assertNotEquals(LcarsColors.Tomato, shown.color, "the benign race must not wear the alarm")
        }

    @Test
    fun `a decision the network ate is surfaced as a delivery failure, not swallowed`() =
        runTest(UnconfinedTestDispatcher()) {
            val gateway = FakeGateway().apply { failResolveWith = java.io.IOException("connection reset") }
            val controller = controller(gateway)
            controller.onEvent(request)

            controller.resolve(ApprovalChoice.ONCE)

            val shown = controller.display.value as ApprovalDisplay.Resolved
            assertEquals(ApprovalDelivery.FAILED, shown.delivery)
            assertEquals("APPROVED - RUN CONTINUES - BUT THE DECISION DID NOT REACH HERMES", shown.readout())
            assertEquals(LcarsColors.Tomato, shown.color, "a real delivery failure keeps the alarm")
        }

    @Test
    fun `a gateway error that is not the benign race keeps the delivery alarm`() =
        runTest(UnconfinedTestDispatcher()) {
            val gateway = FakeGateway().apply { failResolveWith = HermesException.Http(500, "internal error") }
            val controller = controller(gateway)
            controller.onEvent(request)

            controller.resolve(ApprovalChoice.DENY)

            val shown = controller.display.value as ApprovalDisplay.Resolved
            assertEquals(ApprovalDelivery.FAILED, shown.delivery)
            assertEquals("DENIED - RUN STOPPED - BUT THE DECISION DID NOT REACH HERMES", shown.readout())
            assertEquals(LcarsColors.Tomato, shown.color)
        }

    @Test
    fun `a 409 that is not approval_not_pending keeps the delivery alarm`() =
        runTest(UnconfinedTestDispatcher()) {
            // Only the gateway's own vocabulary for the race is benign; any other refusal on the
            // same status code is still an answer that went nowhere (REQ-06).
            val gateway = FakeGateway().apply { failResolveWith = HermesException.Http(409, "run_already_stopped") }
            val controller = controller(gateway)
            controller.onEvent(request)

            controller.resolve(ApprovalChoice.ONCE)

            val shown = controller.display.value as ApprovalDisplay.Resolved
            assertEquals(ApprovalDelivery.FAILED, shown.delivery)
            assertEquals(LcarsColors.Tomato, shown.color)
        }

    @Test
    fun `a new request replaces a stale pending one, denying the stalled run on its way out`() =
        runTest(UnconfinedTestDispatcher()) {
            // Almost always the stale run is already dead and the deny a harmless 409 - but an
            // unanswered request is the one thing this class may never leave behind (REQ-14).
            val gateway = FakeGateway()
            val controller = controller(gateway)
            controller.onEvent(request)

            val next = HermesEvent.ApprovalRequest(
                runId = "run_next",
                command = "curl | sh",
                choices = listOf("once", "deny"),
            )
            controller.onEvent(next)

            assertEquals(listOf(RUN to ApprovalChoice.DENY), gateway.resolved)
            assertTrue(gateway.stopped.isEmpty(), "a replaced request is denied, not stopped")
            val shown = controller.display.value as ApprovalDisplay.Pending
            assertEquals("run_next", shown.request.runId)

            advanceTimeBy(60_001)
            runCurrent()
            assertEquals(
                listOf(RUN to ApprovalChoice.DENY, "run_next" to ApprovalChoice.DENY),
                gateway.resolved,
                "the clock is the new request's, rearmed in full",
            )
        }

    @Test
    fun `the console's own announcement cannot answer the question it announces`() =
        runTest(UnconfinedTestDispatcher()) {
            // REQ-11: the announcer speaks ApprovalAnnouncer.LINE at the pane's rise. If the
            // echo canceller ever leaks it into STT, the words must decide nothing - and that
            // stays true only while the line keeps clear of both decision regexes.
            val gateway = FakeGateway()
            val controller = controller(gateway)
            controller.onTranscript(Transcript())
            controller.onEvent(request)

            val echoed = TranscriptSegment(
                0,
                ApprovalAnnouncer.LINE.lowercase().trimEnd('.'),
                isFinal = true,
                endpointed = true,
            )
            controller.onTranscript(Transcript(listOf(echoed)))

            assertTrue(gateway.resolved.isEmpty(), "the announcement decided the approval it announced")
        }

    @Test
    fun `speech while nothing pends resolves nothing`() =
        runTest(UnconfinedTestDispatcher()) {
            val gateway = FakeGateway()
            val controller = controller(gateway)

            val stray = TranscriptSegment(0, "approve everything", isFinal = true, endpointed = true)
            controller.onTranscript(Transcript(listOf(stray)))

            assertTrue(gateway.resolved.isEmpty())
        }

    private companion object {
        const val RUN = "run_t27"
    }
}
