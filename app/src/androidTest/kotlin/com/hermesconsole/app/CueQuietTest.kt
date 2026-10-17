package com.hermesconsole.app

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.hermesconsole.audio.AudioCapture
import com.hermesconsole.audio.AudioConfig
import com.hermesconsole.audio.AudioTrackOneShotSink
import com.hermesconsole.audio.AudioTrackSinkFactory
import com.hermesconsole.audio.BargeInDetector
import com.hermesconsole.audio.MicPipeline
import com.hermesconsole.audio.SILENCE_DBFS
import com.hermesconsole.audio.SpeechGate
import com.hermesconsole.audio.TtsPlayer
import com.hermesconsole.audio.rmsDbfs
import com.hermesconsole.hermesclient.HermesEvent
import com.hermesconsole.lcars.LcarsCue
import com.hermesconsole.lcars.LcarsCuePlayer
import com.hermesconsole.voiceclient.Transcript
import com.hermesconsole.voiceclient.VoiceLinkState
import com.hermesconsole.voiceclient.VoiceManagerClient
import com.hermesconsole.voiceclient.VoiceManagerConfig
import com.hermesconsole.wakeword.WAKE_PHRASE
import com.hermesconsole.wakeword.WakeWord
import com.hermesconsole.wakeword.WakeWordDetector
import com.hermesconsole.wakeword.WakeWordSource
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * THE REQ-33/REQ-36 MEASUREMENT (T-20), on the SM-X210 against the live VoiceManager.
 *
 * UI sound must be as inaudible to the console's own ears as its voice is: cues ride the same
 * echo-cancelled output path as TTS, so the production listeners - barge-in, wake word, live
 * STT - must hear nothing of them. This fires 50 consecutive cues through the production
 * [LcarsCuePlayer]: 20 with the console READY (the wake detector's watch), then a scripted turn
 * opens - sounding the loop's own wake/listening/dispatch cues on the way - and 30 more fire
 * during the reply's active TTS playback, where all three listeners are live at once.
 *
 * PASS: zero barge-in triggers, zero wake-word triggers, zero non-empty STT partials.
 *
 * Two honesty checks keep the zeros meaningful, as in T-15:
 *  - a positive control plays a burst of cues while the UNPROCESSED microphone listens, and the
 *    cues must be plainly loud there - silence into the room would pass every counter and prove
 *    nothing (it also evidences the acceptance's "audible": the cues clear the speech gate's
 *    energy bar in the room, not just on paper);
 *  - the turn must end back at READY with one dispatch and no stop - a barge-in or a supersede
 *    is a loud failure, never a shrug.
 *
 * Skipped unless VoiceManager's key is available (REQ-28: nothing is compiled in, nothing is
 * logged). The key comes from the console's own settings store - set it once on the settings
 * screen - because a key passed as an instrumentation argument is written to the OS log by adbd
 * itself (T-41). An explicit vmKey=/vmHost= argument still overrides, for scratch backends:
 *
 *     scripts/connected-test.sh CueQuietTest
 *
 * THE ROOM MUST BE QUIET - a person speaking during the run is a real barge-in, counted as the
 * failure it would rightly be.
 */
@RunWith(AndroidJUnit4::class)
class CueQuietTest {

