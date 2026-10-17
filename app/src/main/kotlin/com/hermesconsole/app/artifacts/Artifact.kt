package com.hermesconsole.app.artifacts

import kotlinx.serialization.json.JsonObject

/**
 * One artifact, as it came off the wire (REQ-15).
 *
 * An artifact is a tool call: Hermes invokes the console's artifact tool, and the tool's argument
 * dict - forwarded whole by the patched gateway (REQ-37) - is the payload. [type] is the artifact's
 * declared type, read out of that dict, and it is the only field the routing ever interprets. What
 * the rest of [args] means belongs entirely to the applet registered for the type, so a new payload
 * shape never touches the router (REQ-17).
 */
data class Artifact(
    /** The run that produced this artifact. Pane lifetime hangs off it: a new run's panes replace an old run's. */
    val runId: String,

    /** The declared type - the registry key that picks the applet (D-12). */
    val type: String,

    /** The artifact tool's whole argument dict, exactly as the gateway forwarded it. */
    val args: JsonObject,
)
