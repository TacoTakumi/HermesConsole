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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * THE REQ-12 MEASUREMENT (T-13), on the SM-X210 against the real Hermes and the real VoiceManager.
 *
 * The claim is that the console speaks *while the model is still writing* - and that is not a claim
 * any fake can settle. It needs both servers, the LAN between them, a real TTS engine taking real
 * time to synthesise, and a real speaker. So this runs the whole pipe and reads the two numbers
 * REQ-12 is made of off the turn log:
 *
 *   1. first audio out  <  run.completed        - the console beat the run
 *   2. first audio - first delta  <  1000 ms    - and it did not dawdle getting there
 *
 * The microphone is deliberately absent. What is being measured is the Hermes -> TTS -> speaker
 * pipe, and a human saying "hey computer" into it would add a variable (the room, the range, the
 * speaker's diction) that T-34 already measures on its own. The wake word half of the loop is
 * exercised by [WakeWordRangeTest] and, in the end, by a person standing in front of the tablet.
 *
 * The prompt asks for several sentences on purpose, and this is not padding. VoiceManager
 * synthesises a sentence only once it can see the *end* of it - it dispatches all-but-the-last
 * sentence in its buffer and holds the trailing fragment back (pipeline/tts.py) - so the last
 * sentence of any reply is necessarily synthesised after `done`, which the console only sends when
 * the run is over. A one-sentence reply therefore *cannot* be spoken before run.completed, by
 * anyone, ever. REQ-12 is a claim about streaming replies, and a streaming reply has more than one
 * sentence in it.
 *
 * Skipped unless both backend keys are available (REQ-28: nothing is compiled in, nothing is
 * logged). They come from the console's settings store (T-41: an arg-passed key is logged by
 * adbd); vmKey=/hermesKey= and the host arguments still override, for scratch backends:
 *
 *     scripts/connected-test.sh VoiceLoopTurnTest
 */
@RunWith(AndroidJUnit4::class)
class VoiceLoopTurnTest {

    private val audioManager: AudioManager
        get() = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** No microphone in this measurement - see the class comment. */
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
    fun the_console_speaks_before_the_run_that_is_talking_has_finished() = runBlocking {
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

        val loop = VoiceLoop(
            capture = NoCapture(),
            wake = NoWakeWord(),
            voice = voice.asVoiceLink(),
            agent = hermes.asAgent(),
            // The real speaker, on the configured output route: this test is audible, and is
            // meant to be.
            player = TtsPlayer(AudioTrackSinkFactory(audioManager, AudioConfig())),
            sessionId = hermes.visitSession(),
        )

        try {
            loop.start()
            loop.say(PROMPT)

            // Wait for the turn to be over: audio_end has landed and the loop is listening again.
            withTimeout(TURN_TIMEOUT_MS) {
                loop.state.first { it == ConsoleState.SPEAKING }
                loop.state.first { it == ConsoleState.READY }
            }

            val turn = loop.turn.value
            Log.i(TAG, "=== REQ-12 TURN ===")
            Log.i(TAG, "run:            ${turn.runId}")
            Log.i(TAG, "reply:          ${turn.reply.take(120)}")
            Log.i(TAG, "dispatched:     ${turn.dispatchedAtMillis}")
            Log.i(TAG, "first delta:    ${turn.firstDeltaAtMillis}")
            Log.i(TAG, "first audio:    ${turn.firstAudioAtMillis}")
            Log.i(TAG, "run.completed:  ${turn.runCompletedAtMillis}")
            Log.i(TAG, "audio played:   ${turn.audioBytes} bytes")
            Log.i(TAG, "first-audio latency: ${turn.firstAudioLatencyMillis} ms")

            assertNotNull("no message.delta ever arrived - the run said nothing", turn.firstDeltaAtMillis)
            assertNotNull("no audio ever reached the speaker", turn.firstAudioAtMillis)
            assertNotNull("the run never completed", turn.runCompletedAtMillis)
            assertTrue("nothing was actually played", turn.audioBytes > 0)

            // ASSERTION 1 - the console beat the run.
            assertTrue(
                "first audio (${turn.firstAudioAtMillis}) must precede run.completed " +
                    "(${turn.runCompletedAtMillis}) - the console spoke only after the run finished, " +
                    "which is exactly what REQ-12 forbids",
                turn.spokeBeforeRunCompleted,
            )

            // ASSERTION 2 - and got there promptly.
            //
            // If this fails, read the first sentence before blaming the console. The budget is
            // spent in two places and only one of them is ours: the model writing its opening
            // sentence, and then ~200 ms of VoiceManager synthesis (measured). The console's own
            // contribution is a socket write. A long opening sentence has been measured at 1144 ms
            // and a normal one at 513 ms, on the same code.
            val latency = turn.firstAudioLatencyMillis!!
            val opener = turn.reply.substringBefore(". ", turn.reply)
            assertTrue(
                "first-audio latency was $latency ms, over the $MAX_FIRST_AUDIO_MS ms budget. " +
                    "The model's opening sentence was ${opener.length} chars: \"$opener\" - " +
                    "VoiceManager cannot synthesise a sentence until it sees the next one start, so " +
                    "a long opener delays first audio no matter what the console does.",
                latency < MAX_FIRST_AUDIO_MS,
            )
        } finally {
            loop.close()
            voice.shutdown()
        }
    }

    private companion object {
        const val TAG = "VoiceLoopTurn"

        /**
         * Several sentences, and a short first one. Both halves of that are load-bearing, and
         * neither is the test flattering itself.
         *
         * *Several*, because a reply must stream to be spoken while it streams: VoiceManager
         * synthesises a sentence only once it can see the next one begin, so the final sentence of
         * any reply is necessarily spoken after `done` - and a one-sentence reply therefore cannot
         * be spoken before `run.completed` by anyone, ever.
         *
         * *Short first one*, because otherwise this measures the model's prose instead of the
         * console's pipe. The same code was measured at 513 ms behind a normal opening sentence and
         * 1144 ms behind a long compound one; the difference is entirely how long the model took to
         * finish its first thought, which is not what REQ-12 is about and not something the console
         * can influence. Time-to-first-audio in the field therefore depends on how the assistant
         * opens - a real lever, and one that belongs on the dispatch `instructions`, not here.
         */
        const val PROMPT =
            "In four sentences, with no preamble, lists or code, describe what a starship warp core " +
                "does. Make the FIRST sentence short - under ten words. Answer directly."

        /** REQ-12's budget: a token becomes sound in under a second on the LAN. */
        const val MAX_FIRST_AUDIO_MS = 1_000L

        /** The model has to think, and the TTS engine has to synthesise. Generous, not infinite. */
        const val TURN_TIMEOUT_MS = 120_000L
    }
}
