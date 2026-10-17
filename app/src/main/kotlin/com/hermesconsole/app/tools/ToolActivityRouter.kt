package com.hermesconsole.app.tools

import android.util.Log
import com.hermesconsole.app.artifacts.ArtifactRouter
import com.hermesconsole.hermesclient.HermesEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * One line in the tool-activity strip: a single tool call, read out of its `tool.completed` event.
 *
 * The strip (T-05) draws a row from this - the [tool] name, the one-line [summary], the [duration],
 * an error tint when [isError] - and expands it to [body] (with a marker when [truncated]). [noOutput]
 * marks a tool that ran and returned nothing to show, so the strip can say so rather than draw a blank
 * row. [runId] is the run that produced the line; the strip's lifetime hangs off it (T-03).
 */
data class ToolLine(
    val runId: String,
    val tool: String,
    val summary: String,
    val body: String,
    val truncated: Boolean,
    val isError: Boolean,
    val durationSeconds: Double,
    val noOutput: Boolean,
    /** The session this line arrived in (T-07), or null off the wall. The wall shows only its own. */
    val sessionId: String? = null,
    /**
     * Where in this run's reply the tool fired (T-10): the reply's length at the moment the
     * `tool.completed` arrived, so the wall can weave the line back into the reply at that point
     * (deltas land in the reply before the tap sees the event). 0 for a tool that fired before any
     * reply text.
     */
    val replyOffset: Int = 0,
)

/** Only [session]'s lines, in order (T-07): the pure filter behind the wall's per-session scoping. */
fun linesForSession(lines: List<ToolLine>, session: String?): List<ToolLine> =
    lines.filter { it.sessionId == session }

/**
 * The tool-activity desk (REQ-09, REQ-16): watches a run's event stream for `tool.completed` and
 * turns each one into a line in a single strip.
 *
 * This is a distinct desk from [com.hermesconsole.app.artifacts.ArtifactRouter], deliberately: the
 * artifact desk reads `tool.started` args for the one artifact tool, and is left untouched here (D-04,
 * REQ-09). This desk reads the *completed* side - the result a tool returned - and hands it to
 * [ToolResultReader], which does the un-double-encoding and the never-a-blob work (D-03). A run's
 * lines accumulate into one list, so the strip is one pane with many rows, never many panes (REQ-11).
 *
 * [onEvent] hangs off the same tap that forwards `message.delta` tokens to be spoken, so it is total:
 * it wants exactly one event type, early-returns on the rest, and its one build step is wrapped so a
 * malformed result can never throw a stutter into the reply (the global constraint).
 */
class ToolActivityRouter(
    /**
     * Which session a line belongs to (T-07): read at the moment a `tool.completed` arrives, when the
     * run producing it is the current one. The wall passes its live session; off the wall it is null.
     */
    private val currentSession: () -> String? = { null },
    /**
     * The live reply's length (T-10): read when a `tool.completed` arrives to mark where in the run's
     * reply the tool fired. The wall passes its live turn's reply length; off the wall it is 0.
     */
    private val replyLengthOf: () -> Int = { 0 },
) {

    private val _lines = MutableStateFlow<List<ToolLine>>(emptyList())

    /** The strip's lines, oldest first, for the shell to host as one pane. */
    val lines: StateFlow<List<ToolLine>> = _lines.asStateFlow()

    /** Offer the desk one event off the run's stream. Everything but a `tool.completed` passes through. */
    fun onEvent(event: HermesEvent) {
        val completed = event as? HermesEvent.ToolCompleted ?: return

        // render_artifact is already on screen as an artifact pane; it earns no second line (REQ-13).
        if (completed.tool == ArtifactRouter.ARTIFACT_TOOL) return

        try {
            val display = ToolResultReader.read(completed.result, completed.isError)
            val noOutput = display.body.isBlank()
            val line = ToolLine(
                runId = completed.runId,
                tool = completed.tool,
                // A tool that ran and returned nothing still gets a named line, marked so the strip
                // says so rather than drawing a blank row (REQ-14).
                summary = if (noOutput) NO_OUTPUT_NOTE else display.summary,
                body = display.body,
                truncated = display.truncated,
                isError = completed.isError,
                durationSeconds = completed.durationSeconds,
                noOutput = noOutput,
                sessionId = currentSession(),
                replyOffset = replyLengthOf(),
            )
            // Keep-all (REQ-12, revised T-07): every line stays, appended in order. Lines are scoped
            // to their session by [sessionId], and the wall filters to the current one - so nothing is
            // ever replaced, and an older session never shows a newer run's tools.
            _lines.update { current -> current + line }
        } catch (t: Throwable) {
            // A total desk: a result the reader could not read is a dropped line, never a dropped
            // token of the reply this desk rides beside (REQ-09 global constraint).
            Log.w(TAG, "tool.completed for '${completed.tool}' skipped (run ${completed.runId})", t)
        }
    }

    internal companion object {
        internal const val TAG = "ToolActivityRouter"

        /** The collapsed line for a tool that ran and returned nothing to show (REQ-14). */
        internal const val NO_OUTPUT_NOTE = "(no output)"
    }
}
