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
import com.hermesconsole.audio.NativeSoftwareAec
import com.hermesconsole.audio.ReferenceResampler
import com.hermesconsole.audio.SILENCE_DBFS
import com.hermesconsole.audio.SerializedSoftwareAec
import com.hermesconsole.audio.SpeechGate
import com.hermesconsole.audio.TtsPlayer
import com.hermesconsole.audio.rmsDbfs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * THE SOFTWARE ERL GATE (T-11, REQ-01/REQ-04), on the SM-X210.
 *
 * The software AEC's pass bar, measured the same way the hardware gate (T-04) measured the old
 * path: real TTS out of the real speakers, real microphone in, and the residual - what survives
 * cancellation - read against the -35 dBFS speech gate. PASS is the residual staying comfortably
 * below that bar across the engines the wire actually declares (22050/24000/48000), with zero
 * sustained detections, shortly after playback onset, after convergence, and after an inter-turn
 * gap - not only after long convergence.
 *
 * The wiring under test is the production one (T-08/T-09): one [NativeSoftwareAec] session per
 * capture session, wrapped in [SerializedSoftwareAec]; the reference is the exact played PCM
 * tapped by [TtsPlayer] and resampled to 16 kHz by the reference resampler - the only rate
 * conversion in the reference path - and the session is seeded with the measured output latency
 * (getTimestamp, REQ-05) exactly as the voice loop seeds it. Each playback window is a fresh
 * reply onset, which is the shape the convergence guard (REQ-12) exists for.
 *
 * Runs in the app process with the console on screen - a background instrumented test is handed
 * digital silence since Android 12, and the positive control below exists so a dead rig can
 * never masquerade as a pass (docs/aec-spike.md "Two ways this measurement lied").
 *
 * Setup: the speech fixture ships in the test APK (app/src/androidTest/assets/tts60.pcm).
 */
@RunWith(AndroidJUnit4::class)
class SoftwareErlGateTest {

