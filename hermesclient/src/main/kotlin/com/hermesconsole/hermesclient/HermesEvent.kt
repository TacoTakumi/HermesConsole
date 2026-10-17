package com.hermesconsole.hermesclient

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One event off a run's SSE stream, typed (REQ-11).
 *
 * These are the payloads Hermes' /v1/runs stream actually emits, one class per `event` value, with
 * the wire's own field names. Hermes emits more types than these (`_thinking`, subagent progress);
 * they are not forwarded to this surface or not ours to act on, and anything unrecognised is dropped
 * by the reader rather than delivered as a half-parsed shape.
 */
sealed interface HermesEvent {

    /** The run this event belongs to. Every payload carries it, and barge-in (T-14) turns on it. */
    val runId: String

    /** Does the run end here? Nothing more arrives on the stream after a terminal event. */
    val isTerminal: Boolean get() = this is RunCompleted || this is RunFailed || this is RunCancelled

    /**
     * A token of the assistant's reply, as it is generated. These are what get chunked into
     * VoiceManager's tts_stream as they arrive, so the console speaks before the run completes
     * (REQ-12), and what the reply pane renders incrementally (REQ-18).
     */
    @Serializable
    data class MessageDelta(
        @SerialName("run_id") override val runId: String,
        val delta: String,
    ) : HermesEvent

    /**
     * Hermes called a tool. [args] is the tool's argument dict - the artifact contract (REQ-37):
     * an artifact arrives as a tool call, and [preview] is a human-readable string that cannot be
     * parsed back into arguments. Empty against an unpatched gateway, which is a deployment fault,
     * not a parse one.
     */
    @Serializable
    data class ToolStarted(
        @SerialName("run_id") override val runId: String,
        val tool: String,
        val preview: String = "",
        val args: JsonObject = JsonObject(emptyMap()),
    ) : HermesEvent

    /**
     * A tool call returned. [result] is whatever the tool returned - a string, an object, or nothing
     * - so it stays a [JsonElement] until a consumer that knows the tool reads it.
     */
    @Serializable
    data class ToolCompleted(
        @SerialName("run_id") override val runId: String,
        val tool: String,
        @SerialName("duration") val durationSeconds: Double = 0.0,
        @SerialName("error") val isError: Boolean = false,
        val result: JsonElement? = null,
    ) : HermesEvent

    /** The model's reasoning, once it is available. Rendered, never spoken. */
    @Serializable
    data class ReasoningAvailable(
        @SerialName("run_id") override val runId: String,
        val text: String = "",
    ) : HermesEvent

    /**
     * Hermes is blocked on a human (REQ-14). The run stays stalled until a decision is posted back,
     * so this event must never be dropped on the floor: the console raises the approval pane, and
     * auto-denies rather than leave the run hanging (T-27).
     *
     * [command] and [description] are already redacted gateway-side. [choices] is what the gateway
     * will accept back: once / session / always / deny.
     */
    @Serializable
    data class ApprovalRequest(
        @SerialName("run_id") override val runId: String,
        val command: String = "",
        val description: String = "",
        val choices: List<String> = emptyList(),
    ) : HermesEvent

    /** The run finished. [output] is the whole reply, which the deltas already streamed. */
    @Serializable
    data class RunCompleted(
        @SerialName("run_id") override val runId: String,
        val output: String = "",
        val usage: HermesUsage? = null,
        /**
         * Which provider and model served the run, as upstream reports it: top-level beside
         * usage. Patched gateways also carried it inside usage ([HermesUsage.runtime]); readers
         * prefer this one and fall back to that during the switch.
         */
        val runtime: HermesRuntime? = null,
    ) : HermesEvent

    /** The run died. [error] is redacted gateway-side. */
    @Serializable
    data class RunFailed(
        @SerialName("run_id") override val runId: String,
        val error: String = "",
    ) : HermesEvent

    /** The run was stopped - by barge-in (T-14), or by a turn that was superseded (T-17). */
    @Serializable
    data class RunCancelled(
        @SerialName("run_id") override val runId: String,
    ) : HermesEvent
}

/**
 * The answer to an [HermesEvent.ApprovalRequest] - the whole vocabulary the gateway accepts, and
 * nothing else: it 400s anything it does not know, so this is an enum rather than a string a caller
 * can misspell into a stalled run.
 *
 * The four are exactly the `choices` the request arrives with, so the approval pane can render its
 * buttons straight off them (REQ-14).
 */
enum class ApprovalChoice(internal val wire: String) {

    /** Allow this one call. The next one asks again. */
    ONCE("once"),

    /** Allow this pattern for the rest of the Hermes session. */
    SESSION("session"),

    /** Allow this pattern permanently - Hermes remembers it past this session. */
    ALWAYS("always"),

    /** Refuse. Hermes tells the model the call was denied and the run carries on without it. */
    DENY("deny"),
}

/** Token accounting for a completed run. */
@Serializable
data class HermesUsage(
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
    /**
     * Which provider and model actually served the run (T-07, REQ-08), when the gateway reports
     * it. Additive and safe: the server already emits this object inside usage, unknown fields
     * around and inside it are ignored by design, and a payload without it is a null - so every
     * stream that parsed before parses identically now.
     */
    val runtime: HermesRuntime? = null,
)

/** The run's effective runtime as the gateway sanitises it: ids only, never credentials. */
@Serializable
data class HermesRuntime(
    val provider: String? = null,
    val model: String? = null,
)
