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
import com.hermesconsole.lcars.LcarsCue
import com.hermesconsole.voiceclient.Transcript
import com.hermesconsole.voiceclient.TtsEvent
import com.hermesconsole.wakeword.WakeWord
import com.hermesconsole.wakeword.WakeWordSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * T-50 on the SM-X210: a turn against an unreachable Hermes ends as the error voice at READY, and
 * the process does not die (REQ-39, REQ-26's no-death-in-logcat clause).
 *
 * The JVM tests (VoiceLoopTest) settle the loop's logic with a throwing fake; this settles the one
 * thing a fake cannot - that the REAL HermesClient's connection failure, surfacing through real
 * OkHttp on real ART, is caught before it reaches the process's uncaught handler and turns into the
 * error voice instead. What T-50 fixes is entirely Hermes-side, so the microphone and STT half are
 * deliberately absent (as in [VoiceLoopTurnTest]): the turn is driven with say() straight at a dead
 * local port, which refuses the connection at once. No backend key and no live gateway are touched -
 * the failure is manufactured on the device itself, so this rig needs neither and never runs skipped.
 *
 *     scripts/connected-test.sh DeadHermesTurnTest
 */
@RunWith(AndroidJUnit4::class)
class DeadHermesTurnTest {

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

    /** The turn dies at dispatch, before STT or TTS is ever touched: this link only has to exist. */
    private class IdleVoiceLink : VoiceLink {
        override val transcript: StateFlow<Transcript> = MutableStateFlow(Transcript())
        override val tts: Flow<TtsEvent> = emptyFlow()
        override suspend fun turnStart(): Int = 1
        override fun sendCapture(frame: ByteArray) = Unit
        override fun sendTtsText(requestId: String, text: String, done: Boolean) = Unit
        override fun cancelTts(requestId: String) = Unit
    }

    @Test
    fun a_turn_against_a_dead_hermes_speaks_the_error_and_returns_to_ready() = runBlocking {
        // A local port with nothing behind it: OkHttp's connect is refused at once, so dispatch
        // throws the real ConnectException the field sees when the gateway is down - no timeout wait,
        // no network, no key that a live backend would demand.
        val hermes = HermesClient(HermesConfig(host = "127.0.0.1", port = DEAD_PORT, apiKey = "unused"))

        val cues = ConcurrentLinkedQueue<LcarsCue>()
        val loop = VoiceLoop(
            capture = NoCapture(),
            wake = NoWakeWord(),
            voice = IdleVoiceLink(),
            agent = hermes.asAgent(),
            player = TtsPlayer(AudioTrackSinkFactory(audioManager, AudioConfig())),
            // A constant, not visitSession(): the dead gateway must fail at DISPATCH - a provider that
            // tried to create the session against it would fail first and change the test's subject.
            sessionId = { "session-dead-hermes" },
            cues = { cues += it },
        )

        try {
            loop.start()
            loop.say(PROMPT)

            // The failure path must run and hand the floor back on its own. Waiting on the error
            // landing on the log first, then READY, sidesteps the THINKING flicker being conflated
            // away on the StateFlow - and reaching this line at all is the no-death evidence: an
            // uncaught dispatch throw would have taken the process (and this test) down before now.
            withTimeout(TURN_TIMEOUT_MS) {
                loop.turn.first { it.error != null }
                loop.state.first { it == ConsoleState.READY }
            }

            val turn = loop.turn.value
            Log.i(TAG, "=== T-50 DEAD-HERMES TURN ===")
            Log.i(TAG, "state:  ${loop.state.value}")
            Log.i(TAG, "error:  ${turn.error}")
            Log.i(TAG, "reply:  \"${turn.reply}\"")
            Log.i(TAG, "cues:   $cues")

            assertEquals("the console did not return to READY", ConsoleState.READY, loop.state.value)
            assertNotNull("the failure was not recorded on the turn log", turn.error)
            assertTrue("the error voice never played - cues were $cues", cues.contains(LcarsCue.ERROR))
            assertTrue(
                "nothing was dispatched, yet the turn claims a spoken reply: \"${turn.reply}\"",
                turn.reply.isEmpty(),
            )
        } finally {
            loop.close()
        }
    }

    private companion object {
        const val TAG = "DeadHermesTurn"

        /** Nothing listens here; the console dials it only to be refused. */
        const val DEAD_PORT = 9

        const val PROMPT = "status report"

        /** A refused connection returns in milliseconds; this is slack, not a wait. */
        const val TURN_TIMEOUT_MS = 20_000L
    }
}
