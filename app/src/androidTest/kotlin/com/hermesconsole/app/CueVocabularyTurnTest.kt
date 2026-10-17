package com.hermesconsole.app

import android.content.Context
import android.media.AudioManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hermesconsole.audio.AudioCapture
import com.hermesconsole.audio.AudioConfig
import com.hermesconsole.audio.AudioSink
import com.hermesconsole.audio.AudioSinkFactory
import com.hermesconsole.audio.AudioTrackOneShotSink
import com.hermesconsole.audio.AudioTrackSinkFactory
import com.hermesconsole.audio.TtsPlayer
import com.hermesconsole.hermesclient.HermesClient
import com.hermesconsole.hermesclient.HermesConfig
import com.hermesconsole.lcars.LcarsCue
import com.hermesconsole.lcars.LcarsCuePlayer
import com.hermesconsole.voiceclient.Transcript
import com.hermesconsole.voiceclient.TranscriptSegment
import com.hermesconsole.voiceclient.TtsEvent
import com.hermesconsole.wakeword.WakeWord
import com.hermesconsole.wakeword.WakeWordSource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * THE T-45 / REQ-39 TURN, on the SM-X210 against the real Hermes gateway: one spoken turn through
 * the shipping wake path, and the console's audible vocabulary for it is exactly two voices - the
 * multi-note open sequence when listening opens, and the single close beep when the console stops
 * listening. Nothing at the wake itself, nothing at dispatch, nothing after the utterance.
 *
 * The cues are recorded (the assertable order) and genuinely played through the kit's player on
 * the audio module's real output path (the audible half: the sequence at the top and the beep at
 * the close sound from the tablet's speaker while this runs). The wake word and the transcript are
 * scripted as in [SessionAccumulationDisplayTest] - a microphone would put a human voice in a CI
 * loop - but from the endpointed transcript onward the turn is the shipping one: real dispatch,
 * real gateway, real turn lifecycle.
 *
 * Skipped unless the gateway's key is available (REQ-28). It comes from the console's settings
 * store (T-41: an arg-passed key is logged by adbd); hermesKey=/hermesHost= still override:
 *
 *     scripts/connected-test.sh CueVocabularyTurnTest
 */
@RunWith(AndroidJUnit4::class)
class CueVocabularyTurnTest {

    private val audioManager: AudioManager
        get() = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private class NoCapture : AudioCapture {
        override val config = AudioConfig()
        override fun frames(): Flow<ByteArray> = emptyFlow()
        override fun close() = Unit
    }

    private class SilentSink(override val sampleRateHz: Int) : AudioSink {
        override fun write(pcm: ByteArray) = Unit
        override fun stop() = Unit
        override fun close() = Unit
    }

    /** The wake phrase on demand: the fire drives the shipping `onWake`. */
    private class FireableWake : WakeWordSource {
        private val heard = Channel<WakeWord>(Channel.UNLIMITED)

        override fun detections(frames: Flow<ByteArray>): Flow<WakeWord> = flow {
            coroutineScope {
                launch { frames.collect { } }
                for (word in heard) emit(word)
            }
        }

        fun fire() {
            check(heard.trySend(WakeWord("hey computer", System.currentTimeMillis())).isSuccess)
        }

        override fun reset() = Unit
        override fun close() {
            heard.close()
        }
    }

    /**
     * VoiceManager reduced to what a spoken turn needs: the turn_started ack (observable, so the
     * test can wait for the loop to open the turn before speaking into it), a transcript the test
     * scripts, and the two TTS acknowledgements that complete a turn.
     */
    private class WakeVoice : VoiceLink {
        private val _transcript = MutableStateFlow(Transcript())
        override val transcript: StateFlow<Transcript> = _transcript
        private val _tts = MutableSharedFlow<TtsEvent>(extraBufferCapacity = 64)
        override val tts: Flow<TtsEvent> = _tts
        private val started = mutableSetOf<String>()

        val openTurns = MutableStateFlow(0)

        override suspend fun turnStart(): Int = openTurns.updateAndGet { it + 1 }
        override fun sendCapture(frame: ByteArray) = Unit
        override fun cancelTts(requestId: String) = Unit

        override fun sendTtsText(requestId: String, text: String, done: Boolean) {
            if (started.add(requestId)) check(_tts.tryEmit(TtsEvent.Start(requestId, 22_050)))
            if (done) check(_tts.tryEmit(TtsEvent.End(requestId)))
        }

        fun hear(index: Int, text: String) {
            _transcript.value = Transcript(
                _transcript.value.segments.filterNot { it.index == index } +
                    TranscriptSegment(
                        index = index,
                        text = text,
                        isFinal = true,
                        endpointed = true,
                        turnId = openTurns.value,
                    ),
            )
        }
    }

    @Test
    fun one_spoken_turn_sounds_exactly_the_open_sequence_and_the_close_beep() = runBlocking {
        val backends = TestBackends.resolve()
        assumeTrue(TestBackends.NEEDS_HERMES, backends.hermesKey.isNotBlank())
        val hermes = HermesClient(
            HermesConfig(
                host = backends.hermesHost,
                port = backends.hermesPort,
                apiKey = backends.hermesKey,
            ),
        )

        val cued = CopyOnWriteArrayList<LcarsCue>()
        // The real player on the real output path: this test is audible, and is meant to be.
        val player = LcarsCuePlayer(AudioTrackOneShotSink(audioManager, AudioConfig()))
        val wake = FireableWake()
        val voice = WakeVoice()
        val loop = VoiceLoop(
            capture = NoCapture(),
            wake = wake,
            voice = voice,
            agent = hermes.asAgent(),
            player = TtsPlayer(AudioSinkFactory { SilentSink(it) }),
            sessionId = hermes.visitSession(),
            cues = { cue ->
                cued += cue
                player.play(cue)
            },
        )

        try {
            loop.start()

            // The top of the turn: the wake fires, the STT turn opens, and the one figure the
            // person hears is the open sequence - the wake's acknowledgement folded in.
            wake.fire()
            withTimeout(ACK_TIMEOUT_MS) { voice.openTurns.first { it == 1 } }
            assertEquals(ConsoleState.LISTENING, loop.state.value)
            assertEquals(
                "the open sequence, alone, at the top - the wake has no cue of its own",
                listOf(LcarsCue.LISTENING_OPEN),
                cued.toList(),
            )

            // The utterance, then the whole shipping turn against the gateway.
            voice.hear(index = 1, text = PROMPT)
            withTimeout(TURN_TIMEOUT_MS) { loop.state.first { it == ConsoleState.READY } }

            assertTrue("the gateway answered", loop.turn.value.reply.isNotBlank())
            Log.i(TAG, "turn: \"${loop.turn.value.prompt}\" -> \"${loop.turn.value.reply.take(80)}\"")
            Log.i(TAG, "cues, in order: $cued")
            assertEquals(
                "the turn's whole vocabulary: the sequence at the top, the beep at the close - " +
                    "nothing at dispatch, nothing after the utterance",
                listOf(LcarsCue.LISTENING_OPEN, LcarsCue.LISTENING_CLOSED),
                cued.toList(),
            )

            // Let the close beep finish sounding before the player is torn down.
            delay(BEEP_TAIL_MS)
        } finally {
            player.close()
            loop.close()
        }
    }

    private companion object {
        const val TAG = "T45CueVocabulary"
        const val PROMPT = "Reply with exactly: CUE CHECK COMPLETE."
        const val ACK_TIMEOUT_MS = 10_000L
        const val TURN_TIMEOUT_MS = 120_000L
        const val BEEP_TAIL_MS = 1_000L
    }
}
