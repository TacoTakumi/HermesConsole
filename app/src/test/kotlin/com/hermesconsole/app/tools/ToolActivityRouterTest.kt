package com.hermesconsole.app.tools

import com.hermesconsole.app.artifacts.ArtifactRouter
import com.hermesconsole.hermesclient.HermesEvent
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T-02 / REQ-16, REQ-11, REQ-09: the tool-activity desk turns `tool.completed` events into strip
 * lines.
 *
 * This desk is its own type, separate from ArtifactRouter, riding the same shared tap. Each
 * `tool.completed` becomes exactly one line (REQ-16); lines of one run accumulate in one list
 * (REQ-11, one strip). And REQ-09's structural half is pinned here too: ArtifactRouter is left blind
 * to `tool.completed` - it must not sprout a second handler for it.
 */
class ToolActivityRouterTest {

    private fun toolCompleted(
        tool: String = "bash",
        result: JsonElement? = JsonPrimitive("done"),
        runId: String = "run-1",
        isError: Boolean = false,
        duration: Double = 0.0,
    ) = HermesEvent.ToolCompleted(
        runId = runId,
        tool = tool,
        durationSeconds = duration,
        isError = isError,
        result = result,
    )

    @Test
    fun `a tool completed produces exactly one strip line`() {
        val router = ToolActivityRouter()

        router.onEvent(toolCompleted(tool = "bash", result = JsonPrimitive("build passed")))

        val line = router.lines.value.single()
        assertEquals("bash", line.tool)
        assertEquals("build passed", line.body)
        assertTrue(line.summary.isNotBlank(), "the line carries a readable one-line summary")
    }

    @Test
    fun `a line carries the event's error flag and duration`() {
        val router = ToolActivityRouter()

        router.onEvent(toolCompleted(tool = "bash", isError = true, duration = 1.5))

        val line = router.lines.value.single()
        assertTrue(line.isError, "an error result tints the line")
        assertEquals(1.5, line.durationSeconds)
    }

    @Test
    fun `two tool completed events sharing a run accumulate as two lines in one list`() {
        val router = ToolActivityRouter()

        router.onEvent(toolCompleted(tool = "bash", result = JsonPrimitive("first"), runId = "run-1"))
        router.onEvent(toolCompleted(tool = "grep", result = JsonPrimitive("second"), runId = "run-1"))

        val lines = router.lines.value
        assertEquals(2, lines.size, "a run's tool calls stack as lines in one strip, not two strips")
        assertEquals(listOf("bash", "grep"), lines.map { it.tool })
    }

    @Test
    fun `a non-tool-completed event is ignored`() {
        val router = ToolActivityRouter()

        router.onEvent(HermesEvent.MessageDelta(runId = "run-1", delta = "spoken token"))
        router.onEvent(HermesEvent.RunCompleted(runId = "run-1"))

        assertTrue(router.lines.value.isEmpty(), "the desk consumes only tool.completed")
    }

    /**
     * REQ-09, as a structural fact: the artifact desk is left untouched. A `tool.completed` handed to
     * ArtifactRouter must do nothing - it has no handler for it, and this task adds none.
     */
    @Test
    fun `ArtifactRouter does not handle tool completed`() {
        val artifactRouter = ArtifactRouter()

        artifactRouter.onEvent(toolCompleted(tool = "render_artifact"))

        assertTrue(artifactRouter.panes.value.isEmpty(), "the artifact desk stays blind to tool.completed")
    }

    // ----- T-07: keep-all, per-session (REQ-11, REQ-12) -----

