package com.hermesconsole.app

import android.content.Context
import android.media.AudioManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.hermesconsole.audio.AudioCapture
import com.hermesconsole.audio.AudioConfig
import com.hermesconsole.audio.AudioTrackSinkFactory
import com.hermesconsole.audio.BargeInDetector
import com.hermesconsole.audio.MicPipeline
import com.hermesconsole.audio.TtsPlayer
import com.hermesconsole.hermesclient.HermesClient
import com.hermesconsole.hermesclient.HermesConfig
import com.hermesconsole.voiceclient.VoiceLinkState
import com.hermesconsole.voiceclient.VoiceManagerClient
import com.hermesconsole.voiceclient.VoiceManagerConfig
import com.hermesconsole.wakeword.WakeWord
import com.hermesconsole.wakeword.WakeWordSource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * THE REQ-04 MEASUREMENT (T-14), on the SM-X210 against the real Hermes and the real VoiceManager.
 *
 * Interrupting the console is three cancellations, in three different places, and the requirement
 * gives them 500 ms between them. Two of them are not observable from inside the tablet at all - the
 * `tts_cancel` frame VoiceManager acts on, the POST /v1/runs/{id}/stop Hermes acts on - so this runs
 * the whole loop against both live servers, speaks a real answer out of the real speaker, talks over
 * it, and reads back what actually left the device.
 *
 * The interruption is scripted, and that is deliberate: it is injected into the capture stream as
 * PCM, from a fixture synthesised once (scripts/make-bargein-speech.py). A human in the room is not
 * a fixture - their distance, their diction and their timing all move, and none of them are what
 * REQ-04 is about. What *is* under test is what the console does in the 500 ms after somebody starts
 * talking, and a recording says that far more precisely than a person can. (The microphone, the AEC
 * and the wake word are all measured on real audio elsewhere: T-04, T-15, T-34.)
 *
 * The turn asks for a long answer, and that is also load-bearing. Barge-in is armed by playback
 * exposure - 10 s of it, because a cold echo canceller leaks the console's own voice loudly enough
 * to be taken for a person (T-36) - so an interruption fired at the first sentence is one the
 * console is *right* to ignore. The test therefore waits for the guard to arm before it interrupts,
 * on the production window, with no shortcut: [BargeInDetector] here is the one the shell ships.
 *
 * Skipped unless both backend keys are available (REQ-28: nothing is compiled in, nothing is
 * logged). They come from the console's settings store (T-41: an arg-passed key is logged by
 * adbd); vmKey=/hermesKey= and the host arguments still override, for scratch backends:
 *
 *     VM_API_KEY=... python3 scripts/make-bargein-speech.py --host 127.0.0.1
 *     scripts/connected-test.sh BargeInTurnTest
 *
 * The wire log the acceptance asks for is the run's logcat (`adb logcat -s VoiceLoop BargeInTurn`),
 * read alongside VoiceManager's own log line for the cancel ("TTS cancel: session=... request_id=")
 * and Hermes' for the stop. The device audio is the room: the tablet stops mid-sentence.
 */
@RunWith(AndroidJUnit4::class)
class BargeInTurnTest {

