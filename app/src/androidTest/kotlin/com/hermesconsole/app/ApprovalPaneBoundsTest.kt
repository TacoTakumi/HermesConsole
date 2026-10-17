package com.hermesconsole.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hermesconsole.hermesclient.HermesEvent
import com.hermesconsole.lcars.LcarsTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-01 / REQ-01, REQ-02, REQ-04: a long command must never take the answer controls off the wall.
 *
 * The bug this pins (2026-08-15, run run_5c8ec1d07d17422db4abe5fb3e7bc211): an execute_code
 * approval carrying a ~40-line script rendered it as an unbounded text, the buttons and the
 * countdown - the touch path AND the voice grammar - were pushed below the screen edge, and the
 * approval could only auto-deny. The frame here is the wall's own screen (the test runs on the
 * wall tablet), so "in-bounds" below is literally "answerable on the wall".
 *
 * The contract: the pane's total height stays at or under half the frame; every choice button the
 * gateway offered and the countdown line sit fully inside the frame; the command stays verbatim in
 * a region that scrolls internally to its last line; the reading order stays header, command,
 * buttons, countdown, with the controls below the command region's cap.
 */
@RunWith(AndroidJUnit4::class)
class ApprovalPaneBoundsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun mount(display: ApprovalDisplay = ApprovalDisplay.Pending(REQUEST, deadlineAtMillis = 60_000L)) {
        compose.setContent {
            LcarsTheme {
                Box(Modifier.fillMaxSize().testTag(FRAME)) {
                    ApprovalPane(
                        display = display,
                        onDecide = {},
                        modifier = Modifier.testTag(PANE),
                        now = { 0L },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun frameBounds(): DpRect = compose.onNodeWithTag(FRAME).getBoundsInRoot()

    /** The command region: the one scrollable inside the pane. Absent on the unbounded pane. */
    private fun commandRegion(): SemanticsNodeInteraction =
        compose.onNode(hasScrollAction() and hasAnyAncestor(hasTestTag(PANE)))

    /** The command's text node, found by the script's last line. */
    private fun commandText(): SemanticsNodeInteraction =
        compose.onNode(hasText(END_MARKER, substring = true))

    /** Displayed, and with no edge outside the frame - "reachable", not "technically on-screen". */
    private fun SemanticsNodeInteraction.assertFullyInside(frame: DpRect, what: String) {
        assertIsDisplayed()
        val bounds = getBoundsInRoot()
        assertTrue(
            "$what must sit fully inside the frame: $bounds vs frame $frame",
            bounds.top >= frame.top && bounds.bottom <= frame.bottom,
        )
    }

    /** Drive the command region's scroll to its end through its own semantics action. */
    private fun scrollCommandRegionToEnd() {
        repeat(60) {
            val range = commandRegion().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            if (range.value() >= range.maxValue() - 1f) return
            commandRegion().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 10_000f) }
            compose.waitForIdle()
        }
        val range = commandRegion().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue(
            "the command region must scroll to its end (at ${range.value()} of ${range.maxValue()})",
            range.value() >= range.maxValue() - 1f,
        )
    }

    @Test
    fun every_control_and_the_countdown_stay_on_screen_and_the_pane_keeps_under_half_the_frame() {
        mount()
        val frame = frameBounds()

        // REQ-01: every choice button the gateway offered, and the countdown line that carries
        // the voice grammar, answerable regardless of the command's length.
        BUTTONS.forEach { compose.onNodeWithText(it).assertFullyInside(frame, "the $it button") }
        compose.onNode(hasText(VOICE_GRAMMAR, substring = true))
            .assertFullyInside(frame, "the countdown line")

        // REQ-04: the pane's total height at or under half the screen.
        val pane = compose.onNodeWithTag(PANE).getBoundsInRoot()
        assertTrue(
            "the pane must keep at or under half the frame: ${pane.height} of ${frame.height}",
            pane.height <= frame.height / 2,
        )
    }

    @Test
    fun the_command_scrolls_internally_and_its_last_line_is_reachable() {
        mount()

        // REQ-02: the command is verbatim and taller than its region - the region scrolls, the
        // text is never truncated.
        val region = commandRegion()
        region.assertIsDisplayed()
        val regionBounds = region.getBoundsInRoot()
        assertTrue(
            "the command must overflow its region for this test to mean anything",
            commandText().getUnclippedBoundsInRoot().height > regionBounds.height,
        )

        scrollCommandRegionToEnd()

        // The final line is displayed: the command's bottom edge sits inside the region.
        val textAfter = commandText().getUnclippedBoundsInRoot()
        assertTrue(
            "the command's last line must be visible in the region: text $textAfter vs region $regionBounds",
            textAfter.bottom <= regionBounds.bottom + 1.dp && textAfter.bottom > regionBounds.top,
        )

        // And the controls sit below the cap, never inside or above it (REQ-01 against REQ-02:
        // scrolling the command must not be needed to reach a button).
        BUTTONS.forEach {
            val button = compose.onNodeWithText(it).getBoundsInRoot()
            assertTrue(
                "the $it button must sit below the command region's cap",
                button.top >= regionBounds.bottom,
            )
        }
    }

    @Test
    fun a_resolved_pane_collapses_to_its_verdict() {
        // T-05 / REQ-08, wall-observed during the T-04 walkthrough: the verdict rides out the
        // run (T-27), but the question leaves with its resolution - a dead approval must not
        // keep a capped 40-line script on the wall and squeeze the rail below it.
        mount(ApprovalDisplay.Resolved(REQUEST, ApprovalOutcome.DENIED))

        commandRegion().assertDoesNotExist()
        commandText().assertDoesNotExist()
        compose.onNodeWithText("DENIED - RUN STOPPED").assertIsDisplayed()

        val frame = frameBounds()
        val pane = compose.onNodeWithTag(PANE).getBoundsInRoot()
        assertTrue(
            "a resolved pane is a verdict line, not a wall: ${pane.height} of ${frame.height}",
            pane.height <= frame.height * 0.15f,
        )
    }

    @Test
    fun the_reading_order_stays_header_command_buttons_countdown() {
        mount()

        val header = compose.onNodeWithText("APPROVAL REQUIRED").getBoundsInRoot()
        val command = commandText().getUnclippedBoundsInRoot()
        val firstButton = compose.onNodeWithText(BUTTONS.first()).getBoundsInRoot()
        val countdown = compose.onNode(hasText(VOICE_GRAMMAR, substring = true)).getBoundsInRoot()

        assertTrue("header above command: header=$header command=$command", header.top < command.top)
        assertTrue("command above buttons: command=$command button=$firstButton", command.top < firstButton.top)
        assertTrue("buttons above countdown: button=$firstButton countdown=$countdown", firstButton.top < countdown.top)
    }

    private companion object {
        const val FRAME = "approval-frame"
        const val PANE = "approval-pane"

        /** The last line's marker: how the tests find the command text and prove its end shows. */
        const val END_MARKER = "END OF SCRIPT"

        /** The countdown line's voice grammar - REQ-01's second answer path. */
        const val VOICE_GRAMMAR = "SAY \"APPROVE\" OR \"DENY\""

        /** Every choice the gateway can offer, as the pills word them (the kit sets caps). */
        val BUTTONS = listOf("APPROVE", "APPROVE SESSION", "APPROVE ALWAYS", "DENY")

        /**
         * A 60-line script in the shape of the incident's: unbounded, its ~1560dp of body text
         * overflows the wall's frame in either orientation and the controls leave the screen.
         */
        val COMMAND = buildString {
            appendLine("#!/usr/bin/env python3")
            repeat(58) { appendLine("print('launch sequence step ${it + 1}')") }
            append("print('launch sequence complete')  # $END_MARKER")
        }

        val REQUEST = HermesEvent.ApprovalRequest(
            runId = "run_bounds_test",
            command = COMMAND,
            description = "execute_code",
            choices = listOf("once", "session", "always", "deny"),
        )
    }
}
