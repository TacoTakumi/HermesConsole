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
import com.hermesconsole.hermesclient.HermesEvent
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
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * THE T-46 / REQ-40 WINDOW, on the SM-X210 with the real clock and the kit's real cue player: one
 * wake opens a whole conversation. The reply finishes and the console keeps listening - the
 * follow-up is dispatched as the next turn in the same session with no second wake - and when the
 * re-armed window runs its length in silence, the close beep sounds from the tablet's speaker and
 * the pill drops to READY with the capture stream stopped.
 *
 * The wake, the transcript and the agent are scripted (a microphone and a gateway would put a
 * human voice and a network in a CI loop); the window itself - the timing under test - runs on
 * the device's own clock, and the cues genuinely play on the real output path. The spoken half of
 * the acceptance ("ask for the time, follow up, stay silent") remains the human check on the
 * shipping console, which wires the same loop to the real backends.
 */
@RunWith(AndroidJUnit4::class)
class FollowUpWindowTurnTest {

    private val audioManager: AudioManager
        get() = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private class SilentSink(override val sampleRateHz: Int) : AudioSink {
        override fun write(pcm: ByteArray) = Unit
        override fun stop() = Unit
        override fun close() = Unit
    }

    /** A microphone the test speaks through: frames go in by hand, silence otherwise. */
    private class InjectableCapture : AudioCapture {
        override val config = AudioConfig()
        private val pcm = Channel<ByteArray>(Channel.UNLIMITED)
        override fun frames(): Flow<ByteArray> = pcm.consumeAsFlow()

        fun inject() {
            check(pcm.trySend(ByteArray(config.chunkBytes)).isSuccess)
        }

        override fun close() = Unit
    }

    private class FireableWake : WakeWordSource {
        private val heard = Channel<WakeWord>(Channel.UNLIMITED)
        val fires = AtomicInteger(0)

        override fun detections(frames: Flow<ByteArray>): Flow<WakeWord> = flow {
            coroutineScope {
                launch { frames.collect { } }
                for (word in heard) emit(word)
            }
        }

        fun fire() {
            fires.incrementAndGet()
            check(heard.trySend(WakeWord("hey computer", System.currentTimeMillis())).isSuccess)
        }

        override fun reset() = Unit
        override fun close() {
            heard.close()
        }
    }

    /** VoiceManager reduced to acks, a scripted transcript, and a capture-frame counter. */
    private class WindowVoice : VoiceLink {
        private val _transcript = MutableStateFlow(Transcript())
        override val transcript: StateFlow<Transcript> = _transcript
        private val _tts = MutableSharedFlow<TtsEvent>(extraBufferCapacity = 64)
        override val tts: Flow<TtsEvent> = _tts
        private val started = mutableSetOf<String>()

        val openTurns = MutableStateFlow(0)
        val capturedFrames = AtomicInteger(0)

        override suspend fun turnStart(): Int = openTurns.updateAndGet { it + 1 }

        override fun sendCapture(frame: ByteArray) {
            capturedFrames.incrementAndGet()
        }

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

    /** Hermes reduced to an answer per dispatch: the window, not the gateway, is under test. */
    private class ScriptedAgent : Agent {
        val dispatched = CopyOnWriteArrayList<String>()
        val sessions = CopyOnWriteArrayList<String>()

        override suspend fun dispatch(input: String, sessionId: String): String {
            dispatched += input
            sessions += sessionId
            return "run-${dispatched.size}"
        }

        override fun events(runId: String): Flow<HermesEvent> = flow {
            emit(HermesEvent.MessageDelta(runId, "Acknowledged."))
            emit(HermesEvent.RunCompleted(runId, output = "Acknowledged."))
        }

        override suspend fun stop(runId: String) = Unit
    }

    @Test
    fun one_wake_carries_a_follow_up_and_the_window_closes_out_loud() = runBlocking {
        val cued = CopyOnWriteArrayList<LcarsCue>()
        // The real player on the real output path: the window's open figures and its close beep
        // are audible from the tablet while this runs, exactly as the wall console sounds them.
        val player = LcarsCuePlayer(AudioTrackOneShotSink(audioManager, AudioConfig()))
        val capture = InjectableCapture()
        val wake = FireableWake()
        val voice = WindowVoice()
        val agent = ScriptedAgent()
        val loop = VoiceLoop(
            capture = capture,
            wake = wake,
            voice = voice,
            agent = agent,
            player = TtsPlayer(AudioSinkFactory { SilentSink(it) }),
            sessionId = { "follow-up-window-test" },
            followUpWindowMillis = { WINDOW_MS },
            cues = { cue ->
                cued += cue
                player.play(cue)
            },
        )

        try {
            loop.start()

            // Turn one, from the one and only wake of the whole conversation.
            wake.fire()
            withTimeout(STEP_TIMEOUT_MS) { voice.openTurns.first { it == 1 } }
            voice.hear(index = 1, text = "what time is it")

            // THE WINDOW. The reply is done and the pill never dropped: the console is listening
            // on a fresh STT turn, and the mic still streams off-device.
            withTimeout(STEP_TIMEOUT_MS) { voice.openTurns.first { it == 2 } }
            assertEquals(ConsoleState.LISTENING, loop.state.value)
            capture.inject()
            withTimeout(STEP_TIMEOUT_MS) {
                while (voice.capturedFrames.get() == 0) delay(20)
            }
            Log.i(TAG, "window open after turn 1: state=${loop.state.value}, sttTurns=${voice.openTurns.value}")

            // The follow-up, mid-window, with no second wake: the next turn in the same session.
            voice.hear(index = 2, text = "and in tokyo")
            withTimeout(STEP_TIMEOUT_MS) { voice.openTurns.first { it == 3 } }
            assertEquals(ConsoleState.LISTENING, loop.state.value)
            assertEquals(listOf("what time is it", "and in tokyo"), agent.dispatched.toList())
            assertEquals(
                "both turns landed in the same session",
                listOf("follow-up-window-test", "follow-up-window-test"),
                agent.sessions.toList(),
            )
            assertEquals("one wake carried the whole conversation", 1, wake.fires.get())

            // Silence. The re-armed window runs its length on the device's own clock and closes
            // out loud: the beep, READY, and not a frame more off-device. The timeout is the
            // configured length plus grace, so a window that overran would fail right here.
            withTimeout(WINDOW_MS + CLOSE_GRACE_MS) { loop.state.first { it == ConsoleState.READY } }
            val framesBefore = voice.capturedFrames.get()
            capture.inject()
            delay(200)
            assertEquals("a closed window streams nothing off-device", framesBefore, voice.capturedFrames.get())

            Log.i(TAG, "cues, in order: $cued")
            assertEquals(
                "listening opened at the wake, the window and the re-armed window - and closed " +
                    "exactly once, when the console really stopped",
                listOf(
                    LcarsCue.LISTENING_OPEN,
                    LcarsCue.LISTENING_OPEN,
                    LcarsCue.LISTENING_OPEN,
                    LcarsCue.LISTENING_CLOSED,
                ),
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
        const val TAG = "T46FollowUpWindow"
        const val WINDOW_MS = 3_000L
        const val STEP_TIMEOUT_MS = 10_000L
        const val CLOSE_GRACE_MS = 2_000L
        const val BEEP_TAIL_MS = 1_000L
    }
}