    @get:Rule
    val micPermission: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.RECORD_AUDIO)

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val audioManager: AudioManager
        get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Test
    fun software_aec_keeps_the_residual_below_the_gate_across_engines_and_gaps(): Unit = runBlocking {
        val pcm = fixture(TTS_FILE)
        // The debug launch (T-17): the harness owns the microphone - the shipping console's own
        // capture path is never constructed, so there is no second recorder competing for the mic.
        val activity = Intent(context, MainActivity::class.java).putExtra("debug", true)
        ActivityScenario.launch<MainActivity>(activity).use {
            delay(1_500) // let the shell's startup self-check finish and release its recorder

            val volume = setMediaVolumeToNormal()
            Log.i(TAG, "=== SOFTWARE AEC ERL GATE ===")
            Log.i(TAG, "gate: ${SpeechGate.DEFAULT_THRESHOLD_DBFS} dBFS sustained over " +
                "${SpeechGate.DEFAULT_CONSECUTIVE_FRAMES} frames (${SpeechGate.DEFAULT_CONSECUTIVE_FRAMES * 20} ms)")
            Log.i(TAG, "volume: $volume of media stream max")
            Log.i(TAG, "tts fixture: ${"%.1f".format(pcm.size / (TTS_RATE * 2.0))} s @ $TTS_RATE Hz")

            // POSITIVE CONTROL - the honesty check this gate cannot be trusted without. The
            // unprocessed microphone must hear the tablet's own voice loudly; if it does not, the
            // speakers never spoke or the mic never listened, and every quiet reading below is an
            // artefact of a dead rig.
            val control = measureUnprocessed(durationMs = CONTROL_MS, playing = pcm)
            Log.i(TAG, "POSITIVE CONTROL (unprocessed capture, tablet speaking): ${control.describe()}")
            assertTrue(
                "The unprocessed mic heard ${"%.1f".format(control.peakDbfs)} dBFS while the tablet " +
                    "was speaking - the echo path does not exist, so there is nothing for the AEC " +
                    "to cancel. Check the speakers and the volume.",
                control.peakDbfs > SpeechGate.DEFAULT_THRESHOLD_DBFS,
            )
            assertTrue(
                "AudioTrack played ${control.framesPlayed} frames: the tablet never spoke.",
                control.framesPlayed > TTS_RATE * (CONTROL_MS / 1000 / 2),
            )

            // ONE AEC session for the whole measurement - the product shape: the canceller
            // converges on the continuously-open capture and the engine varies per reply. The
            // ONE wired player plays every window: its referenceConsumer is the T-09 reference
            // path, so the AEC sees the exact PCM each playback window puts into the room.
            val aec = SerializedSoftwareAec(NativeSoftwareAec.create())
            val probe = ProbingSinkFactory(AudioTrackSinkFactory(audioManager, AudioConfig()))
            var resampler: ReferenceResampler? = null
            val player = TtsPlayer(probe)
            player.referenceConsumer = { ref ->
                resampler?.resample(ref)?.let { aec.processReference(it) }
            }

            // T-08's seed, verbatim: the measured output-path latency (getTimestamp), read once at
            // capture start. A short burst opens the track and lets the device report a
            // presentation timestamp before the measurement windows begin.
            player.onAudioStart(RATES.first())
            resampler = ReferenceResampler(RATES.first())
            playOn(player, pcm, RATES.first(), BURST_MS)
            val seed = player.latencyMillis
            if (seed != null) aec.setDelaySeedMs(seed)
            Log.i(TAG, "delay seed: ${seed ?: "unmeasured"} ms")

            try {
                var worstMean = SILENCE_DBFS
                var worstPeak = SILENCE_DBFS
                var worstMaxFrameUs = 0L
                for (rate in RATES) {
                    resampler = ReferenceResampler(rate)
                    Log.i(TAG, "--- engine $rate Hz ---")

                    // A fresh reply onset: the canceller is warm from the previous engine but this
                    // reply just started - the window the convergence guard exists for.
                    val onset = measureCancelled(ONSET_MS, pcm, rate, player, probe, aec)
                    Log.i(TAG, "ONSET (fresh reply, canceller warm): ${onset.describe()}")
                    assertWindow(onset, "onset @ $rate")

                    val converged = measureCancelled(CONVERGED_MS, pcm, rate, player, probe, aec)
                    Log.i(TAG, "CONVERGED (mid-reply): ${converged.describe()}")
                    assertWindow(converged, "converged @ $rate")

                    // The inter-turn gap: playback stops, the room goes quiet, and the canceller
                    // de-adapts through the silence (docs/aec-spike.md "The eighth sighting").
                    val gap = measureCancelled(GAP_MS, null, rate, player, probe, aec)
                    Log.i(TAG, "GAP (silence): ${gap.describe()}")

                    // The reply after the gap - the leak's worst window on the broken hardware
                    // path, and the acceptance names it explicitly.
                    val postGap = measureCancelled(POST_GAP_MS, pcm, rate, player, probe, aec)
                    Log.i(TAG, "POST-GAP (reply resumed): ${postGap.describe()}")
                    assertWindow(postGap, "post-gap @ $rate")

                    worstMean = minOf(worstMean, postGap.meanDbfs)
                    worstPeak = minOf(worstPeak, postGap.peakDbfs)
                    worstMaxFrameUs = maxOf(worstMaxFrameUs, postGap.maxFrameUs)
                }

                Log.i(TAG, "---")
                Log.i(TAG, "RESULT uncancelled echo (control): mean=${"%.1f".format(control.meanDbfs)} dBFS " +
                    "peak=${"%.1f".format(control.peakDbfs)} dBFS")
                Log.i(TAG, "RESULT worst cancelled residual: mean=${"%.1f".format(worstMean)} dBFS " +
                    "peak=${"%.1f".format(worstPeak)} dBFS")
                Log.i(TAG, "RESULT ERL (mean) = ${"%.1f".format(control.meanDbfs - worstMean)} dB")
                Log.i(TAG, "RESULT worst max per-frame time = ${"%.2f".format(worstMaxFrameUs / 1000.0)} ms " +
                    "(20 ms budget)")
                Log.i(TAG, "VERDICT PASS - residual below the ${SpeechGate.DEFAULT_THRESHOLD_DBFS} dBFS gate " +
                    "across engines and gaps")
            } finally {
                player.close()
                aec.close()
            }
        }
    }

    /** One measurement window's pass bar: below the gate, no sustained detection, within budget. */
    private fun assertWindow(m: CancelledMeasurement, what: String) {
        assertTrue(
            "residual ${"%.1f".format(m.meanDbfs)} dBFS in the $what window is above the " +
                "${SpeechGate.DEFAULT_THRESHOLD_DBFS} dBFS gate - the software AEC is not cancelling",
            m.meanDbfs < SpeechGate.DEFAULT_THRESHOLD_DBFS,
        )
        assertTrue(
            "the $what window produced ${m.detections} sustained detection(s): the console's own " +
                "voice still clears the barge-in gate on the cancelled stream",
            m.detections == 0,
        )
        assertTrue(
            "max per-frame AEC time ${m.maxFrameUs} us in the $what window exceeds the 20 ms " +
                "frame period - capture is being starved (REQ-11)",
            m.maxFrameUs < FRAME_PERIOD_US,
        )
    }

    /**
     * Capture through the software AEC for [durationMs], optionally playing [playing] at [rate]
     * out of the speakers on the wired [player] - the same player whose referenceConsumer feeds
     * the AEC, so the reference is the exact PCM this window puts into the room (T-09). Every
     * frame is processed by [aec] and measured for level and per-frame cost; the cancelled stream
     * is what the barge-in detector would see.
     */
    private suspend fun measureCancelled(
        durationMs: Long,
        playing: ByteArray?,
        rate: Int,
        player: TtsPlayer,
        probe: ProbingSinkFactory,
        aec: SerializedSoftwareAec,
    ): CancelledMeasurement = coroutineScope {
        val gate = SpeechGate()
        val sweep = GateSweep()
        var detections = 0
        var frames = 0
        var peak = SILENCE_DBFS
        var sumDbfs = 0.0
        var timeNs = 0L
        var maxFrameNs = 0L
        val deadline = System.currentTimeMillis() + durationMs

        var playback: Job? = null
        if (playing != null) playback = launch(Dispatchers.IO) { playOn(player, playing, rate) }

        withContext(Dispatchers.IO) {
            val mic = MicPipeline(audioManager, AudioConfig())
            mic.start()
            try {
                mic.frames().collect { frame ->
                    val t0 = System.nanoTime()
                    val cancelled = aec.processCapture(frame)
                    val elapsed = System.nanoTime() - t0
                    timeNs += elapsed
                    if (elapsed > maxFrameNs) maxFrameNs = elapsed

                    val level = rmsDbfs(cancelled)
                    frames++
                    sumDbfs += level
                    if (level > peak) peak = level
                    sweep.accept(cancelled)
                    if (gate.accept(cancelled)) {
                        detections++
                        Log.w(TAG, "  DETECTION at frame $frames (${"%.1f".format(level)} dBFS)")
                    }
                    if (System.currentTimeMillis() >= deadline) throw StopCapture()
                }
            } catch (stop: StopCapture) {
                // the measurement window closed; the only way out of an endless capture flow
            } finally {
                mic.close()
            }
        }

        val framesPlayed = probe.last?.framesPlayed ?: 0
        val routedTo = probe.last?.routedDeviceName ?: "nothing"
        playback?.cancelAndJoin()
        CancelledMeasurement(
            frames = frames,
            detections = detections,
            peakDbfs = peak,
            meanDbfs = if (frames > 0) sumDbfs / frames else SILENCE_DBFS,
            maxFrameUs = if (frames > 0) maxFrameNs / 1_000 else 0,
            meanFrameUs = if (frames > 0) timeNs / frames / 1_000 else 0,
            framesPlayed = framesPlayed,
            routedTo = routedTo,
            sweep = sweep.report(),
        )
    }

    /** The positive control: unprocessed capture while the tablet speaks. Harness-only recorder. */
    private suspend fun measureUnprocessed(durationMs: Long, playing: ByteArray?): Measurement =
        coroutineScope {
            val record = openUnprocessedCapture()
            val probe = ProbingSinkFactory(AudioTrackSinkFactory(audioManager, AudioConfig()))
            var playback: Job? = null
            if (playing != null) playback = launch(Dispatchers.IO) {
                playOn(TtsPlayer(probe), playing, TTS_RATE, 0)
            }

            var peak = SILENCE_DBFS
            var sumDbfs = 0.0
            var frames = 0
            val buffer = ByteArray(640)
            val deadline = System.currentTimeMillis() + durationMs
            withContext(Dispatchers.IO) {
                while (System.currentTimeMillis() < deadline) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read <= 0) break
                    val level = rmsDbfs(buffer.copyOf(read))
                    frames++
                    sumDbfs += level
                    if (level > peak) peak = level
                }
            }
            playback?.cancelAndJoin()
            record.stop()
            record.release()
            Measurement(
                frames = frames,
                peakDbfs = peak,
                meanDbfs = if (frames > 0) sumDbfs / frames else SILENCE_DBFS,
                framesPlayed = probe.last?.framesPlayed ?: 0,
            )
        }

    /**
     * Play up to [durationMs] (or the whole fixture when 0) out of the speakers on [player],
     * declared at [rate] - exactly the way the console plays a reply. Cooperative on purpose:
     * AudioTrack.write blocks, so a cancelled coroutine would keep talking into the next window's
     * measurement.
     */
    private suspend fun playOn(player: TtsPlayer, pcm: ByteArray, rate: Int, durationMs: Long = 0) {
        player.onAudioStart(rate)
        val bytes = if (durationMs > 0) {
            minOf(pcm.size, ((durationMs / 1000.0) * TTS_RATE * 2).toInt())
        } else pcm.size
        var offset = 0
        while (offset < bytes && currentCoroutineContext().isActive) {
            val end = minOf(offset + CHUNK, bytes)
            player.onChunk(pcm.copyOfRange(offset, end))
            offset = end
        }
        player.onAudioEnd()
    }

    /** The control stream: the microphone with no voice processing on it at all. */
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

    /** Keeps hold of the real track so each window can prove audio actually left the device. */
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

    private fun fixture(name: String): ByteArray = try {
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }
    } catch (e: Exception) {
        throw AssertionError(
            "Missing the speech fixture app/src/androidTest/assets/$name. It is a build input, " +
                "not a source file - synthesise it against a running VoiceManager (scripts/).",
            e,
        )
    }

    private class StopCapture : RuntimeException()

    private data class Measurement(
        val frames: Int,
        val peakDbfs: Double,
        val meanDbfs: Double,
        val framesPlayed: Int,
    ) {
        fun describe(): String =
            "frames=$frames mean=${"%.1f".format(meanDbfs)} dBFS peak=${"%.1f".format(peakDbfs)} dBFS " +
                "played=$framesPlayed"
    }

    private data class CancelledMeasurement(
        val frames: Int,
        val detections: Int,
        val peakDbfs: Double,
        val meanDbfs: Double,
        val maxFrameUs: Long,
        val meanFrameUs: Long,
        val framesPlayed: Int,
        val routedTo: String,
        val sweep: String = "",
    ) {
        fun describe(): String =
            "frames=$frames detections=$detections peak=${"%.1f".format(peakDbfs)} dBFS " +
                "mean=${"%.1f".format(meanDbfs)} dBFS " +
                "frame=${"%.2f".format(meanFrameUs / 1000.0)} ms mean, " +
                "${"%.2f".format(maxFrameUs / 1000.0)} ms max " +
                "played=$framesPlayed @ $routedTo sweep[$sweep]"
    }

    private companion object {
        const val TAG = "SoftwareErlGate"
        const val TTS_FILE = "tts60.pcm"
        const val TTS_RATE = 24_000

        /** The engines the wire declares (OpenVoice 22050, VibeVoice 24000, the 48k variant). */
        val RATES = intArrayOf(22_050, 24_000, 48_000)

        /** One fresh-reply onset: short enough to catch the leak the guard exists for. */
        const val ONSET_MS = 5_000L

        /** Mid-reply: the canceller has had this window to converge on this reply. */
        const val CONVERGED_MS = 15_000L

        /** The inter-turn gap: silence long enough for the canceller to de-adapt (REQ-01). */
        const val GAP_MS = 3_000L

        /** The reply resumed after the gap - the acceptance names this window explicitly. */
        const val POST_GAP_MS = 8_000L

        /** The positive control: long enough to be sure real speech, not a gap, was measured. */
        const val CONTROL_MS = 8_000L

        /** The calibration burst: enough audio for AudioTrack to report a presentation timestamp. */
        const val BURST_MS = 2_000L

        const val CHUNK = 4_800 // 100 ms at 24 kHz
        const val FRAME_PERIOD_US = 20_000L // the 20 ms capture frame, in microseconds
    }
}