    @get:Rule
    val micPermission: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.RECORD_AUDIO)

    private val audioManager: AudioManager
        get() = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /**
     * The production capture path, with the script spliced into it.
     *
     * The recorder still paces the stream - one 20 ms frame at a time, through the same AudioRecord
     * and the same echo canceller the console ships - and an injected utterance takes the place of
     * the room for exactly as long as it lasts. So the timing this test measures is real time: the
     * 100 ms the gate spends confirming that the energy is speech is inside the 500 ms budget, not
     * outside it.
     */
    private class ScriptedCapture(private val mic: MicPipeline) : AudioCapture {
        override val config: AudioConfig get() = mic.config

        private val scripted = ConcurrentLinkedQueue<ByteArray>()

        /** When the first frame of the current injection actually reached the loop. */
        @Volatile
        var speechStartedAtMillis: Long? = null
            private set

        /** Say [pcm] into the microphone: 16 kHz mono PCM16, as the fixture was synthesised. */
        fun inject(pcm: ByteArray) {
            speechStartedAtMillis = null
            // Whole 20 ms frames (the AEC takes 10 ms frames at 16 kHz - REQ-04 - and the capture
            // path emits two of them per chunk). A fixture rarely divides into them evenly, so a
            // trailing partial is zero-padded, never emitted raw: an incomplete frame throws in
            // the software-AEC path and would crash the run mid-measurement.
            val frame = config.chunkBytes
            var offset = 0
            while (offset < pcm.size) {
                val end = minOf(offset + frame, pcm.size)
                val chunk = pcm.copyOfRange(offset, end)
                scripted += if (chunk.size == frame) chunk else chunk.copyOf(frame)
                offset = end
            }
        }

        override fun frames(): Flow<ByteArray> = mic.frames().map { live ->
            val spoken = scripted.poll() ?: return@map live
            if (speechStartedAtMillis == null) speechStartedAtMillis = System.currentTimeMillis()
            spoken
        }

        override fun close() = mic.close()
    }

    /**
     * The wake word, scripted too - the phrase itself is measured by [WakeWordRangeTest], and
     * putting a TTS voice through the keyword spotter here would test the fixture, not the loop.
     * What matters to REQ-04 is that the turn starts the way a real one does: streaming to STT from
     * the first frame, so an interruption is transcribed from *its* first frame too.
     */
    private class ScriptedWake : WakeWordSource {
        private val heard = Channel<WakeWord>(Channel.UNLIMITED)

        override fun detections(frames: Flow<ByteArray>): Flow<WakeWord> = flow {
            coroutineScope {
                launch { frames.collect { } }
                for (word in heard) emit(word)
            }
        }

        fun fire() = check(heard.trySend(WakeWord("hey computer", System.currentTimeMillis())).isSuccess)

        override fun reset() = Unit
        override fun close() = Unit
    }

    @Test
    fun speaking_over_the_console_stops_the_speaker_the_synthesis_and_the_run(): Unit = runBlocking {
        val backends = TestBackends.resolve()
        assumeTrue(
            TestBackends.NEEDS_BOTH,
            backends.vmKey.isNotBlank() && backends.hermesKey.isNotBlank(),
        )
        val question = fixture(QUESTION_FILE)
        val interruption = fixture(INTERRUPTION_FILE)

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

        val mic = MicPipeline(audioManager, AudioConfig())
        val aec = mic.start()
        assertTrue("barge-in rests on the echo canceller, and it is not on: $aec", aec.enabled)

        val capture = ScriptedCapture(mic)
        val wake = ScriptedWake()
        // The production detector, production window. Nothing here is turned down to pass.
        val detector = BargeInDetector()
        val loop = VoiceLoop(
            capture = capture,
            wake = wake,
            voice = voice.asVoiceLink(),
            agent = hermes.asAgent(),
            player = TtsPlayer(AudioTrackSinkFactory(audioManager, AudioConfig())),
            sessionId = hermes.visitSession(),
            bargeIn = detector,
        )

        try {
            loop.start()

            // TURN N - woken, asked a question, and answering it out loud.
            wake.fire()
            withTimeout(5_000) { loop.state.first { it == ConsoleState.LISTENING } }
            capture.inject(question)
            withTimeout(QUESTION_TIMEOUT_MS) { loop.state.first { it == ConsoleState.THINKING } }
            val asked = loop.turn.value.prompt
            Log.i(TAG, "turn N: heard \"$asked\"")

            withTimeout(TURN_TIMEOUT_MS) { loop.state.first { it == ConsoleState.SPEAKING } }
            val cancelledRun = loop.turn.value.runId
            Log.i(TAG, "turn N: run $cancelledRun is speaking")

            // Wait for the echo canceller to warm up on the console's own voice. Until it has, an
            // interruption is suppressed by design (T-36) - and the console had better still be
            // speaking when it does: an answer this short would mean Hermes ignored the question.
            withTimeout(CONVERGENCE_TIMEOUT_MS) {
                while (!detector.isConverged) delay(200)
            }
            Log.i(TAG, "canceller converged; console is still ${loop.state.value}")
            assertEquals(
                "the answer ended before the canceller warmed up - nothing to interrupt",
                ConsoleState.SPEAKING,
                loop.state.value,
            )

            // And nothing has interrupted it. If the console barged in on *itself* here, the echo
            // canceller leaked - which is T-15's failure, arriving early.
            assertNull("the console interrupted itself before anybody spoke", loop.lastBargeIn.value)

            // THE INTERRUPTION.
            Log.i(TAG, "=== speaking over the console ===")
            capture.inject(interruption)
            withTimeout(BARGE_IN_TIMEOUT_MS) { loop.state.first { it == ConsoleState.LISTENING } }

            val bargeIn = withTimeout(BARGE_IN_TIMEOUT_MS) {
                loop.lastBargeIn.first { it?.runStoppedAtMillis != null }!!
            }
            val speechAt = capture.speechStartedAtMillis!!

            Log.i(TAG, "=== REQ-04 BARGE-IN ===")
            Log.i(TAG, "speech onset:      $speechAt")
            Log.i(TAG, "confirmed as speech: ${bargeIn.speechAtMillis} (+${bargeIn.speechAtMillis - speechAt} ms)")
            Log.i(TAG, "playback stopped:  ${bargeIn.playbackStoppedAtMillis} (+${bargeIn.playbackStoppedAtMillis!! - speechAt} ms)")
            Log.i(TAG, "tts_cancel sent:   ${bargeIn.ttsCancelledAtMillis} (+${bargeIn.ttsCancelledAtMillis!! - speechAt} ms) request ${bargeIn.requestId}")
            Log.i(TAG, "run stop returned: ${bargeIn.runStoppedAtMillis} (+${bargeIn.runStoppedAtMillis!! - speechAt} ms) run ${bargeIn.runId}")
            Log.i(TAG, "dropped after cancel: ${bargeIn.droppedAudioBytes} bytes of the abandoned reply")

            assertEquals("the wrong run was stopped", cancelledRun, bargeIn.runId)

            // ASSERTION 1 - all three cancels, inside 500 ms of the human opening their mouth.
            //
            // Measured from the first frame of injected speech, not from the frame the gate was
            // satisfied on: the ~100 ms the gate spends making sure this is a person and not a door
            // slamming is time the console is still talking, and the requirement is about the room,
            // not about the gate.
            val cancels = listOf(
                "AudioTrack flushed and stopped" to bargeIn.playbackStoppedAtMillis,
                "tts_cancel to VoiceManager" to bargeIn.ttsCancelledAtMillis,
                "POST /v1/runs/{id}/stop to Hermes" to bargeIn.runStoppedAtMillis,
            )
            cancels.forEach { (what, at) ->
                val latency = at - speechAt
                assertTrue(
                    "$what landed $latency ms after speech onset, over the $MAX_CANCEL_MS ms budget",
                    latency in 0..MAX_CANCEL_MS,
                )
            }

            // ASSERTION 2 - the interruption is the next turn, transcribed as turn N+1.
            val next = withTimeout(TURN_TIMEOUT_MS) {
                loop.turn.first { it.runId != null && it.runId != cancelledRun }
            }
            Log.i(TAG, "turn N+1: heard \"${next.prompt}\" -> run ${next.runId}")
            assertTrue(
                "turn N+1 was dispatched as \"${next.prompt}\" - the interruption was not what the " +
                    "console heard next (it should contain \"$INTERRUPTION_KEYWORD\")",
                next.prompt.lowercase().contains(INTERRUPTION_KEYWORD),
            )
            assertTrue(
                "turn N+1 re-dispatched the question the interruption cut off",
                !next.prompt.equals(asked, ignoreCase = true),
            )

            // ASSERTION 3 - and not one word of the abandoned answer was ever spoken.
            //
            // A cancelled request does not go quiet on the wire: VoiceManager finishes the chunk it
            // is synthesising and then closes the request with an audio_end of its own. Any of that
            // PCM the console played would be counted here - the barge-in started a fresh turn log,
            // and the new turn has not begun speaking yet (it has only just been dispatched). So a
            // single byte in it is the abandoned reply talking over the person who interrupted it.
            assertEquals(
                "the console played ${next.audioBytes} bytes of the reply it had already cancelled",
                0,
                next.audioBytes,
            )
            Log.i(TAG, "turn N+1 dispatched with 0 bytes of the abandoned reply spoken")
        } finally {
            loop.close()
            voice.shutdown()
        }
    }

    /** The scripted speech. Synthesised, not committed - see scripts/make-bargein-speech.py. */
    private fun fixture(name: String): ByteArray = try {
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }
    } catch (e: FileNotFoundException) {
        throw AssertionError(
            "Missing the speech fixture app/src/androidTest/assets/$name. It is a build input, not " +
                "a source file - synthesise it against a running VoiceManager:\n" +
                "    VM_API_KEY=... python3 scripts/make-bargein-speech.py --host <vm-host>",
            e,
        )
    }

    private companion object {
        const val TAG = "BargeInTurn"

        const val QUESTION_FILE = "bargein-question.pcm"
        const val INTERRUPTION_FILE = "bargein-interruption.pcm"

        /** A word of the interruption that no answer to the question could plausibly contain. */
        const val INTERRUPTION_KEYWORD = "shield"

        /** REQ-04's budget: the console shuts up, everywhere, within half a second of being cut off. */
        const val MAX_CANCEL_MS = 500L

        /** STT has to hear the question out and decide the speaker has finished. */
        const val QUESTION_TIMEOUT_MS = 30_000L

        /** The model has to think, and the TTS engine has to synthesise. Generous, not infinite. */
        const val TURN_TIMEOUT_MS = 120_000L

        /** 10 s of playback exposure, plus the slack of a reply that has to be that long. */
        const val CONVERGENCE_TIMEOUT_MS = 60_000L

        /** The three cancels have 500 ms; this is the timeout on ever seeing them at all. */
        const val BARGE_IN_TIMEOUT_MS = 10_000L
    }
}
