package com.hermesconsole.voiceclient

/**
 * Where the connection stands with VoiceManager's turn engine. A connection that is not configured
 * for it stays [Off]. An opted-in connection is [Pending] from the moment it sends `turn_engine`
 * until the server answers: [Ready] on `turn_engine_ready`, [Refused] on an `error` frame. A ready
 * connection becomes [Lost] when the server says its turn engine stopped. A refused or lost
 * connection is a plain connection until the next dial opts in again.
 */
sealed interface TurnEngineStatus {
    data object Off : TurnEngineStatus
    data object Pending : TurnEngineStatus
    data object Ready : TurnEngineStatus

    /** The server refused the opt-in; [errorType] is its `error_type`. */
    data class Refused(val errorType: String) : TurnEngineStatus
    data object Lost : TurnEngineStatus
}

/**
 * The user's turn ended (`turn_end`). [text] joins every final since the previous turn end,
 * whatever its turn id, so a consumer that fences by turn builds its own prompt and takes this as
 * the signal. [by] is `model` or `silence`.
 */
data class TurnEnd(val turnId: Int, val text: String, val micSample: Long, val by: String)

/**
 * The user cut in over the reply [requestId] (`barge`). The server has cancelled that request:
 * no more PCM comes for it, and it must not be cancelled again.
 */
data class Barge(val requestId: String, val onsetMicSample: Long, val micSample: Long)
