package com.hermesconsole.voiceclient

/** The state of the single full-duplex link to VoiceManager, as the consumer sees it. */
sealed interface VoiceLinkState {

    /** Never dialled, or closed by us. */
    data object Idle : VoiceLinkState

    data object Connecting : VoiceLinkState

    /** Upgraded and carrying audio. */
    data object Connected : VoiceLinkState

    /**
     * The link dropped for a reason that a retry could plausibly fix (REQ-06 draws the line here:
     * only [AuthFailed] is terminal). Reconnect policy lands in T-09; this state is its trigger.
     */
    data class Dropped(val code: Int?, val reason: String?) : VoiceLinkState

    /**
     * VoiceManager rejected the api_key. Terminal and visible (REQ-06): a bad credential is a
     * human's problem, and retrying it just hammers the server with the same wrong key.
     */
    data object AuthFailed : VoiceLinkState
}
