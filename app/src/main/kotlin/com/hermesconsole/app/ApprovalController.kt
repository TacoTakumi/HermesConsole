package com.hermesconsole.app

import android.util.Log
import com.hermesconsole.hermesclient.ApprovalChoice
import com.hermesconsole.hermesclient.HermesClient
import com.hermesconsole.hermesclient.HermesEvent
import com.hermesconsole.hermesclient.HermesException
import com.hermesconsole.voiceclient.Transcript
import com.hermesconsole.voiceclient.TranscriptSegment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The approval on the wall, for the shell to render (REQ-14). A pure value: the pane is a function
 * of this and nothing else, so what the controller's tests pin about it IS what the wall shows.
 */
sealed interface ApprovalDisplay {

    /** The request being displayed - the tool's command and arguments, gateway-redacted. */
    val request: HermesEvent.ApprovalRequest

    /** Awaiting a decision: the buttons are live and the auto-deny clock is running. */
    data class Pending(
        override val request: HermesEvent.ApprovalRequest,
        /** When the clock denies it, on the controller's own clock - the pane's countdown. */
        val deadlineAtMillis: Long,
    ) : ApprovalDisplay

    /** Decided - by touch, voice, or the clock. Stays on the wall for the rest of the run. */
    data class Resolved(
        override val request: HermesEvent.ApprovalRequest,
        val outcome: ApprovalOutcome,
        /** Whether the decision reached Hermes - and, when it did not, whether that mattered. */
        val delivery: ApprovalDelivery = ApprovalDelivery.LANDED,
    ) : ApprovalDisplay
}

/**
 * What became of the decision's POST (REQ-05, REQ-06). The gateway runs its own fail-closed 60 s
 * clock from its own start instant, so the console's answer structurally races it: losing that
 * race is routine, and only a POST that genuinely went nowhere may read as an alarm.
 */
enum class ApprovalDelivery {

    /** The gateway took the decision: the verdict on the wall is the one that happened. */
    LANDED,

    /**
     * The gateway refused the POST because nothing was pending any more (HTTP 409,
     * approval_not_pending): its own timeout or another resolver got there first. Benign - the
     * approval was resolved, just not by this console's answer.
     */
    RESOLVED_ELSEWHERE,

    /**
     * The POST failed outright - network, 5xx, anything but the benign race. The decision on
     * the wall never reached Hermes: REQ-14's exact failure mode, one network hop later.
     */
    FAILED,
}

/** How a displayed approval ended. The pane words these; the enum only distinguishes them. */
enum class ApprovalOutcome {

    /** A human allowed it; the run carries on to completion with the tool. */
    APPROVED,

    /** A human refused it - and the run with it: the console stops the run as well (see below). */
    DENIED,

    /** Nobody answered inside the window; the console denied it so the run could not stall. */
    AUTO_DENIED,
}

/**
 * The approval controller (T-27, REQ-14): every `approval.request` off a run's stream either gets
 * a human decision - touch or voice - or the clock denies it at [timeoutMillis]. Whichever answers
 * first is the only answer that leaves the console, and no request is ever left to stall a run
 * silently: the gateway would otherwise hold the agent blocked for its own five-minute limit.
 *
 * [onEvent] hangs off [VoiceLoop]'s event tap, like the artifact router and under the same
 * contract: cheap, total, and never trusted to matter more than the spoken reply.
 *
 * The deny/stop split is the gateway's own semantics (T-12): a posted deny does not end the run -
 * Hermes tells the model the call was refused and the turn carries on without it. So a *human*
 * deny stops the run too (the acceptance's "ends as cancelled/refused" - a person refusing the
 * tool is refusing the turn built on it), while the clock's auto-deny only unblocks the run and
 * lets the model finish its turn without the tool: the outcome is spoken, never a silent stall.
 */
