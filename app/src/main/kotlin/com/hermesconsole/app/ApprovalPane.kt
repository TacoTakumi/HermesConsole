package com.hermesconsole.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.hermesconsole.hermesclient.ApprovalChoice
import com.hermesconsole.hermesclient.HermesEvent
import com.hermesconsole.lcars.LcarsBar
import com.hermesconsole.lcars.LcarsCap
import com.hermesconsole.lcars.LcarsColors
import com.hermesconsole.lcars.LcarsMetrics
import com.hermesconsole.lcars.LcarsPill
import com.hermesconsole.lcars.LcarsSegment
import com.hermesconsole.lcars.LcarsTheme
import kotlinx.coroutines.delay

/**
 * The approval pane's socket in a shell: collects the controller's display and renders the pane
 * while there is one. What the shell hosts is this - it never sees the controller's state.
 */
@Composable
fun ApprovalHost(approvals: ApprovalController) {
    val display by approvals.display.collectAsState()
    display?.let { ApprovalPane(it, onDecide = approvals::resolve) }
}

/**
 * The approval pane itself (T-27, REQ-14): what Hermes wants to do, the buttons that answer it,
 * the countdown to the console answering for you - and, once anything has answered, the verdict.
 *
 * It renders in the alarm register - Tomato and Mars, the palette's only reds - because it is the
 * one pane on the console that is a question rather than a report, and it must read as one from
 * across a room. The shell pins it outside the scrolling content column: no amount of reply may
 * push a stalled run's question off the screen.
 *
 * The pane's height is bounded at [MaxHeightFraction] of the screen (REQ-04, the ImageApplet
 * screen-fraction idiom): the command region alone absorbs the bound and scrolls internally, so
 * the buttons and the countdown - the touch path and the voice grammar - are on the screen while
 * the question pends no matter how long the command runs (REQ-01), and every line of the command
 * stays reachable verbatim, never truncated (REQ-02).
 *
 * A pure function of [ApprovalDisplay]: what the controller's tests pin IS what the wall shows.
 */
@Composable
fun ApprovalPane(
    display: ApprovalDisplay,
    onDecide: (ApprovalChoice) -> Unit,
    modifier: Modifier = Modifier,
    now: () -> Long = System::currentTimeMillis,
) {
    val pending = display is ApprovalDisplay.Pending
    Column(
        modifier = modifier
            .fillMaxWidth()
            .sizeIn(maxHeight = (LocalConfiguration.current.screenHeightDp * MaxHeightFraction).dp),
        verticalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap),
        ) {
            LcarsPill(
                text = if (pending) "approval required" else "approval",
                color = if (pending) LcarsColors.Tomato else LcarsColors.Mars,
                cap = LcarsCap.Start,
                minWidth = 280.dp,
            )
            LcarsBar(
                segments = listOf(
                    LcarsSegment(null, LcarsColors.Mars, weight = 0.3f),
                    LcarsSegment(display.request.description.ifBlank { "tool call" }, LcarsColors.AlmondCreme),
                ),
                modifier = Modifier.weight(1f),
                capEnd = true,
            )
        }

        Column(
            modifier = Modifier.padding(start = LcarsMetrics.Gap * 5, top = LcarsMetrics.Gap),
            verticalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap * 2),
        ) {
            when (display) {
                is ApprovalDisplay.Pending -> {
                    // The command, verbatim (already redacted gateway-side): the person is
                    // approving *this*, so while the question pends it is the largest thing on
                    // the pane. The region takes whatever height the pane's cap leaves after
                    // the controls - never the controls' own room - and a command taller than
                    // that scrolls in place rather than truncating.
                    Box(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        BasicText(
                            text = display.request.command.ifBlank { display.request.description },
                            style = LcarsTheme.typography.body.copy(color = LcarsColors.AlmondCreme),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap)) {
                        display.request.buttons().forEach { choice ->
                            LcarsPill(
                                text = choice.buttonLabel,
                                color = choice.buttonColor,
                                onClick = { onDecide(choice) },
                            )
                        }
                    }
                    Countdown(display, now)
                }

                // Resolved, the pane collapses to its verdict (REQ-08): the question leaves the
                // wall with its resolution, and the rail and content row get their height back
                // while the verdict rides out the run.
                is ApprovalDisplay.Resolved -> BasicText(
                    text = display.readout(),
                    style = LcarsTheme.typography.header.copy(color = display.color),
                )
            }
        }
    }
}

