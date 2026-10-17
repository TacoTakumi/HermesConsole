package com.hermesconsole.voiceclient

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** Suspend until the client reaches state [S], or fail the test rather than hang. */
suspend inline fun <reified S : VoiceLinkState> VoiceManagerClient.awaitState(timeoutMs: Long = 5_000): S =
    withTimeout(timeoutMs) { state.first { it is S } as S }