    /**
     * REQ-12 (revised, T-07): tool lines are never replaced. Every run's lines accumulate inline and
     * stay - the old keep-last "pointer to the last run" is gone.
     */
    @Test
    fun `tool lines accumulate across runs and are never replaced`() {
        val router = ToolActivityRouter()

        router.onEvent(toolCompleted(tool = "bash", result = JsonPrimitive("A ran"), runId = "run-A"))
        // A toolless run touches nothing, and the prior line stands.
        router.onEvent(HermesEvent.MessageDelta(runId = "run-B", delta = "no tools this turn"))
        router.onEvent(HermesEvent.RunCompleted(runId = "run-B"))
        // A new run's line is appended after the old, not in place of it.
        router.onEvent(toolCompleted(tool = "grep", result = JsonPrimitive("B ran"), runId = "run-B"))

        assertEquals(listOf("bash", "grep"), router.lines.value.map { it.tool }, "both runs' lines stay")
        assertEquals(listOf("run-A", "run-B"), router.lines.value.map { it.runId })
    }

    /** T-07: each line is tagged with the session it arrived in, off the currentSession supplier. */
    @Test
    fun `each line is tagged with the session it arrived in`() {
        var session = "sess-A"
        val router = ToolActivityRouter(currentSession = { session })

        router.onEvent(toolCompleted(tool = "bash", result = JsonPrimitive("in A")))
        session = "sess-B"
        router.onEvent(toolCompleted(tool = "grep", result = JsonPrimitive("in B")))

        assertEquals(listOf("sess-A", "sess-B"), router.lines.value.map { it.sessionId })
    }

    /** T-10: each line records where in its run's reply the tool fired, off the replyLength supplier. */
    @Test
    fun `a line records the reply offset where the tool fired`() {
        var replyLength = 0
        val router = ToolActivityRouter(replyLengthOf = { replyLength })

        replyLength = 15
        router.onEvent(toolCompleted(tool = "bash", result = JsonPrimitive("done")))

        assertEquals(15, router.lines.value.single().replyOffset, "the offset is the reply length at arrival")
    }

    /** T-07: the pure filter behind the wall's per-session scoping - only that session's lines. */
    @Test
    fun `linesForSession returns only that session's lines, empty for a session with none`() {
        val a1 = ToolLine("r1", "bash", "s", "b", false, false, 0.0, false, sessionId = "sess-A")
        val b1 = ToolLine("r2", "grep", "s", "b", false, false, 0.0, false, sessionId = "sess-B")
        val a2 = ToolLine("r3", "cat", "s", "b", false, false, 0.0, false, sessionId = "sess-A")
        val all = listOf(a1, b1, a2)

        assertEquals(listOf(a1, a2), linesForSession(all, "sess-A"), "only session A's lines, in order")
        assertEquals(listOf(b1), linesForSession(all, "sess-B"))
        assertEquals(emptyList<ToolLine>(), linesForSession(all, "sess-C"), "a session with no lines shows none")
    }

    /** REQ-13: render_artifact is already drawn as an artifact pane, so it earns no strip line. */
    @Test
    fun `a render_artifact tool completed adds no line, but any other tool does`() {
        val router = ToolActivityRouter()

        router.onEvent(toolCompleted(tool = "render_artifact", result = JsonPrimitive("shown as a pane")))
        assertTrue(router.lines.value.isEmpty(), "render_artifact is a pane, not a strip line")

        router.onEvent(toolCompleted(tool = "bash", result = JsonPrimitive("ok")))
        assertEquals(listOf("bash"), router.lines.value.map { it.tool }, "a non-excluded tool still lands a line")
    }

    /** REQ-14: a tool that ran and returned nothing still gets a line - named, and marked no-output. */
    @Test
    fun `a null result adds a no-output line naming the tool`() {
        val router = ToolActivityRouter()

        router.onEvent(toolCompleted(tool = "write_file", result = null))

        val line = router.lines.value.single()
        assertEquals("write_file", line.tool, "the line names the tool that ran")
        assertTrue(line.noOutput, "the line is marked no-output")
        assertTrue(line.summary.isNotBlank(), "an explicit no-output note, not a blank row")
        assertTrue(line.body.isBlank(), "nothing to expand")
    }
}
