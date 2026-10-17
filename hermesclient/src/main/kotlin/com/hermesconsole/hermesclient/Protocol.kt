package com.hermesconsole.hermesclient

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The /v1/runs wire format.
 *
 * Unknown keys and unknown event types are tolerated, not fatal: Hermes will grow events this
 * client does not model, and a console that dies on a server upgrade is a console that dies on a
 * wall where nobody can restart it.
 */
internal val hermesJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    // An absent option must be absent from the body, not present as null: the gateway reads
    // instructions/session_id for presence.
    explicitNulls = false
    // And inbound, the gateway writes null where this model has a default - tool.started ships
    // "preview": null for a tool with no preview formatter - and an explicit null is not an
    // absent key: without coercion the decode throws and the whole event is dropped.
    coerceInputValues = true
}

/**
 * POST /v1/runs. [input] is the turn's text; the rest is optional and stays off the wire when null.
 *
 * There is deliberately no conversation_history field: a run scoped to a session gets its context
 * from the patched gateway's own db (hermes-patch 0002, T-42), not from the client.
 */
@Serializable
internal data class RunRequest(
    val input: String,
    @SerialName("session_id") val sessionId: String? = null,
    val instructions: String? = null,
    val model: String? = null,
)

/**
 * POST /v1/runs/{id}/approval. The gateway reads one key, `choice`, and accepts only its own four
 * words for it - see [ApprovalChoice].
 */
@Serializable
internal data class ApprovalDecision(val choice: String)

/** The 202 that starts a run. Everything else in the turn hangs off [runId]. */
@Serializable
internal data class RunAccepted(
    @SerialName("run_id") val runId: String? = null,
    val status: String? = null,
)

/**
 * Parse one SSE event payload into a typed event, or null if it is malformed, untyped, or of a type
 * we do not model. Never throws - see [hermesJson].
 */
internal fun parseEvent(payload: String): HermesEvent? = runCatching {
    val obj = hermesJson.parseToJsonElement(payload).jsonObject
    when (obj["event"]?.jsonPrimitive?.contentOrNull) {
        "message.delta" -> hermesJson.decodeFromJsonElement<HermesEvent.MessageDelta>(obj)
        "tool.started" -> hermesJson.decodeFromJsonElement<HermesEvent.ToolStarted>(obj)
        "tool.completed" -> hermesJson.decodeFromJsonElement<HermesEvent.ToolCompleted>(obj)
        "reasoning.available" -> hermesJson.decodeFromJsonElement<HermesEvent.ReasoningAvailable>(obj)
        "approval.request" -> hermesJson.decodeFromJsonElement<HermesEvent.ApprovalRequest>(obj)
        "run.completed" -> hermesJson.decodeFromJsonElement<HermesEvent.RunCompleted>(obj)
        "run.failed" -> hermesJson.decodeFromJsonElement<HermesEvent.RunFailed>(obj)
        "run.cancelled" -> hermesJson.decodeFromJsonElement<HermesEvent.RunCancelled>(obj)
        else -> null
    }
}.getOrNull()

/**
 * The `text/event-stream` line grammar, fed a line at a time.
 *
 * Hermes writes one `data:` line per event and comments (`: keepalive`, `: stream closed`) between
 * them, but the reader owes the spec more than it owes today's gateway: an event's data may span
 * several `data:` lines, and other fields (`event:`, `id:`, `retry:`) may appear and are not ours
 * to act on.
 */
internal class SseFramer {

    private val data = StringBuilder()

    /** Feed one line. Returns an event's data payload when the blank line that ends it arrives. */
    fun read(line: String): String? {
        if (line.startsWith(":")) return null // a comment - the keepalive tick, and the closing note
        if (line.isEmpty()) return dispatch()

        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        if (field != "data") return null // event:, id:, retry: - not ours to act on

        // One optional space after the colon belongs to the framing, not to the value. A field with
        // no colon at all carries an empty value, which still lengthens the payload by a newline.
        val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
        if (data.isNotEmpty()) data.append('\n')
        data.append(value)
        return null
    }

    private fun dispatch(): String? {
        if (data.isEmpty()) return null
        val payload = data.toString()
        data.setLength(0)
        return payload
    }
}
