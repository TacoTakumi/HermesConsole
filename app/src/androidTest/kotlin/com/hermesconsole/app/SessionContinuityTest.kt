package com.hermesconsole.app

import android.content.Context
import android.media.AudioManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hermesconsole.audio.AudioCapture
import com.hermesconsole.audio.AudioConfig
import com.hermesconsole.audio.AudioTrackSinkFactory
import com.hermesconsole.audio.TtsPlayer
import com.hermesconsole.hermesclient.HermesClient
import com.hermesconsole.hermesclient.HermesConfig
import com.hermesconsole.hermesclient.createSession
import com.hermesconsole.voiceclient.VoiceLinkState
import com.hermesconsole.voiceclient.VoiceManagerClient
import com.hermesconsole.voiceclient.VoiceManagerConfig
import com.hermesconsole.wakeword.WakeWord
import com.hermesconsole.wakeword.WakeWordSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * THE REQ-23 CONTINUITY CHECK (T-22), on the SM-X210 against the real Hermes and VoiceManager:
 * a turn in session A, a detour through session B, and back - A's reply must remember A's past
 * and B's must not know it.
 *
 * This is the console's own dispatch path end to end: the loop's session provider is read at
 * each dispatch, the run carries only the utterance and the session id, the patched gateway
 * (hermes-patch 0002, T-42) assembles the context from that session's stored conversation, and
 * the reply is spoken through the real TTS pipe. The microphone is deliberately absent, as in
 * [VoiceLoopTurnTest]: what is under test is session scoping, and a human's diction adds a
 * variable T-34 already measures. The wake-word-to-turn path is the same [VoiceLoop.say] entry
 * from the transcript onward.
 *
 * Skipped unless both backend keys are available (REQ-28). They come from the console's settings
 * store (T-41: an arg-passed key is logged by adbd); vmKey=/hermesKey= still override:
 *
 *     scripts/connected-test.sh SessionContinuityTest
 */
@RunWith(AndroidJUnit4::class)
class SessionContinuityTest {

    private val audioManager: AudioManager
        get() = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private class NoCapture : AudioCapture {
        override val config = AudioConfig()
        override fun frames(): Flow<ByteArray> = emptyFlow()
        override fun close() = Unit
    }

    private class NoWakeWord : WakeWordSource {
        override fun detections(frames: Flow<ByteArray>): Flow<WakeWord> = emptyFlow()
        override fun reset() = Unit
        override fun close() = Unit
    }

    @Test
    fun a_turn_in_session_A_survives_a_detour_through_session_B() = runBlocking {
        val backends = TestBackends.resolve()
        assumeTrue(
            TestBackends.NEEDS_BOTH,
            backends.vmKey.isNotBlank() && backends.hermesKey.isNotBlank(),
        )

        val voice = VoiceManagerClient(
            VoiceManagerConfig(
                host = backends.vmHost,
                port = backends.vmPort,
                apiKey = backends.vmKey,
            ),
        )
        val hermes = HermesClient(
            HermesConfig(
                host = backends.hermesHost,
                port = backends.hermesPort,
                apiKey = backends.hermesKey,
            ),
        )

        voice.connect()
        withTimeout(15_000) { voice.state.first { it is VoiceLinkState.Connected } }

        // Two fresh conversations, made exactly as the ready screen makes one (REQ-23).
        val a = hermes.createSession(title = "continuity A")
        val b = hermes.createSession(title = "continuity B")
        Log.i(TAG, "sessions: A=${a.id} B=${b.id}")

        // The ready screen's switch, as the loop sees it: a provider read at each dispatch.
        var current = a.id
        val loop = VoiceLoop(
            capture = NoCapture(),
            wake = NoWakeWord(),
            voice = voice.asVoiceLink(),
            agent = hermes.asAgent(),
            player = TtsPlayer(AudioTrackSinkFactory(audioManager, AudioConfig())),
            sessionId = { current },
        )

        try {
            loop.start()

            current = a.id
            val planted = turn(loop, "The codeword for this conversation is TANGO51. Reply with exactly: OK.")
            Log.i(TAG, "A turn 1 reply: ${planted.take(120)}")

            current = b.id
            val detour = turn(
                loop,
                "What codeword was given earlier in this conversation? " +
                    "Reply with only the codeword, or exactly NONE if none was given.",
            )
            Log.i(TAG, "B reply: ${detour.take(120)}")
            assertFalse(
                "session B must not know session A's codeword - got: $detour",
                detour.contains("TANGO51", ignoreCase = true),
            )

            current = a.id
            val recalled = turn(
                loop,
                "What codeword was given earlier in this conversation? " +
                    "Reply with only the codeword, or exactly NONE if none was given.",
            )
            Log.i(TAG, "A turn 2 reply: ${recalled.take(120)}")
            assertTrue(
                "returning to session A must recall its codeword - got: $recalled",
                recalled.contains("TANGO51", ignoreCase = true),
            )
        } finally {
            loop.close()
            voice.shutdown()
        }
    }

    /** One typed turn, driven to completion: dispatched, spoken, and the loop READY again. */
    private suspend fun turn(loop: VoiceLoop, prompt: String): String {
        loop.say(prompt)
        withTimeout(TURN_TIMEOUT_MS) {
            loop.state.first { it != ConsoleState.READY }
            loop.state.first { it == ConsoleState.READY }
        }
        return loop.turn.value.reply
    }

    private companion object {
        const val TAG = "SessionContinuity"

        /** The model has to think three times and the TTS has to speak. Generous, not infinite. */
        const val TURN_TIMEOUT_MS = 120_000L
    }
}
