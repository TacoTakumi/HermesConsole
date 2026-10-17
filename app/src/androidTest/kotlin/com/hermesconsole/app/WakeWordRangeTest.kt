package com.hermesconsole.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.hermesconsole.audio.AudioConfig
import com.hermesconsole.audio.MicPipeline
import com.hermesconsole.audio.SILENCE_DBFS
import com.hermesconsole.audio.rmsDbfs
import com.hermesconsole.wakeword.WakeWord
import com.hermesconsole.wakeword.WakeWordDetector
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * THE RANGE MEASUREMENT (T-34, REQ-01).
 *
 * The wake word is the console's only door: if "hey computer" does not carry from across the room,
 * the tablet is a touchscreen. So this measures the real thing - a human voice, at 2 m, into the
 * wall-mounted tablet's own microphone, through the AEC-processed capture stream the console
 * actually listens on. Nothing is simulated, and no recorded fixture stands in for a person: a
 * fixture played from a speaker measures the speaker, not the room.
 *
 * The phrase is two words because one did not work. "computer" alone was measured across four live
 * runs and every knob the library has, and never beat 7 of 10 (D-14a) - three BPE tokens are simply
 * not enough acoustic evidence for this spotter. That history is why this harness is so defensive:
 * most of it exists because some earlier version of it lied.
 *
 * PASS: 8 of 10 spoken utterances detected, each within 1 s of the speaker FINISHING the phrase.
 *
 * HUMAN IN THE LOOP. This test cannot run unattended. Stand 2 m from the mounted tablet; on each
 * beep, say "hey computer" once, in a normal speaking voice. There are 10 beeps.
 *
 *   scripts/connected-test.sh WakeWordRangeTest
 *
 * Every number this harness reports is defended against a way it could lie:
 *
 *  - The CUE must be heard, or the operator is speaking into the void between windows. The tone is
 *    therefore played and then confirmed against the device's own playback head - a beep that never
 *    left the speaker fails the trial rather than scoring as a human who missed their line.
 *  - LATENCY is measured from the END of the phrase (D-14a), not from the beep and not from the first
 *    syllable. The pause between the cue and the operator drawing breath is the operator's, not the
 *    console's; and "hey computer" takes ~800 ms to say, so a bar measured from its start would demand
 *    detection before the speaker had finished speaking. Speech is a sustained crossing of a bar set
 *    against the room's measured noise floor, because a fixed bar low enough to catch a voice at 2 m
 *    is also low enough to trip on a footstep.
 *  - LIVENESS: a silent microphone detects nothing, and "nothing detected" is exactly what a bad
 *    model looks like. Every trial must show the room actually reaching the mic.
 */
@RunWith(AndroidJUnit4::class)
class WakeWordRangeTest {

