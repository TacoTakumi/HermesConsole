package com.hermesconsole.app.tools

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hermesconsole.lcars.LcarsTheme
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-08 / REQ-02, REQ-03, REQ-06, REQ-08: the compact tool-activity strip.
 *
 * A pure Compose test that mounts [ToolActivityStrip] over hand-built lines and drives the tree. The
 * strip's contract, after T-08: each line is a small `> <tool>` row - no summary, no duration, no
 * box; a tap toggles that row's body and only that row's; the expanded body is the reader's readable
 * text, never a JSON envelope (D-03), bounded with a marker when it overran; a failed call carries a
 * visible ERROR mark.
 */
@RunWith(AndroidJUnit4::class)
class ToolActivityStripTest {

    @get:Rule
    val compose = createComposeRule()

    private fun line(
        tool: String = "bash",
        summary: String = "summary",
        body: String = "body",
        truncated: Boolean = false,
        isError: Boolean = false,
        duration: Double = 1.5,
        runId: String = "run-1",
        noOutput: Boolean = false,
        sessionId: String? = null,
    ) = ToolLine(runId, tool, summary, body, truncated, isError, duration, noOutput, sessionId)

    private fun mount(vararg lines: ToolLine) {
        compose.setContent {
            LcarsTheme { Column { lines.forEach { ToolLineRow(it) } } }
        }
    }

    @Test
    fun a_row_shows_the_tool_name_with_no_error_mark_when_clean() {
        mount(line(tool = "execute_code", isError = false))

        compose.onNodeWithText("execute_code").assertIsDisplayed()
        // No error mark on a clean result.
        compose.onNodeWithText("ERROR").assertDoesNotExist()
    }

    @Test
    fun an_error_row_carries_a_visible_error_indication() {
        mount(line(tool = "bash", isError = true))

        compose.onNodeWithText("ERROR").assertIsDisplayed()
    }

    @Test
    fun a_row_is_collapsed_by_default_and_a_tap_toggles_its_body() {
        mount(line(tool = "grep", body = "UNIQUE_BODY_CONTENT"))

        // Collapsed by default: the body is not on screen.
        compose.onNodeWithText("UNIQUE_BODY_CONTENT").assertDoesNotExist()

        // A tap on the row reveals the body.
        compose.onNodeWithText("grep").performClick()
        compose.onNodeWithText("UNIQUE_BODY_CONTENT").assertIsDisplayed()

        // A second tap hides it again.
        compose.onNodeWithText("grep").performClick()
        compose.onNodeWithText("UNIQUE_BODY_CONTENT").assertDoesNotExist()
    }

    @Test
    fun toggling_one_row_does_not_change_another() {
        mount(
            line(tool = "alpha_tool", body = "ALPHA_BODY"),
            line(tool = "beta_tool", body = "BETA_BODY"),
        )

        compose.onNodeWithText("alpha_tool").performClick()

        compose.onNodeWithText("ALPHA_BODY").assertIsDisplayed()
        // The other row stayed collapsed - the toggle is per row.
        compose.onNodeWithText("BETA_BODY").assertDoesNotExist()
    }

    @Test
    fun an_opened_row_with_no_output_says_so() {
        mount(line(tool = "terminal", summary = "(no output)", body = "", noOutput = true))

        compose.onNodeWithText("(no output)").assertDoesNotExist()
        compose.onNodeWithText("terminal").performClick()
        compose.onNodeWithText("(no output)").assertIsDisplayed()
    }

    /** REQ-08: an over-cap body, read by the real reader, shows its truncation marker on screen. */
    @Test
    fun an_over_cap_body_shows_a_truncation_marker() {
        val display = ToolResultReader.read(JsonPrimitive("x".repeat(ToolResultReader.BODY_CAP * 2)), isError = false)
        mount(line(tool = "cat", summary = display.summary, body = display.body, truncated = display.truncated))

        compose.onNodeWithText("cat").performClick()
        compose.onNodeWithText("truncated", substring = true, ignoreCase = true).assertIsDisplayed()
    }

    /** REQ-06: a structured result with no text key renders readable values, never a JSON envelope. */
    @Test
    fun a_structured_no_text_result_renders_readable_text_with_no_json_envelope() {
        val result = buildJsonObject {
            put("alpha", "first value")
            put("beta", "second value")
        }
        val display = ToolResultReader.read(result, isError = false)
        mount(line(tool = "probe", summary = display.summary, body = display.body, truncated = display.truncated))

        compose.onNodeWithText("probe").performClick()
        compose.onNodeWithText("first value", substring = true).assertIsDisplayed()
        // The one thing it must never be on screen: a serialized JSON envelope (D-03).
        compose.onNodeWithText("{", substring = true).assertDoesNotExist()
        compose.onNodeWithText("\"alpha\"", substring = true).assertDoesNotExist()
    }
}
