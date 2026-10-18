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
import com.hermesconsole.audio.AudioConfig
import com.hermesconsole.audio.AudioSink
import com.hermesconsole.audio.AudioSinkFactory
import com.hermesconsole.audio.AudioTrackSink
import com.hermesconsole.audio.AudioTrackSinkFactory
import com.hermesconsole.audio.MicPipeline
import com.hermesconsole.audio.SILENCE_DBFS
import com.hermesconsole.audio.SpeechGate
import com.hermesconsole.audio.TtsPlayer
import com.hermesconsole.audio.rmsDbfs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * THE HARDWARE GATE (T-04, REQ-05).
 *
 * The console's entire interaction model rests on one physical claim: when the tablet speaks
 * through its own speakers, its own microphone - after hardware echo cancellation - does not hear
 * speech. If that claim is false, the tablet barges in on itself, and no software fixes it short
 * of half-duplex mic gating, which the spec refuses. So this test mocks nothing: real TTS, real
 * speakers, real mic, real [SpeechGate].
 *
 * It runs in the *app* process with MainActivity on screen, and that is not incidental. Since
 * Android 12, a process with no visible Activity is handed SILENCE by AudioRecord rather than an
 * error - a background instrumented test therefore "passes" this gate while measuring a dead
 * microphone. The liveness checks below exist so that failure can never again masquerade as a pass.
 *
 * PASS: zero sustained-speech detections across 3 x 60 s of playback, with nobody speaking.
 *
 * Setup:
 *   adb push tts60.pcm /sdcard/Android/data/com.hermesconsole/files/tts60.pcm
 */
@RunWith(AndroidJUnit4::class)
class AecHardwareGateTest {