class ApprovalController(
    private val gateway: ApprovalGateway,
    private val scope: CoroutineScope,
    /** The auto-deny window. 60 s (plan decision): time to walk to a wall panel, never a hung run. */
    private val timeoutMillis: Long = AUTO_DENY_MILLIS,
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * Fired once per raise, with the request taking the wall (REQ-10, REQ-11): the pane is the
     * one element on the console that expires, so its arrival is never silent. What it sounds
     * like - the cue, the spoken line - is the assembly's business, not this class's.
     */
    private val announce: (HermesEvent.ApprovalRequest) -> Unit = {},
) {

    private val _display = MutableStateFlow<ApprovalDisplay?>(null)

    /** What the wall shows about approvals right now, or null for no pane at all. */
    val display: StateFlow<ApprovalDisplay?> = _display.asStateFlow()

    /** The auto-deny clock for the pending request; disarmed the moment anything decides. */
    private var clock: Job? = null

    /**
     * The transcript as of the last feed, and its segments as of the pane rising. Only speech
     * *after* the request may decide it - the words that raised the request must not also answer
     * it, and VoiceManager keeps the whole turn's segments in every snapshot. Keyed by everything
     * that identifies a hearing - turn, index, text, endpointed - so a revision or an endpoint
     * call on an old segment counts as fresh speech, and an old turn's segment never does.
     */
    private var lastTranscript = Transcript()
    private var heardAtRaise = emptySet<SegmentKey>()

    /** Offer the controller one event off the run's stream, after the loop's own work with it. */
    @Synchronized
    fun onEvent(event: HermesEvent) {
        if (event is HermesEvent.ApprovalRequest) return raise(event)

        val shown = _display.value ?: return
        if (shown.request.runId != event.runId) {
            // Another run owns the wall now. For a Resolved pane this is the outcome retiring
            // with its turn; for a Pending one it is the only signal the pane's run is gone -
            // barge-in cancels the event collector before run.cancelled can arrive - and the
            // dead run's approval was already unwound by the stop, so nothing is posted for it.
            clear()
        } else if (event.isTerminal && shown is ApprovalDisplay.Pending) {
            // The run ended around an unanswered request (stopped, failed): nothing left to
            // answer, nothing left to stall. The clock must not deny a run that no longer exists.
            Log.i(TAG, "run ${event.runId} ended with its approval unanswered; pane down")
            clear()
        }
    }

    /**
     * The touch path: a pill on the pane. The first decision wins - a press racing the clock
     * (or a voice decision) into an already-resolved pane is a no-op, never a second POST.
     */
    fun resolve(choice: ApprovalChoice) = decide(choice, byClock = false)

    /**
     * The voice path: fed every transcript update by the assembly. Only segments that appeared -
     * or were revised - after the pane rose are read, and only once they have endpointed: a
     * mid-utterance hypothesis is not a decision (D-08 dispatches on endpoints for the same
     * reason). Vocabulary is deliberately tiny - approve / deny - and deny outranks approve in
     * one utterance, because "don't approve that, deny it" must never approve.
     */
    @Synchronized
    fun onTranscript(transcript: Transcript) {
        lastTranscript = transcript
        if (_display.value !is ApprovalDisplay.Pending) return

        val fresh = transcript.segments
            .filter { it.endpointed && SegmentKey(it) !in heardAtRaise }
            .joinToString(" ") { it.text }
        when {
            DENY_WORDS.containsMatchIn(fresh) -> decide(ApprovalChoice.DENY, byClock = false)
            APPROVE_WORDS.containsMatchIn(fresh) -> decide(ApprovalChoice.ONCE, byClock = false)
        }
    }

    @Synchronized
    private fun raise(request: HermesEvent.ApprovalRequest) {
        clock?.cancel()
        // A request replacing one still pending: deny the old one on its way out, never drop it.
        // Almost always the old run is already dead and the POST a harmless 409 - but "almost"
        // is not a guarantee, and an unanswered request is the one thing this class must never
        // leave behind (REQ-14).
        (_display.value as? ApprovalDisplay.Pending)?.let { stale ->
            Log.w(TAG, "approval for run ${stale.request.runId} replaced while pending; denying it")
            scope.launch {
                runCatching { gateway.resolve(stale.request.runId, ApprovalChoice.DENY) }
                    .onFailure { Log.w(TAG, "stale approval deny (run ${stale.request.runId}): ${it.message}") }
            }
        }
        heardAtRaise = lastTranscript.segments.mapTo(mutableSetOf()) { SegmentKey(it) }
        _display.value = ApprovalDisplay.Pending(request, deadlineAtMillis = now() + timeoutMillis)
        Log.i(TAG, "approval pane up (run ${request.runId}): ${request.command}")
        announce(request)

        clock = scope.launch {
            delay(timeoutMillis)
            decide(ApprovalChoice.DENY, byClock = true)
        }
    }

    @Synchronized
    private fun decide(choice: ApprovalChoice, byClock: Boolean) {
        val pending = _display.value as? ApprovalDisplay.Pending ?: return
        clock?.cancel()

        val outcome = when {
            byClock -> ApprovalOutcome.AUTO_DENIED
            choice == ApprovalChoice.DENY -> ApprovalOutcome.DENIED
            else -> ApprovalOutcome.APPROVED
        }
        _display.value = ApprovalDisplay.Resolved(pending.request, outcome)
        val runId = pending.request.runId
        Log.i(TAG, "approval $outcome (run $runId, choice ${choice.name})")

        scope.launch {
            // A refusal here is usually the benign race - this decision lost to the gateway's own
            // timeout or to a stop that unwound the wait, and the gateway names that precisely
            // (409, approval_not_pending) - but it can also be the network eating the POST.
            // The wall words the two apart (REQ-05, REQ-06): the race reads calm, only an answer
            // that genuinely went nowhere reads as the alarm. Retrying would only 409 again.
            runCatching { gateway.resolve(runId, choice) }
                .onFailure { failure ->
                    Log.w(TAG, "approval POST refused (run $runId): ${failure.message}")
                    markDelivery(
                        runId,
                        if (failure.isApprovalNotPending()) ApprovalDelivery.RESOLVED_ELSEWHERE else ApprovalDelivery.FAILED,
                    )
                }
            if (outcome == ApprovalOutcome.DENIED) {
                runCatching { gateway.stop(runId) }
                    .onFailure { Log.w(TAG, "denied run $runId did not stop: ${it.message}") }
            }
        }
    }

    /** The decision on the wall did not land: say what became of it on the same readout. */
    @Synchronized
    private fun markDelivery(runId: String, delivery: ApprovalDelivery) {
        _display.value = _display.value?.let { shown ->
            if (shown is ApprovalDisplay.Resolved && shown.request.runId == runId) {
                shown.copy(delivery = delivery)
            } else {
                shown
            }
        }
    }

    private fun clear() {
        clock?.cancel()
        clock = null
        _display.value = null
    }

    /**
     * The gateway's own vocabulary for the structural race (REQ-05): it had already resolved
     * this approval when the console's answer arrived. Anything else on any code stays a real
     * delivery failure - the wire is not ours to trust with a broader match.
     */
    private fun Throwable.isApprovalNotPending(): Boolean =
        this is HermesException.Http && code == 409 && "approval_not_pending" in detail

    /** Everything that identifies one hearing of one segment (D-18's turn id included). */
    private data class SegmentKey(val turnId: Int, val index: Int, val text: String, val endpointed: Boolean) {
        constructor(segment: TranscriptSegment) :
            this(segment.turnId, segment.index, segment.text, segment.endpointed)
    }

    companion object {
        private const val TAG = "ApprovalController"

        /** The plan's settled window: long enough to walk to the panel, never a hung run. */
        const val AUTO_DENY_MILLIS = 60_000L

        /**
         * The voice vocabulary. Word-bounded so "approval" (the console's own pane being read
         * aloud, or the reply discussing it) cannot decide; "denied"/"approved" are the past
         * tenses people actually speak at a screen.
         */
        private val APPROVE_WORDS = Regex("""\bapproved?\b""", RegexOption.IGNORE_CASE)
        private val DENY_WORDS = Regex("""\bden(?:y|ied)\b""", RegexOption.IGNORE_CASE)
    }
}

/**
 * What the controller needs from Hermes. A port for the same reason the loop's [Agent] is one:
 * the guarantee above - one answer, always an answer - is provable on a plain JVM with a fake.
 */
interface ApprovalGateway {

    /** Answer the approval the run is stalled on (POST /approval). */
    suspend fun resolve(runId: String, choice: ApprovalChoice)

    /** End the run (POST /stop) - the other half of a human deny. */
    suspend fun stop(runId: String)
}

/** The real gateway, as the controller sees it. */
fun HermesClient.asApprovalGateway(): ApprovalGateway = object : ApprovalGateway {
    override suspend fun resolve(runId: String, choice: ApprovalChoice) = resolveApproval(runId, choice)

    override suspend fun stop(runId: String) = this@asApprovalGateway.stop(runId)
}