    @get:Rule
    val micPermission: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.RECORD_AUDIO)

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val audioManager: AudioManager
        get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** What the capture tap should do with each frame, which differs by trial phase. */
    private enum class Phase { IDLE, FLOOR, WINDOW }

    private val phase = AtomicReference(Phase.IDLE)
    private val floorDbfs = AtomicReference(SILENCE_DBFS)
    private val onsetBarDbfs = AtomicReference(ONSET_BAR_FLOOR)
    private val onsetRun = AtomicInteger(0)
    private val onsetMillis = AtomicLong(0)
    private val lastSpeechMillis = AtomicLong(0)
    private val peakDbfs = AtomicReference(SILENCE_DBFS)
    private val framesSeen = AtomicInteger(0)
    private val recorder = AtomicReference<OutputStream?>(null)

    /**
     * THE CUE'S OWN POSITIVE CONTROL - and it needs no human, which is the point.
     *
     * The range measurement below is worthless if the operator cannot hear when to speak, and the
     * first run of this harness proved that a silent cue does not announce itself: it just looks
     * like a person who keeps missing their line. The device's playback head says whether the tone
     * was played; only the microphone says whether it was AUDIBLE. So play it and listen on the
     * unprocessed source, where a beep from the tablet's own speaker must be plainly loud.
     */
    @Test
    fun the_cue_tone_actually_reaches_the_room() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            delay(1_500)
            setMediaVolumeToNormal()

            val record = openUnprocessedCapture()
            val peak = AtomicReference(SILENCE_DBFS)
            val listening = launch(Dispatchers.IO) {
                val buffer = ByteArray(640)
                while (isActive) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read <= 0) break
                    val level = rmsDbfs(buffer.copyOf(read))
                    peak.updateAndGet { loudest -> maxOf(loudest, level) }
                }
            }

            delay(300) // a moment of room tone first, so the peak below is the beep and not the start-up
            val played = cue()
            delay(300)

            listening.cancelAndJoin()
            record.stop()
            record.release()

            val expected = CUE_HZ * CUE_MS / 1000
            Log.i(TAG, "CUE played $played of $expected frames; " +
                "unprocessed mic peak ${"%.1f".format(peak.get())} dBFS")

            assertTrue(
                "The device played only $played of $expected cue frames: the tone never left " +
                    "AudioTrack. Closing the sink flushes it - that is barge-in's job, not a cue's.",
                played >= expected,
            )
            assertTrue(
                "The cue played, but the microphone heard only ${"%.1f".format(peak.get())} dBFS - " +
                    "the beep is not reaching the room, so the operator cannot hear when to speak. " +
                    "Check the media volume and the output route.",
                peak.get() > CUE_AUDIBLE_DBFS,
            )
        }
    }

    @Test
    fun detects_the_wake_phrase_at_two_metres_from_the_wall_mount() = runBlocking {
        // Since Android 12 a process with no visible Activity is handed SILENCE by AudioRecord
        // rather than an error - so without the console on screen this harness would measure a dead
        // microphone and report it as a model that cannot hear (T-04 learned this the hard way).
        ActivityScenario.launch(MainActivity::class.java).use {
            delay(1_500) // let the shell's own startup self-check finish and release its recorder

            setMediaVolumeToNormal()

            // ONE capture session for all 10 trials, held open throughout - the console listens for
            // the wake word all day on a continuously-open stream, and that is the stream whose
            // detection rate this test is entitled to report.
            //
            // Noise suppression is switchable from the command line because it is a live suspect,
            // not a settled question: VOICE_COMMUNICATION's suppressor gates this room's tone to a
            // mean of -105 dBFS, and the wake word opens with exactly the sort of low-energy plosive
            // a gate is glad to be rid of. The echo canceller stays on either way - that one is not
            // a suspect, it is the foundation (REQ-05).
            val suppress = InstrumentationRegistry.getArguments()
                .getString("noiseSuppression", "true").toBoolean()

            // DIAGNOSTIC ONLY (T-33). The console listens on VOICE_COMMUNICATION and nothing else -
            // it is the only source routed through the hardware echo canceller, and barge-in rests
            // on that (D-03, REQ-05). But an ASR replayed over these recordings transcribes only the
            // TAIL of every utterance ("UTER", "URE") on audio where a human plainly hears the whole
            // word, which says this source's preprocessing is mangling the leading plosive before
            // any model sees it - and that is exactly the failure the wake word shows.
            //
            // So this switch exists to answer one question: is the capture path the problem? It is
            // not a product mode. If unprocessed capture detects reliably, the fix is a design
            // decision about which stream the wake word may listen on - and never-self-barge
            // (REQ-06) has to be answered before any such change ships.
            val unprocessed = InstrumentationRegistry.getArguments()
                .getString("source", "voicecomm") == "unprocessed"

            val mic = if (unprocessed) null else MicPipeline(audioManager, AudioConfig(noiseSuppression = suppress))
            val raw = if (unprocessed) openUnprocessedCapture() else null
            val aec = mic?.start()
            if (aec != null) {
                assertTrue(
                    "hardware AEC is not enabled - this is not the stream the console listens on",
                    aec.enabled,
                )
            }

            // Keep the whole session's audio. A live run costs a human 90 seconds of standing in a
            // room saying one word to a tablet, so tuning a threshold by guess-and-re-run spends
            // the scarcest thing here. Recorded once, these utterances can be replayed through any
            // config or any model offline, as many times as the tuning takes.
            //
            // What it cannot do is REPLACE the live run: a recording proves a model can detect this
            // voice in this room, not that the console can. The acceptance measurement stays live.
            val recording = File(context.getExternalFilesDir(null), SESSION_PCM)
            val sink = BufferedOutputStream(FileOutputStream(recording))
            recorder.set(sink)

            // Tap the capture stream on its way to the detector, so every trial can report what the
            // microphone actually heard alongside what the model made of it.
            val frames = (mic?.frames() ?: unprocessedFrames(raw!!)).onEach(::observe)

            WakeWordDetector(context).use { detector ->
                val heard = Channel<WakeWord>(Channel.UNLIMITED)
                val detecting = launch(Dispatchers.IO) {
                    detector.detections(frames).collect { heard.trySend(it) }
                }

                Log.i(TAG, "=== WAKE WORD AT 2 m ===")
                Log.i(TAG, "mic: ${aec?.describe() ?: "UNPROCESSED capture (VOICE_RECOGNITION) - diagnostic, no AEC"}")
                Log.i(TAG, "noise suppression: ${if (unprocessed) "n/a" else if (suppress) "on (platform default)" else "OFF"}")
                Log.i(TAG, "detector: gain=${WakeWordDetector.CAPTURE_GAIN}x " +
                    "score=${WakeWordDetector.KEYWORDS_SCORE} " +
                    "threshold=${WakeWordDetector.KEYWORDS_THRESHOLD}")
                Log.i(TAG, "STAND 2 m FROM THE TABLET. On each beep, say \"hey computer\" once.")
                delay(SETUP_MS) // time to walk to the mark

                val trials = (1..TRIALS).map { n -> runTrial(n, heard) }

                detecting.cancelAndJoin()
                mic?.close()
                raw?.run { stop(); release() }
                recorder.set(null)
                sink.flush()
                sink.close()
                Log.i(TAG, "RECORDING ${recording.length() / 2 / 16} ms of capture at $recording")

                // Where each utterance sits in that recording, so an offline sweep scores the same
                // 10 windows this run did rather than the whole session end to end.
                File(context.getExternalFilesDir(null), SESSION_MARKS).writeText(
                    buildString {
                        appendLine("# trial startFrame endFrame detectedLive  (frame = 640 bytes = 20 ms)")
                        trials.forEach { appendLine("${it.index} ${it.startFrame} ${it.endFrame} ${it.detected}") }
                    },
                )

                report(trials)
            }
        }
    }

    private fun unprocessedFrames(record: AudioRecord): Flow<ByteArray> = flow {
        val buffer = ByteArray(640) // 20 ms at 16 kHz, the framing the detector expects
        while (currentCoroutineContext().isActive) {
            val read = record.read(buffer, 0, buffer.size)
            if (read <= 0) break
            emit(buffer.copyOf(read))
        }
    }

    /** The capture tap: the room, as the microphone reports it, frame by frame. */
    private fun observe(frame: ByteArray) {
        recorder.get()?.write(frame) // every frame, in every phase: the frame index IS the offset
        framesSeen.incrementAndGet()
        val level = rmsDbfs(frame)
        when (phase.get()) {
            Phase.IDLE -> Unit

            // Between the cue and the window: learn how loud this room is when nobody is speaking.
            Phase.FLOOR -> floorDbfs.updateAndGet { floor -> maxOf(floor, level) }

            Phase.WINDOW -> {
                peakDbfs.updateAndGet { peak -> maxOf(peak, level) }
                if (level >= onsetBarDbfs.get()) {
                    // Sustained, not instantaneous: one loud frame is a door or a chair, and taking
                    // it for the start of the word would credit the detector with a head start.
                    if (onsetRun.incrementAndGet() == ONSET_FRAMES) {
                        val firstFrameOfRun =
                            System.currentTimeMillis() - (ONSET_FRAMES - 1) * FRAME_MS
                        onsetMillis.compareAndSet(0L, firstFrameOfRun)
                    }
                    // The last moment the speaker was still speaking. By the time the detector
                    // fires, this has stopped moving - so it is the end of the phrase, and the
                    // latency that matters is measured from here (D-14a).
                    lastSpeechMillis.set(System.currentTimeMillis())
                } else {
                    onsetRun.set(0)
                }
            }
        }
    }

    private suspend fun runTrial(n: Int, heard: Channel<WakeWord>): Trial {
        // Measure the room's noise floor while the cue tone leaves it. Both things need the same
        // quiet moment, so they share it.
        floorDbfs.set(SILENCE_DBFS)
        phase.set(Phase.FLOOR)
        val cueFrames = cue()
        delay(SETTLE_MS)

        // The bar a voice must clear to count as the start of an utterance, set against this room
        // rather than against an assumption about it. Clamped: a noisy room must not raise the bar
        // above a real voice at 2 m, and a silent one must not drop it onto the noise itself.
        val floor = floorDbfs.get()
        val bar = (floor + FLOOR_MARGIN_DB).coerceIn(ONSET_BAR_FLOOR, ONSET_BAR_CEILING)
        onsetBarDbfs.set(bar)

        // Drain whatever the model made of the cue or of the previous trial. Deliberately NOT
        // detector.reset(): the decoder is being driven from the collector thread, and sherpa-onnx's
        // OnlineStream is not thread-safe - resetting it from here races into native code. The
        // detector already resets itself after every hit, so draining is enough.
        while (heard.tryReceive().isSuccess) Unit
        onsetMillis.set(0L)
        lastSpeechMillis.set(0L)
        onsetRun.set(0)
        peakDbfs.set(SILENCE_DBFS)
        phase.set(Phase.WINDOW)

        Log.i(TAG, "--- TRIAL $n/$TRIALS: say \"hey computer\" NOW " +
            "(floor ${"%.1f".format(floor)} dBFS, onset bar ${"%.1f".format(bar)} dBFS)")
        val startFrame = framesSeen.get()
        val hit = withTimeoutOrNull(WINDOW_MS) { heard.receive() }
        phase.set(Phase.IDLE)
        val endFrame = framesSeen.get()

        val onsetAt = onsetMillis.get()
        val endedAt = lastSpeechMillis.get()
        val trial = Trial(
            index = n,
            cueFrames = cueFrames,
            startFrame = startFrame,
            endFrame = endFrame,
            detected = hit != null,
            // Measured from the END of the phrase, not its start (D-14a). "hey computer" takes the
            // best part of a second to say, and a bar measured from the first syllable would be
            // asking the console to answer before the speaker had finished asking - which is not a
            // latency budget, it is a demand for precognition.
            latencyMs = if (hit != null && endedAt > 0L) hit.atMillis - endedAt else UNMEASURED,
            // Kept because it is the honest denominator: it says how much of the wait below is the
            // phrase itself rather than the detector.
            phraseMs = if (onsetAt > 0L && endedAt > onsetAt) endedAt - onsetAt else UNMEASURED,
            peakDbfs = peakDbfs.get(),
        )
        Log.i(TAG, "    ${trial.describe()}")
        delay(GAP_MS)
        return trial
    }

    /**
     * A short tone out of the tablet's speaker - the cue to speak - returning the frames the device
     * says it actually played.
     *
     * Deliberately NOT the console's TtsPlayer/AudioTrackSink path, though the first version of this
     * harness tried that and played silence for ten straight trials. That sink is built for what it
     * carries: streaming TTS, MODE_STREAM, USAGE_ASSISTANT - which the platform hands a deep-buffer
     * output. A 200 ms blip written into a deep buffer is never mixed before the track is released;
     * the audio log says "3200 frames delivered" and the playback head never leaves zero. Sentences
     * work there. Beeps do not.
     *
     * So the cue uses the canonical short-sound path instead - MODE_STATIC, the whole tone resident
     * in the track before it plays. It is scaffolding, not product: the wake word is what is under
     * test here, and the cue's only job is to be heard.
     */
    private suspend fun cue(): Int {
        val samples = CUE_HZ * CUE_MS / 1000
        val pcm = ByteArray(samples * 2)
        for (i in 0 until samples) {
            // Fade the last few ms out, so the tone ends instead of clicking.
            val fade = minOf(1.0, (samples - i) / (CUE_HZ * 0.01))
            val value = (sin(2 * PI * CUE_TONE_HZ * i / CUE_HZ) * CUE_AMPLITUDE * fade * Short.MAX_VALUE)
                .toInt()
            pcm[i * 2] = (value and 0xFF).toByte()
            pcm[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(CUE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(pcm.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        return try {
            track.write(pcm, 0, pcm.size) // the whole tone, resident, before a note is played
            track.play()
            withTimeoutOrNull(CUE_DRAIN_MS) {
                while (track.playbackHeadPosition < samples) delay(10)
            }
            track.playbackHeadPosition
        } finally {
            track.release()
        }
    }

    private fun report(trials: List<Trial>) {
        val detected = trials.count { it.detected }
        val measured = trials.filter { it.detected && it.latencyMs != UNMEASURED }
        val worstLatency = measured.maxOfOrNull { it.latencyMs } ?: 0L

        Log.i(TAG, "---")
        trials.forEach { Log.i(TAG, "RESULT ${it.describe()}") }
        Log.i(TAG, "RESULT detected = $detected of $TRIALS (need >= $REQUIRED)")
        if (measured.isNotEmpty()) {
            Log.i(
                TAG,
                "RESULT latency from END of phrase: worst=$worstLatency ms " +
                    "mean=${measured.map { it.latencyMs }.average().toLong()} ms " +
                    "(need < $MAX_LATENCY_MS ms)",
            )
            Log.i(TAG, "RESULT the phrase itself took " +
                "${measured.filter { it.phraseMs != UNMEASURED }.map { it.phraseMs }.average().toLong()} ms to say")
        }
        Log.i(TAG, "VERDICT ${if (detected >= REQUIRED && worstLatency < MAX_LATENCY_MS) "PASS" else "FAIL"}")

        // THE CUE - checked before anything is concluded about the model. If the beep never left the
        // speaker, the operator was speaking into windows they could not hear, and every number
        // below is about this harness rather than about the wake word.
        val expectedFrames = CUE_HZ * CUE_MS / 1000
        val silentCues = trials.filter { it.cueFrames < expectedFrames }
        assertTrue(
            "The cue tone did not play on trial(s) ${silentCues.map { it.index }} " +
                "(frames played: ${silentCues.map { it.cueFrames }}, expected $expectedFrames). " +
                "The operator could not hear when to speak, so this run measured nothing. Re-run.",
            silentCues.isEmpty(),
        )

        // LIVENESS - a silent microphone detects nothing, and so does a broken model.
        assertTrue("the capture stream produced no frames: the microphone is dead", framesSeen.get() > 0)
        val silent = trials.filter { it.peakDbfs < LIVENESS_DBFS }
        assertTrue(
            "The microphone heard nothing above $LIVENESS_DBFS dBFS on trial(s) " +
                "${silent.map { it.index }} (peaks: ${silent.map { "%.1f".format(it.peakDbfs) }}). " +
                "Either nobody spoke on those beeps or the mic is not live - this is not a model " +
                "failure, and the score would be meaningless. Re-run.",
            silent.isEmpty(),
        )

        assertTrue(
            "The wake word carried only $detected time(s) out of $TRIALS at 2 m (need $REQUIRED). " +
                "REQ-01 is not met: swap in the documented fallback model " +
                "(sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20) and re-measure.",
            detected >= REQUIRED,
        )
        assertTrue(
            "Detection took $worstLatency ms after the speaker finished the phrase (must be < " +
                "$MAX_LATENCY_MS ms): the console would feel deaf before it feels awake.",
            worstLatency < MAX_LATENCY_MS,
        )
    }

    /**
     * The microphone with no voice processing on it at all - the control stream.
     *
     * Deliberately NOT the production source: the AEC-processed path exists precisely to cancel
     * sound the tablet itself made, so asking it whether the tablet's own beep was audible is
     * asking the one listener guaranteed to say no. The console never opens this source.
     */
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

    /** Normal listening volume: 70% of the media stream, so the cue carries 2 m. */
    private fun setMediaVolumeToNormal() {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (max * 0.7).toInt(), 0)
    }

    private data class Trial(
        val index: Int,
        val cueFrames: Int,
        /** Where this trial's window sits in the session recording: frame index = 640-byte offset. */
        val startFrame: Int,
        val endFrame: Int,
        val detected: Boolean,
        val latencyMs: Long,
        val phraseMs: Long,
        val peakDbfs: Double,
    ) {
        fun describe(): String {
            val outcome = when {
                !detected -> "MISS"
                latencyMs == UNMEASURED -> "detected, BUT NOT IN THIS WINDOW (no speech onset)"
                else -> "detected ${latencyMs} ms after the phrase ended (phrase took ${phraseMs} ms)"
            }
            return "trial $index: $outcome, mic peak ${"%.1f".format(peakDbfs)} dBFS"
        }
    }

    private companion object {
        const val TAG = "WakeWordRange"

        const val TRIALS = 10
        const val REQUIRED = 8 // REQ-01: 8 of 10 at 2 m
        const val MAX_LATENCY_MS = 1_000L // REQ-01: detection within 1 s

        const val SETUP_MS = 5_000L // walk to the 2 m mark
        const val WINDOW_MS = 5_000L // per utterance
        const val GAP_MS = 1_000L
        const val SETTLE_MS = 600L // the cue leaving the room, and the room's floor being measured

        const val FRAME_MS = 20L // AudioConfig.chunkBytes: 640 bytes = 20 ms at 16 kHz

        /** 3 frames = 60 ms of sustained energy before this harness calls it the start of a word. */
        const val ONSET_FRAMES = 3

        /** How far above the room's own noise floor a voice must land to be a voice. */
        const val FLOOR_MARGIN_DB = 12.0

        /**
         * The onset bar's clamps. A voice at 2 m arrives far quieter than SpeechGate's barge-in
         * threshold, which is tuned for conversational distance - so the bar has to sit low. But
         * not arbitrarily low: below the floor clamp it would trip on the room, and above the
         * ceiling clamp a real voice at 2 m could never reach it.
         */
        const val ONSET_BAR_FLOOR = -55.0
        const val ONSET_BAR_CEILING = -35.0

        /**
         * Did any sound at all reach the microphone? Not "was that speech?" - just enough to tell a
         * live mic from digital silence (-120 dBFS), so a dead mic can never read as a model miss.
         */
        const val LIVENESS_DBFS = -50.0

        const val UNMEASURED = -1L

        const val CUE_HZ = 16_000
        const val CUE_TONE_HZ = 880.0
        const val CUE_MS = 200
        const val CUE_AMPLITUDE = 0.3
        const val CUE_DRAIN_MS = 2_000L

        /**
         * How loud the tablet's own beep must land on its own unprocessed microphone. A speaker and
         * a mic inches apart: the tone arrives loudly or it did not arrive. Nothing subtle to tune.
         */
        const val CUE_AUDIBLE_DBFS = -40.0

        /** The session recording and its trial markers, for offline tuning against a real voice. */
        const val SESSION_PCM = "wakeword-session.pcm"
        const val SESSION_MARKS = "wakeword-session.marks"
    }
}