    @get:Rule
    val micPermission: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.RECORD_AUDIO)

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val audioManager: AudioManager
        get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Test
    fun fifty_cues_produce_zero_barge_ins_zero_wakes_zero_echo_partials(): Unit = runBlocking {
        val backends = TestBackends.resolve()
        assumeTrue(TestBackends.NEEDS_VM, backends.vmKey.isNotBlank())
        val question = fixture(QUESTION_FILE)

        // The mic is only live while the console is actually on screen (Android 12+ hands a
        // background process silence, not an error - T-04 learned this the hard way). The debug
        // launch (T-17) is what keeps it THIS test's mic: on a store-configured device - T-41's
        // normal state - a plain launch boots the shipping console, whose wake watch holds the
        // capture path (starving this rig's control record to digital zeros) and whose live
        // detector would answer the very cues under test. The debug console never constructs a
        // capture path at all.
        val activity = Intent(context, MainActivity::class.java).putExtra("debug", true)
        ActivityScenario.launch<MainActivity>(activity).use {
            delay(1_500) // let the shell's startup self-check finish and release its recorder

            val volume = setMediaVolumeToNormal()
            Log.i(TAG, "=== REQ-33/REQ-36: CUES ARE QUIET TO THE CONSOLE ===")
            Log.i(TAG, "volume: $volume")

            // POSITIVE CONTROL: the cues must be audible in the room, or the zeros below would
            // only prove the speaker was off.
            proveTheCuesAreAudible()

            val voice = VoiceManagerClient(
                VoiceManagerConfig(
                    host = backends.vmHost,
                    port = backends.vmPort,
                    apiKey = backends.vmKey,
                ),
            )
            voice.connect()
            withTimeout(15_000) { voice.state.first { it is VoiceLinkState.Connected } }

            val mic = MicPipeline(audioManager, AudioConfig())
            val aec = mic.start()
            assertTrue("REQ-33 rests on the echo canceller, and it is not on: $aec", aec.enabled)
            Log.i(TAG, "mic: ${aec.describe()}")

            val capture = ScriptedCapture(mic)
            val wake = CountingWake(WakeWordDetector(context))
            val agent = ScriptedAgent(REPLY)
            val detector = BargeInDetector() // the production detector, production window
            // The production cue path, exactly as MainActivity builds it: the kit's player on the
            // audio module's sink factory - the same factory TTS plays through.
            val cues = LcarsCuePlayer(AudioTrackOneShotSink(audioManager, AudioConfig()))
            val loop = VoiceLoop(
                capture = capture,
                wake = wake,
                voice = voice.asVoiceLink(),
                agent = agent,
                player = TtsPlayer(AudioTrackSinkFactory(audioManager, AudioConfig())),
                sessionId = { "session-cue-quiet" },
                bargeIn = detector,
                cues = cues::play,
            )

            try {
                loop.start()
                var fired = 0

                // PHASE 1: the console is READY; only the wake detector is listening, and 20
                // cues must not wake it.
                repeat(READY_CUES) { n ->
                    cues.play(CUE_CYCLE[n % CUE_CYCLE.size])
                    fired++
                    delay(CUE_SPACING_MS)
                }
                delay(SETTLE_MS)
                assertEquals("a cue woke the console with nobody speaking", 0, wake.echoDetections.get())
                assertEquals(ConsoleState.READY, loop.state.value)

                // PHASE 2: a scripted end-to-end turn - the loop sounds its own open sequence on
                // the way in (REQ-39's vocabulary, live).
                wake.fire()
                withTimeout(5_000) { loop.state.first { it == ConsoleState.LISTENING } }
                capture.inject(question)
                withTimeout(QUESTION_TIMEOUT_MS) { loop.state.first { it == ConsoleState.THINKING } }
                val prompt = loop.turn.value.prompt
                assertTrue("STT heard nothing of the question", prompt.isNotBlank())
                Log.i(TAG, "heard \"$prompt\", speaking")

                withTimeout(SPEAK_START_TIMEOUT_MS) { loop.state.first { it == ConsoleState.SPEAKING } }

                // Everything STT has said so far belongs to the question. Anything non-blank
                // beyond this can only be playback - reply or cues - coming back through the
                // microphone, because nobody is speaking.
                val baseline = snapshot(voice.transcript.value)
                val partials = LinkedHashSet<String>()
                val watcher = launch {
                    voice.transcript.collect { transcript ->
                        echoText(transcript, baseline).forEach {
                            if (partials.add(it)) Log.w(TAG, "STT PARTIAL from the echo path: $it")
                        }
                    }
                }

                // PHASE 3: 30 cues over the active reply - REQ-33's hardest case, all three
                // listeners live while cue and TTS share the output path.
                repeat(SPEAKING_CUES) { n ->
                    assertEquals(
                        "the console stopped speaking ${n}/$SPEAKING_CUES cues into playback - " +
                            "a cue barged its own reply in: ${loop.lastBargeIn.value}",
                        ConsoleState.SPEAKING,
                        loop.state.value,
                    )
                    cues.play(CUE_CYCLE[n % CUE_CYCLE.size])
                    fired++
                    delay(CUE_SPACING_MS)
                }

                val outcome = withTimeout(PLAYBACK_TIMEOUT_MS) {
                    loop.state.first { it == ConsoleState.READY || it == ConsoleState.LISTENING }
                }
                watcher.cancelAndJoin()
                // A StateFlow conflates rapid updates: re-diff the settled transcript.
                echoText(voice.transcript.value, baseline).forEach { partials.add(it) }

                if (outcome == ConsoleState.LISTENING) {
                    org.junit.Assert.fail(
                        "the console interrupted itself with nobody speaking: ${loop.lastBargeIn.value}",
                    )
                }

                // The echo tail dies quietly, or a late trigger is still the console hearing itself.
                delay(SETTLE_MS)

                Log.i(TAG, "RESULT: $fired cues fired (+3 transition cues from the turn itself)")
                Log.i(TAG, "RESULT barge-in triggers = ${if (loop.lastBargeIn.value == null) 0 else 1} (must be 0)")
                Log.i(TAG, "RESULT wake-word triggers = ${wake.echoDetections.get()} (must be 0)")
                Log.i(TAG, "RESULT non-empty echo partials = ${partials.size} (must be 0)")
                Log.i(TAG, if (loop.lastBargeIn.value == null && wake.echoDetections.get() == 0 && partials.isEmpty()) "VERDICT: PASS" else "VERDICT: FAIL")

                assertTrue("fewer than 50 cues fired: $fired", fired >= 50)
                assertNull("a cue triggered barge-in: ${loop.lastBargeIn.value}", loop.lastBargeIn.value)
                assertEquals("a cue triggered the wake word", 0, wake.echoDetections.get())
                assertEquals("the cues produced STT partials: $partials", 0, partials.size)
                assertEquals("a run was stopped in a session nobody interrupted", 0, agent.stops.get())
                assertEquals("a turn was superseded by something STT heard", 1, agent.dispatches.get())
                assertEquals(ConsoleState.READY, loop.state.value)
            } finally {
                loop.close()
                voice.shutdown()
            }
        }
    }

    /**
     * Play every cue, loud end to end, while the UNPROCESSED microphone listens: the burst must
     * clear the speech gate's own bar in the room, or the measurement has no sound to measure.
     */
    private suspend fun proveTheCuesAreAudible() = coroutineScope {
        val record = openUnprocessedCapture()
        var peak = SILENCE_DBFS

        val player = LcarsCuePlayer(AudioTrackOneShotSink(audioManager, AudioConfig()))
        val burst = launch(Dispatchers.IO) {
            repeat(3) {
                LcarsCue.entries.forEach { cue ->
                    player.play(cue)
                    delay(CUE_SPACING_MS)
                }
            }
        }
        withContext(Dispatchers.IO) {
            val buffer = ByteArray(640)
            val deadline = System.currentTimeMillis() + CONTROL_MS
            while (System.currentTimeMillis() < deadline) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) break
                val level = rmsDbfs(buffer.copyOf(read))
                if (level > peak) peak = level
            }
        }
        burst.cancelAndJoin()
        player.close()
        record.stop()
        record.release()

        Log.i(TAG, "POSITIVE CONTROL: unprocessed mic peak ${"%.1f".format(peak)} dBFS while the cues played")
        assertTrue(
            "The unprocessed microphone heard only ${"%.1f".format(peak)} dBFS of the cue burst - " +
                "the cues are not audible in the room, so the zeros below would prove nothing. " +
                "Check the speakers, the volume, and the output route.",
            peak > SpeechGate.DEFAULT_THRESHOLD_DBFS,
        )
    }

    // -- the same rig as T-15, minus what only a 90 s soak needs -------------------------------

    /** The production capture path with the question spliced in - T-14's splice. */
    private class ScriptedCapture(private val mic: MicPipeline) : AudioCapture {
        override val config: AudioConfig get() = mic.config

        private val scripted = ConcurrentLinkedQueue<ByteArray>()

        /** Say [pcm] into the microphone: 16 kHz mono PCM16, as the fixture was synthesised. */
        fun inject(pcm: ByteArray) {
            var offset = 0
            while (offset < pcm.size) {
                val end = minOf(offset + config.chunkBytes, pcm.size)
                scripted += pcm.copyOfRange(offset, end)
                offset = end
            }
        }

        override fun frames(): Flow<ByteArray> = mic.frames().map { live -> scripted.poll() ?: live }

        override fun close() = mic.close()
    }

    /**
     * The real wake-word detector on every frame, its verdicts counted before the loop can
     * ignore them - REQ-33 is about the detector TRIGGERING. [reset] is a no-op for T-15's two
     * reasons: a hypothesis the echo half-built must keep its chance to fire, and the loop calls
     * reset() off the collector thread that owns the stream.
     */
    private class CountingWake(private val real: WakeWordDetector) : WakeWordSource {
        val echoDetections = AtomicInteger(0)
        private val scripted = Channel<WakeWord>(Channel.UNLIMITED)

        override fun detections(frames: Flow<ByteArray>): Flow<WakeWord> = merge(
            real.detections(frames).onEach {
                echoDetections.incrementAndGet()
                Log.w(TAG, "WAKE TRIGGER from the console's own playback: $it")
            },
            scripted.consumeAsFlow(),
        )

        fun fire() = check(scripted.trySend(WakeWord(WAKE_PHRASE, System.currentTimeMillis())).isSuccess)

        override fun reset() = Unit
        override fun close() = real.close()
    }

    /** The agent, scripted: a fixed reply long enough to fire 30 cues over, streamed as deltas. */
    private class ScriptedAgent(private val reply: List<String>) : Agent {
        val dispatches = AtomicInteger(0)
        val stops = AtomicInteger(0)

        override suspend fun dispatch(input: String, sessionId: String): String =
            "cue-quiet-run-${dispatches.incrementAndGet()}"

        override fun events(runId: String): Flow<HermesEvent> = flow {
            for (sentence in reply) {
                emit(HermesEvent.MessageDelta(runId = runId, delta = "$sentence "))
                delay(DELTA_PACING_MS)
            }
            emit(HermesEvent.RunCompleted(runId = runId, output = reply.joinToString(" ")))
        }

        override suspend fun stop(runId: String) {
            stops.incrementAndGet()
            Log.w(TAG, "RUN STOPPED: $runId - something cancelled a turn nobody interrupted")
        }
    }

    /** The transcript as (turn, segment) -> text, so any later non-blank change stands out. */
    private fun snapshot(transcript: Transcript): Map<String, String> =
        transcript.segments.associate { "turn ${it.turnId} segment ${it.index}" to it.text }

    private fun echoText(transcript: Transcript, baseline: Map<String, String>): List<String> =
        transcript.segments
            .filter { it.text.isNotBlank() && baseline["turn ${it.turnId} segment ${it.index}"] != it.text }
            .map { "turn ${it.turnId} segment ${it.index}: \"${it.text}\"" }

    /** The control stream: the microphone with no voice processing at all. Harness-only. */
    private fun openUnprocessedCapture(): AudioRecord {
        val minBuf = AudioRecord.getMinBufferSize(
            16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        return AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            16_000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 4,
        ).apply { startRecording() }
    }

    /** Normal listening volume: 70% of the media stream, as every playback measurement sets it. */
    private fun setMediaVolumeToNormal(): String {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val level = (max * 0.7).toInt()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
        return "$level/$max (70%)"
    }

    /** The scripted question fixture. Synthesised, not committed - see scripts/. */
    private fun fixture(name: String): ByteArray = try {
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }
    } catch (e: FileNotFoundException) {
        throw AssertionError(
            "Missing the fixture app/src/androidTest/assets/$name. It is a build input, not a " +
                "source file - synthesise it against a running VoiceManager (scripts/).",
            e,
        )
    }

    private companion object {
        const val TAG = "CueQuiet"

        /** The T-15 turn-opener: one clause, one breath, no commas. */
        const val QUESTION_FILE = "t15-question.pcm"

        const val READY_CUES = 20
        const val SPEAKING_CUES = 30

        /** Longer than the longest cue (~270 ms), so each is heard whole, 50 times over. */
        const val CUE_SPACING_MS = 400L

        /** Every voice in the set gets its turns, transitions and confirm alike. */
        val CUE_CYCLE = LcarsCue.entries

        /** The positive control listens across one three-burst cycle of the whole set. */
        const val CONTROL_MS = 8_000L

        const val QUESTION_TIMEOUT_MS = 30_000L
        const val SPEAK_START_TIMEOUT_MS = 30_000L

        /** ~45 s of reply: 30 cues at 400 ms spacing, then slack for the tail to play out. */
        const val PLAYBACK_TIMEOUT_MS = 120_000L
        const val DELTA_PACING_MS = 100L
        const val SETTLE_MS = 2_000L

        val REPLY = listOf(
            "Acknowledged. Beginning a full diagnostic sweep of the console's audio subsystems.",
            "The primary output path is nominal and the echo canceller reports convergence.",
            "Cue generators one through six are online and responding within tolerance.",
            "Playback and capture are sharing the voice communication route as designed.",
            "No anomalous energy has been detected on the barge-in gate during this interval.",
            "The wake word detector remains in its listening state with no spurious hypotheses.",
            "Speech transcription is streaming and the transcript buffer is stable.",
            "Cross-checking each cue against the detector thresholds now.",
            "Interim results indicate all interface sounds are being cancelled from the capture path.",
            "Continuing the sweep across the remaining subsystems at normal playback volume.",
            "Secondary verification confirms the output mix is within expected parameters.",
            "All interface acknowledgement tones have completed their scheduled test cycle.",
            "Final analysis is under way and the summary will be available momentarily.",
            "Diagnostic sweep complete. All audio subsystems are operating within specification.",
        )
    }
}