    @get:Rule
    val micPermission: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.RECORD_AUDIO)

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val audioManager: AudioManager
        get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Test
    fun hardware_aec_suppresses_the_tablets_own_tts_below_the_barge_in_gate() = runBlocking {
        // The speech ships inside the test APK: an app's own data directory is wiped by the
        // reinstall that every instrumented run performs, so a pushed file cannot survive to be read.
        val pcm = runCatching {
            InstrumentationRegistry.getInstrumentation().context.assets.open(TTS_FILE)
                .use { it.readBytes() }
        }.getOrElse {
            throw AssertionError(
                "Missing the speech fixture app/src/androidTest/assets/$TTS_FILE. " +
                    "Generate it against a running VoiceManager: " +
                    "VM_TOKEN=... python3 scripts/make-aec-speech.py",
            )
        }

        // The mic is only live while the console is actually on screen, and it must be on screen
        // WITHOUT the wall console behind it: the wall assembly opens its own VOICE_COMMUNICATION
        // recorder and holds it for the life of the process, and the platform silences the second
        // recorder in the process (`AF::RecordTrack: setSilenced`) - this gate then measures exact
        // digital zeros and the positive control fails. The `debug` launch is the shape this test
        // needs: an Activity on screen with no capture path constructed at all (DisengagedCapture),
        // so the only recorder in the process is the one this test opens.
        val onScreen = Intent(context, MainActivity::class.java).putExtra("debug", true)
        ActivityScenario.launch<MainActivity>(onScreen).use {
            delay(1_500) // let the shell finish starting before the measurement begins

            val volume = setMediaVolumeToNormal()
            Log.i(TAG, "=== AEC HARDWARE GATE ===")
            Log.i(TAG, "gate: ${SpeechGate.DEFAULT_THRESHOLD_DBFS} dBFS sustained over " +
                "${SpeechGate.DEFAULT_CONSECUTIVE_FRAMES} frames (${SpeechGate.DEFAULT_CONSECUTIVE_FRAMES * 20} ms)")
            Log.i(TAG, "volume: $volume of media stream max")
            Log.i(TAG, "tts: ${"%.1f".format(pcm.size / (TTS_RATE * 2.0))} s @ $TTS_RATE Hz")

            // POSITIVE CONTROL - the honesty check this gate cannot be trusted without.
            //
            // The voice-communication path noise-gates non-speech to exact digital zeros, so a
            // quiet reading there proves nothing: a dead mic and a perfectly cancelled one look
            // identical. Instead, play the same TTS and listen on the UNPROCESSED source, where
            // the tablet's own voice must be plainly, loudly audible. If it is not, the speakers
            // never spoke or the mic never listened, and any "PASS" below is an artefact.
            val control = measure(durationMs = CONTROL_MS, playing = pcm, aec = false)
            Log.i(TAG, "POSITIVE CONTROL (unprocessed capture, tablet speaking): ${control.describe()}")
            assertTrue(
                "The unprocessed mic heard ${"%.1f".format(control.peakDbfs)} dBFS while the tablet " +
                    "was speaking - the echo path does not exist, so there is nothing for the AEC " +
                    "to cancel. The gate below would pass on a dead rig. Check the speakers.",
                control.peakDbfs > SpeechGate.DEFAULT_THRESHOLD_DBFS,
            )
            assertTrue(
                "AudioTrack played ${control.framesPlayed} frames: the tablet never actually spoke.",
                control.framesPlayed > TTS_RATE * (CONTROL_MS / 1000 / 2),
            )
            Log.i(TAG, "  echo path confirmed: playback routed to ${control.routedTo}")

            // ONE capture session for the whole measurement, held open across every run - because
            // that is what the console actually does: the microphone is open all day listening for
            // the wake word, and the echo canceller converges on that continuously-open stream.
            // Opening a fresh session per run measures a cold canceller, which the product never has.
            val mic = MicPipeline(audioManager, AudioConfig())
            assertTrue("hardware AEC is not enabled; the gate is meaningless", mic.start().enabled)

            // The convergence window, reported separately rather than hidden. A cold canceller
            // leaks the tablet's own voice for the first seconds of playback; the question this
            // gate has to answer is whether it still leaks once converged, as the console will be.
            val warmup = measure(durationMs = WARMUP_MS, playing = pcm, aec = true, openMic = mic)
            Log.i(TAG, "WARM-UP (cold echo canceller converging): ${warmup.describe()}")
            Log.i(TAG, "SWEEP  warm-up detections by threshold: ${warmup.sweep}")

            val runs = (1..RUNS).map { run ->
                val result = measure(durationMs = PLAYBACK_MS, playing = pcm, aec = true, openMic = mic)
                Log.i(TAG, "RUN $run/$RUNS (AEC capture, converged, tablet speaking): ${result.describe()}")
                // Playback is only real if the device actually consumed the frames and routed
                // them somewhere. Without this, a muted or dead output would read as a clean pass.
                assertTrue(
                    "AudioTrack played ${result.framesPlayed} frames: the tablet never spoke, " +
                        "so the echo it must survive was never produced.",
                    result.framesPlayed > TTS_RATE * 30, // at least 30 s of audio left the device
                )
                Log.i(TAG, "  playback: ${result.framesPlayed} frames out of ${result.routedTo}")
                result
            }

            mic.close()
            val detections = runs.sumOf { it.detections }
            val worstEcho = runs.maxOf { it.peakDbfs }

            Log.i(TAG, "---")
            // Peak is a poor summary here: brief transients do cross the threshold, they just never
            // sustain the 100 ms the gate requires. Mean level is what shows the suppression, and
            // sustained detections are what actually decide the gate.
            val meanEcho = runs.map { it.meanDbfs }.average()
            Log.i(TAG, "RESULT uncancelled echo (control): mean=${"%.1f".format(control.meanDbfs)} dBFS peak=${"%.1f".format(control.peakDbfs)} dBFS")
            Log.i(TAG, "RESULT cancelled echo (converged): mean=${"%.1f".format(meanEcho)} dBFS peak=${"%.1f".format(worstEcho)} dBFS (transient)")
            Log.i(TAG, "RESULT AEC suppression (mean) = ${"%.1f".format(meanEcho - control.meanDbfs).removePrefix("-")} dB")
            Log.i(TAG, "RESULT warm-up (cold canceller) detections = ${warmup.detections} - the console must not act on barge-in here")
            Log.i(TAG, "RESULT detections = $detections (must be 0)")
            runs.forEachIndexed { i, r -> Log.i(TAG, "SWEEP  echo detections by threshold, run ${i + 1}: ${r.sweep}") }
            Log.i(TAG, "VERDICT ${if (detections == 0) "PASS" else "FAIL"}")

            assertEquals(
                "The tablet's own TTS tripped the barge-in gate $detections time(s): it would " +
                    "interrupt itself. Barge-in is a gate, not a branch - STOP and re-spec (REQ-05).",
                0,
                detections,
            )
        }
    }

    /**
     * Capture for [durationMs], optionally playing [playing] out of the speakers at the same time.
     *
     * With [aec] true this is the production path (MicPipeline: VOICE_COMMUNICATION + hardware AEC).
     * With it false, capture is the unprocessed source - the control that proves sound actually
     * travelled from the speakers to the microphone.
     */
    private suspend fun measure(
        durationMs: Long,
        playing: ByteArray?,
        aec: Boolean,
        openMic: MicPipeline? = null,
    ): Measurement = coroutineScope {
        val mic = if (!aec) null else openMic ?: MicPipeline(audioManager, AudioConfig()).also {
            assertTrue("hardware AEC is not enabled; the gate is meaningless without it", it.start().enabled)
        }
        val unprocessed = if (aec) null else openUnprocessedCapture()

        val gate = SpeechGate()
        val sweep = GateSweep()
        var detections = 0
        var frames = 0
        var peak = SILENCE_DBFS
        var sumDbfs = 0.0
        val deadline = System.currentTimeMillis() + durationMs

        val probe = ProbingSinkFactory(AudioTrackSinkFactory(audioManager, AudioConfig()))
        var playback: Job? = null
        if (playing != null) playback = launch(Dispatchers.IO) { play(playing, probe) }

        withContext(Dispatchers.IO) {
            val source = mic?.frames() ?: unprocessedFrames(unprocessed!!)
            try {
                source.collect { frame ->
                    val level = rmsDbfs(frame)
                    frames++
                    sumDbfs += level
                    if (level > peak) peak = level
                    sweep.accept(frame)
                    if (gate.accept(frame)) {
                        detections++
                        Log.w(TAG, "  DETECTION at frame $frames (${"%.1f".format(level)} dBFS)")
                    }
                    if (System.currentTimeMillis() >= deadline) throw StopCapture()
                }
            } catch (stop: StopCapture) {
                // the measurement window closed; the only way out of an endless capture flow
            }
        }

        val framesPlayed = probe.last?.framesPlayed ?: 0
        val routedTo = probe.last?.routedDeviceName ?: "nothing"
        playback?.cancelAndJoin()
        if (openMic == null) mic?.close() // a caller-owned session stays open across runs
        unprocessed?.run { stop(); release() }
        Measurement(
            frames = frames,
            detections = detections,
            peakDbfs = peak,
            meanDbfs = if (frames > 0) sumDbfs / frames else SILENCE_DBFS,
            framesPlayed = framesPlayed,
            routedTo = routedTo,
            sweep = sweep.report(),
        )
    }

    /**
     * Play the TTS out of the built-in speakers, exactly the way the console will.
     *
     * The loop is cooperative on purpose: AudioTrack.write blocks, so a cancelled coroutine would
     * keep talking straight through the end of the window and into the next run's measurement.
     */
    private suspend fun play(pcm: ByteArray, factory: AudioSinkFactory) {
        TtsPlayer(factory, AudioConfig()).use { player ->
            player.onAudioStart(TTS_RATE) // the rate the wire declared (audiocpp/dots)
            var offset = 0
            while (offset < pcm.size && currentCoroutineContext().isActive) {
                val end = minOf(offset + CHUNK, pcm.size)
                player.onChunk(pcm.copyOfRange(offset, end))
                offset = end
            }
            player.onAudioEnd()
        }
    }

    /**
     * The control stream: the microphone with no voice processing on it at all.
     *
     * Deliberately NOT the production source - the point is to hear the room as it really is,
     * echo included. It exists only inside this harness; the console itself never opens it.
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

    private fun unprocessedFrames(record: AudioRecord): Flow<ByteArray> = flow {
        val buffer = ByteArray(640) // 20 ms at 16 kHz, same framing as the production path
        while (currentCoroutineContext().isActive) {
            val read = record.read(buffer, 0, buffer.size)
            if (read <= 0) break
            emit(buffer.copyOf(read))
        }
    }

    /** Keeps hold of the real track so the harness can prove audio actually left the device. */
    private class ProbingSinkFactory(private val inner: AudioSinkFactory) : AudioSinkFactory {
        var last: AudioTrackSink? = null
        override fun create(sampleRateHz: Int): AudioSink =
            inner.create(sampleRateHz).also { last = it as? AudioTrackSink }
    }

    /** Normal listening volume: 70% of the media stream. The number is recorded with the result. */
    private fun setMediaVolumeToNormal(): String {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val level = (max * 0.7).toInt()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
        return "$level/$max (70%)"
    }

    private class StopCapture : RuntimeException()

    private data class Measurement(
        val frames: Int,
        val detections: Int,
        val peakDbfs: Double,
        val meanDbfs: Double,
        val framesPlayed: Int,
        val routedTo: String,
        val sweep: String = "",
    ) {
        fun describe(): String =
            "frames=$frames detections=$detections peak=${"%.1f".format(peakDbfs)} dBFS " +
                "mean=${"%.1f".format(meanDbfs)} dBFS"
    }

    private companion object {
        const val TAG = "AecGate"
        const val TTS_FILE = "tts60.pcm"
        /**
         * The fixture's rate, which is the live engine's: audiocpp/dots declares 48000 Hz on the
         * wire. It was 24000 while the fixture was VibeVoice - and that fixture is why this gate
         * passed at 66.2 dB while the console self-barged, because VibeVoice's audio is the one
         * content that never leaked (docs/aec-spike.md, "The ninth"). Regenerate the fixture and
         * this constant together: scripts/make-aec-speech.py prints the rate it wrote.
         */
        const val TTS_RATE = 48_000
        const val RUNS = 3
        const val PLAYBACK_MS = 60_000L

        /** Long enough for the echo canceller to converge - the console is never colder than this. */
        const val WARMUP_MS = 20_000L

        /** The positive control: long enough to be sure real speech, not a gap, was measured. */
        const val CONTROL_MS = 10_000L
        const val CHUNK = 9_600 // 100 ms at 48 kHz
    }
}
