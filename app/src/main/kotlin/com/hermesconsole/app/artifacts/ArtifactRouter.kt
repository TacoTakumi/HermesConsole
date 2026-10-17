package com.hermesconsole.app.artifacts

import android.util.Log
import androidx.compose.runtime.Composable
import com.hermesconsole.app.splitMediaLines
import com.hermesconsole.hermesclient.HermesEvent
import com.hermesconsole.hermesclient.HermesMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * A routed artifact: the payload, married to the applet that knows how to draw it.
 *
 * This is what the shell hosts, and all the shell may know: a [title] to stack the pane under and a
 * [Content] to fill it with. Nothing here says image or markdown, so hosting a type the shell has
 * never heard of costs it zero lines - the other half of REQ-17's seam.
 */
data class ArtifactPane(val artifact: Artifact, val applet: ArtifactApplet) {

    /** The pane's heading in the content column. */
    val title: String get() = applet.title

    /** The pane's body: the applet, rendering its own payload. */
    @Composable
    fun Content() = applet.Render(artifact)
}

/**
 * The artifact router (REQ-15): watches a run's event stream for artifact tool calls and routes
 * each one to a pane by its declared type.
 *
 * An artifact arrives as a `tool.started` event for [ARTIFACT_TOOL], whose args - forwarded whole
 * by the patched gateway (REQ-37) - carry the payload, with its type declared under [TYPE_FIELD].
 * The router reads that one field, asks the [registry] for a renderer, and publishes the pane; it
 * never interprets the rest of the payload, so a new artifact type adds a registration and an
 * applet and changes nothing here (REQ-17).
 *
 * [onEvent] hangs off [com.hermesconsole.app.VoiceLoop]'s event tap - the same collector that
 * forwards `message.delta` tokens to be spoken - so it is deliberately cheap and total: a map
 * lookup on the one event it wants, an early return on everything else, and a log-and-skip for
 * anything unroutable. No crash, no blank pane, and never a stutter in the reply (REQ-15).
 */
class ArtifactRouter(private val registry: AppletRegistry = AppletRegistry.standard()) {

    private val _panes = MutableStateFlow<List<ArtifactPane>>(emptyList())

    /**
     * The routed panes, oldest first, for the shell to host. A run's artifacts accumulate; the
     * first artifact of a *new* run replaces them, so the screen keeps the last turn's panes until
     * the next turn has something to say for itself - never a blank for the gap.
     */
    val panes: StateFlow<List<ArtifactPane>> = _panes.asStateFlow()

    /**
     * Drop every pane. The between-turns rule above holds inside one session only: a session
     * entered, opened or left must not show another session's artifacts.
     */
    fun clear() {
        _panes.value = emptyList()
    }

    /** Offer the router one event off the run's stream. Everything but an artifact passes through untouched. */
    fun onEvent(event: HermesEvent) {
        val started = event as? HermesEvent.ToolStarted ?: return
        if (started.tool != ARTIFACT_TOOL) return

        // The declared type must be a string in the args dict. Anything else - missing, blank,
        // nested, JSON null - is a payload that cannot say what it is, and is skipped the same way
        // an unregistered type is: this is the wire, and the wire is not ours to trust (REQ-15).
        val type = (started.args[TYPE_FIELD] as? JsonPrimitive)?.contentOrNull?.takeUnless { it.isBlank() }
        if (type == null) {
            Log.w(TAG, "artifact tool call with no usable '$TYPE_FIELD' skipped (run ${started.runId})")
            return
        }

        val applet = registry.appletFor(type)
        if (applet == null) {
            // Logged and skipped, nothing more: the spoken reply this artifact rides beside is
            // still streaming, and an unknown pane type is not worth a crash, a blank pane, or a
            // dropped token of it (REQ-15). Registering the type is how this log line goes away.
            Log.w(TAG, "artifact type '$type' has no registered applet; skipped (run ${started.runId})")
            return
        }

        val artifact = Artifact(runId = started.runId, type = type, args = started.args)
        _panes.update { current ->
            // A new run's first artifact takes the screen; its siblings stack after it.
            current.filter { it.artifact.runId == started.runId } + ArtifactPane(artifact, applet)
        }
        Log.i(TAG, "artifact '$type' routed to ${applet.title} (run ${started.runId})")
    }

    /**
     * A `MEDIA:<target>` tag the model wrote into its reply instead of calling the artifact tool.
     * An `http(s)` target is shown as an image artifact, exactly as a render_artifact image call
     * would be. Anything else - a file path on the gateway host, which the tablet cannot fetch -
     * is logged and skipped.
     */
    fun onMedia(runId: String, target: String) {
        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            Log.w(TAG, "MEDIA target is not an http(s) URL; skipped (run $runId)")
            return
        }
        onEvent(
            HermesEvent.ToolStarted(
                runId = runId,
                tool = ARTIFACT_TOOL,
                preview = "",
                args = buildJsonObject {
                    put(TYPE_FIELD, "image")
                    put("url", target)
                },
            ),
        )
    }

    /**
     * An entered session's artifacts, from its stored history: the last turn that showed any -
     * its render_artifact calls and its http(s) MEDIA: targets - routed again, in order, exactly
     * as the live rule leaves the screen after that turn. A session that never showed one is
     * left blank.
     */
    fun restore(history: List<HermesMessage>) {
        clear()
        val turn = history.lastOrNull { message ->
            message.role == "assistant" &&
                (message.toolCalls.any { it.name == ARTIFACT_TOOL } || mediaTargets(message).isNotEmpty())
        } ?: return
        turn.toolCalls.filter { it.name == ARTIFACT_TOOL }.forEach { call ->
            onEvent(HermesEvent.ToolStarted(runId = RESTORED_RUN, tool = ARTIFACT_TOOL, preview = "", args = call.args))
        }
        mediaTargets(turn).forEach { onMedia(RESTORED_RUN, it) }
    }

    private fun mediaTargets(message: HermesMessage): List<String> =
        splitMediaLines(message.content).media.filter { it.startsWith("http://") || it.startsWith("https://") }

    internal companion object {
        /** The run id restored panes carry: the next live run's first artifact replaces them. */
        internal const val RESTORED_RUN = "restored"

        /** The custom Hermes tool that delivers artifacts (REQ-15). Its args dict is the payload. */
        internal const val ARTIFACT_TOOL = "render_artifact"

        /** The args key naming the artifact's declared type - the registry key (D-12). */
        internal const val TYPE_FIELD = "kind"

        internal const val TAG = "ArtifactRouter"
    }
}