/** The clock the pane runs on: seconds to the auto-deny, and the voice grammar beside it. */
@Composable
private fun Countdown(display: ApprovalDisplay.Pending, now: () -> Long) {
    var remainingSeconds by remember(display.request.runId, display.deadlineAtMillis) {
        mutableLongStateOf(display.deadlineAtMillis.secondsFrom(now()))
    }
    LaunchedEffect(display.request.runId, display.deadlineAtMillis) {
        while (true) {
            remainingSeconds = display.deadlineAtMillis.secondsFrom(now())
            delay(250)
        }
    }
    BasicText(
        text = "AUTO-DENY IN $remainingSeconds S - SAY \"APPROVE\" OR \"DENY\"",
        style = LcarsTheme.typography.readout.copy(color = LcarsColors.Mars),
    )
}

private fun Long.secondsFrom(now: Long): Long = ((this - now).coerceAtLeast(0) + 999) / 1_000

/**
 * The pane's height budget as a fraction of the screen (REQ-04 bounds it at one half). 0.45
 * rather than ImageApplet's 0.40: an approval is a blocking question, not a passive artifact, so
 * it buys the extra reading room. On the wall the pane's fixed chrome - header pill, buttons,
 * countdown, gaps - takes ~140dp, so the fraction must sit on the whole pane: the plan's
 * 0.40-0.45 on the command region alone would run the pane past half the 800dp screen.
 */
private const val MaxHeightFraction = 0.45f

/**
 * The gateway's own vocabulary as buttons, in the order it sent them (T-12): once / session /
 * always / deny. An unrecognised choice is skipped - the wire is not ours to trust - and a
 * request offering nothing usable still gets the two answers a human must always have: this
 * pane exists so a run can never stall on a missing button.
 */
private fun HermesEvent.ApprovalRequest.buttons(): List<ApprovalChoice> =
    choices.mapNotNull { wire -> ApprovalChoice.entries.firstOrNull { it.name.equals(wire, ignoreCase = true) } }
        .ifEmpty { listOf(ApprovalChoice.ONCE, ApprovalChoice.DENY) }

/** The button's word for each wire choice. APPROVE alone means once: the next call asks again. */
private val ApprovalChoice.buttonLabel: String
    get() = when (this) {
        ApprovalChoice.ONCE -> "approve"
        ApprovalChoice.SESSION -> "approve session"
        ApprovalChoice.ALWAYS -> "approve always"
        ApprovalChoice.DENY -> "deny"
    }

/** Deny wears the alarm colour; the wider grants cool off as they widen. */
private val ApprovalChoice.buttonColor: Color
    get() = when (this) {
        ApprovalChoice.ONCE -> LcarsColors.GoldenTanoi
        ApprovalChoice.SESSION -> LcarsColors.Butterscotch
        ApprovalChoice.ALWAYS -> LcarsColors.Butterscotch
        ApprovalChoice.DENY -> LcarsColors.Tomato
    }

/**
 * The verdict, in the pane's own words: how it ended, and what became of the answer. A decision
 * the gateway had already resolved (REQ-05) reads in the calm register - the race against the
 * gateway's own clock is structural, and losing it is not a delivery failure - while a POST that
 * genuinely went nowhere (REQ-06) keeps the alarm: a decision that silently vanished is REQ-14's
 * exact failure mode, one network hop later.
 */
internal fun ApprovalDisplay.Resolved.readout(): String {
    val verdict = when (outcome) {
        ApprovalOutcome.APPROVED -> "APPROVED - RUN CONTINUES"
        ApprovalOutcome.DENIED -> "DENIED - RUN STOPPED"
        ApprovalOutcome.AUTO_DENIED -> "NO ANSWER - AUTO-DENIED"
    }
    return when (delivery) {
        ApprovalDelivery.LANDED -> verdict
        ApprovalDelivery.RESOLVED_ELSEWHERE -> when (outcome) {
            ApprovalOutcome.AUTO_DENIED -> "NO ANSWER - HERMES TIMED OUT FIRST"
            ApprovalOutcome.APPROVED -> "APPROVED - HERMES HAD ALREADY RESOLVED IT"
            ApprovalOutcome.DENIED -> "DENIED - HERMES HAD ALREADY RESOLVED IT"
        }
        ApprovalDelivery.FAILED -> "$verdict - BUT THE DECISION DID NOT REACH HERMES"
    }
}

/** Only a real delivery failure wears the alarm; a resolved-elsewhere keeps its verdict's colour. */
internal val ApprovalDisplay.Resolved.color: Color
    get() = when {
        delivery == ApprovalDelivery.FAILED -> LcarsColors.Tomato
        outcome == ApprovalOutcome.APPROVED -> LcarsColors.GoldenTanoi
        else -> LcarsColors.Mars
    }
