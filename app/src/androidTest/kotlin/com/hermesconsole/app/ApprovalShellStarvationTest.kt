package com.hermesconsole.app

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hermesconsole.hermesclient.HermesEvent
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-02 / REQ-03: a pending approval never starves the shell.
 *
 * The 2026-08-15 incident's other casualty: the unbounded pane took the whole screen, and the
 * shell's weighted content row - the rail and the conversation - was squeezed to nothing (the
 * stray blue block in the screenshot is its remnant). With the pane bounded (T-01), the shell
 * must keep its whole frame alive around a long-command approval: the rail stands, and the
 * content row below the pane keeps visible height. The frame is the wall's own screen - the
 * test runs on the wall tablet.
 */
@RunWith(AndroidJUnit4::class)
class ApprovalShellStarvationTest {

    @get:Rule
    val compose = createComposeRule()

    private fun mount() {
        compose.setContent {
            ConsoleShell(
                state = ConsoleState.READY,
                turn = TurnLog(),
                spoken = listOf(SpokenTurn(runId = null, prompt = "", reply = REPLY)),
                approval = {
                    ApprovalPane(
                        display = ApprovalDisplay.Pending(REQUEST, deadlineAtMillis = 60_000L),
                        onDecide = {},
                        modifier = Modifier.testTag(PANE),
                        now = { 0L },
                    )
                },
            )
        }
        compose.waitForIdle()
    }

    /** The shell's content column: the scrollable holding the conversation, never the pane's. */
    private fun contentColumn() =
        compose.onNode(hasScrollAction() and hasAnyDescendant(hasText(REPLY, substring = true)))

    @Test
    fun the_pane_stands_beside_the_rail_not_across_it() {
        // T-06 / REQ-09, operator call during the T-04 walkthrough: the full-width banner
        // interrupts the side bar. The pane belongs to the content area - right of the rail,
        // still pinned above the scrolling column so no reply can push the question off screen.
        mount()

        val pane = compose.onNodeWithTag(PANE).getBoundsInRoot()
        val rail = compose.onNodeWithText("CONFIG").getBoundsInRoot()
        assertTrue(
            "the pane must start at or right of the rail's edge: pane=$pane rail=$rail",
            pane.left >= rail.right,
        )

        // Still pinned: the scrolling conversation column sits below the pane, never over it.
        val content = contentColumn().getBoundsInRoot()
        assertTrue(
            "the content column scrolls below the pane, not across it: pane=$pane content=$content",
            content.top >= pane.bottom,
        )
    }

    @Test
    fun the_rail_and_the_content_row_survive_a_long_command_approval() {
        mount()

        // The rail stands: its config segment - inert chrome on every shell - is on the wall.
        compose.onNodeWithText("CONFIG").assertIsDisplayed()

        // The content row below the pane keeps visible height: the conversation's column is
        // laid out, and the reply inside it is on the wall, not squeezed to a zero-height row.
        val content = contentColumn().getBoundsInRoot()
        assertTrue("the content column must keep visible height: $content", content.height > 0.dp)
        compose.onNode(hasText(REPLY, substring = true)).assertIsDisplayed()
    }

    private companion object {
        const val PANE = "approval-pane"

        /** A conversation line under the approval, so the content row has real content to keep. */
        const val REPLY = "The launch checklist is ready for review."

        /** The incident's shape: a 60-line script raised mid-conversation. */
        val COMMAND = buildString {
            appendLine("#!/usr/bin/env python3")
            repeat(58) { appendLine("print('launch sequence step ${it + 1}')") }
            append("print('launch sequence complete')")
        }

        val REQUEST = HermesEvent.ApprovalRequest(
            runId = "run_starvation_test",
            command = COMMAND,
            description = "execute_code",
            choices = listOf("once", "deny"),
        )
    }
}
