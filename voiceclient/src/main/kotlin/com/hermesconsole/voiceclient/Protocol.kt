package com.hermesconsole.voiceclient

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * VoiceManager's inbound control frames: JSON text on the same socket that carries the audio.
 *
 * Unknown frame types and unknown fields are ignored rather than fatal - VoiceManager may grow
 * frames we do not handle yet, and a console that dies on an unrecognised message is a console that
 * dies on a server upgrade.
 */
internal val protocolJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    // Outbound frames carry their "type" as a defaulted field, and kotlinx.serialization omits
    // defaults unless told otherwise - without this the server gets a frame with no type at all.
    encodeDefaults = true
}

/**
 * A transcript update - `transcription` on the wire, and it is worth saying why, because this client
 * spent its first three tasks parsing a frame type the server has never once sent.
 *
 * The name is VoiceManager's (`TranscriptionMessage`, protocol.py), and nothing but the server can
 * settle it. A fake server built from a guess will happily agree with the guess: every test here
 * passed against `transcript`, and the console still could not hear a word anybody said, because the
 * real frame was falling into the ignore-the-unknown branch that exists to survive a server upgrade.
 * That is the cost of a lenient parser, and it is worth paying - but it means the frame *names* have
 * to be checked against the server, not against ourselves.
 *
 * [segmentIndex] is the segment's identity. VoiceManager reuses it to *revise* a segment it has
 * already sent - the speculative endpoint-hold (REQ-07): [endpoint] with [isFinal] false says "the
 * turn looks done, act on this, but I may take it back".
 *
 * [turnId] is the conversation turn the utterance *belongs to* - the turn open when its audio was
 * received, not when the frame was sent - and defaults to 0 on a connection that never opened one.
 * Turn scoping and segment revision are orthogonal: filter by [turnId], upsert by [segmentIndex]
 * (D-18).
 */
@Serializable
internal data class TranscriptFrame(
    @SerialName("segment_index") val segmentIndex: Int,
    val text: String,
    @SerialName("is_final") val isFinal: Boolean = false,
    val endpoint: Boolean = false,
    @SerialName("turn_id") val turnId: Int = 0,
) {
    fun toSegment() = TranscriptSegment(
        index = segmentIndex,
        text = text,
        isFinal = isFinal,
        // A settled segment ends the turn whether or not the frame bothered to say so.
        endpointed = endpoint || isFinal,
        turnId = turnId,
    )
}

/**
 * A TTS turn begins. [sampleRate] is declared per turn and may differ from the last one, so the
 * console reads it off the wire rather than assuming a constant (REQ-09).
 */
@Serializable
internal data class AudioStartFrame(
    @SerialName("sample_rate") val sampleRate: Int,
    @SerialName("request_id") val requestId: String? = null,
)

/** A TTS turn's audio is complete. */
@Serializable
internal data class AudioEndFrame(
    @SerialName("request_id") val requestId: String? = null,
)

/** Ask VoiceManager to stop synthesising a turn - barge-in's outbound half (T-14). */
@Serializable
internal data class TtsCancelFrame(
    @SerialName("request_id") val requestId: String,
    val type: String = "tts_cancel",
)

/**
 * Open a new conversation turn. Bare by contract - `{"type":"turn_start"}`, no payload - and the
 * server answers with [TurnStartedFrame] carrying the id it assigned (D-18).
 */
@Serializable
internal data class TurnStartFrame(
    val type: String = "turn_start",
)

/**
 * The server's ack to [TurnStartFrame]. The server owns the turn counter, so [turnId] is where the
 * client *learns* the current turn's id - it is never inferred locally.
 */
@Serializable
internal data class TurnStartedFrame(
    @SerialName("turn_id") val turnId: Int,
)

/**
 * A piece of the text to speak, pushed as the assistant generates it (REQ-12).
 *
 * VoiceManager accumulates the text per [requestId] and synthesises each sentence as soon as it can
 * see that the sentence is complete - so the console streams Hermes' tokens straight in and hears
 * the first sentence while the model is still writing the second.
 *
 * [done] is the only thing that flushes the *last* sentence: the server holds the trailing fragment
 * back because it cannot know whether more text is coming. Sending it is therefore not optional -
 * a request that never says done stays half-spoken until the server's flush timeout fires.
 */
@Serializable
internal data class TtsStreamFrame(
    @SerialName("request_id") val requestId: String,
    val text: String,
    val done: Boolean = false,
    val type: String = "tts_stream",
)

/** The frame types we act on. Anything else is ignored by design. */
internal sealed interface InboundFrame {
    data class Transcript(val frame: TranscriptFrame) : InboundFrame
    data class AudioStart(val frame: AudioStartFrame) : InboundFrame
    data class AudioEnd(val frame: AudioEndFrame) : InboundFrame
    data class TurnStarted(val frame: TurnStartedFrame) : InboundFrame
}

/** Parse a control frame, or null if it is malformed or of a type we do not handle. */
internal fun parseInboundFrame(text: String): InboundFrame? = runCatching {
    val type = protocolJson.parseToJsonElement(text).jsonObject["type"]?.jsonPrimitive?.content
    when (type) {
        "transcription" -> InboundFrame.Transcript(protocolJson.decodeFromString<TranscriptFrame>(text))
        "audio_start" -> InboundFrame.AudioStart(protocolJson.decodeFromString<AudioStartFrame>(text))
        "audio_end" -> InboundFrame.AudioEnd(protocolJson.decodeFromString<AudioEndFrame>(text))
        "turn_started" -> InboundFrame.TurnStarted(protocolJson.decodeFromString<TurnStartedFrame>(text))
        else -> null
    }
}.getOrNull()
