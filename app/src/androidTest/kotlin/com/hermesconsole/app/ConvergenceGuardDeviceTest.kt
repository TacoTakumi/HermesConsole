package com.hermesconsole.app

import android.content.Context
import android.media.AudioManager
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
import com.hermesconsole.audio.BargeInDetector
import com.hermesconsole.audio.ConvergenceGuard
import com.hermesconsole.audio.MicPipeline
import com.hermesconsole.audio.SILENCE_DBFS
import com.hermesconsole.audio.SpeechGate
import com.hermesconsole.audio.TtsPlayer
import com.hermesconsole.audio.rmsDbfs
import java.util.concurrent.atomic.AtomicBoolean
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * THE CONVERGENCE GUARD ON HARDWARE (T-32, REQ-05/REQ-04).
 *
 * The one condition the hardware gate came back with. A cold echo canceller leaks the tablet's own
 * voice - at up to -15 dBFS, as loud as a person at the wall mount - for the first seconds of
 * playback after a capture session opens (docs/aec-spike.md). Unguarded, the console interrupts
 * itself on its first sentence after every boot. [ConvergenceGuard] holds the barge-in *action*
 * back until the canceller has heard enough of the console's own voice to be trusted.
 *
 * On one cold session and one continuous 60 s answer, this test holds the guard to three things:
 *   1. inside the window, the console acts on no barge-in - whatever the raw gate saw;
 *   2. once converged, echo alone with nobody speaking still fires nothing;
 *   3. and a person at 2 m DOES fire barge-in, in that same turn. A guard that never lifts would
 *      pass the first two and quietly kill REQ-04, so the third is the one that keeps it honest.
 *
 * What the raw gate saw inside the window is reported, not asserted: the leak did not reproduce on
 * re-measurement, and a test that demands it would fail on a healthy device (docs/aec-spike.md).
 *
 * HUMAN IN THE LOOP. This test needs someone in the room:
 *   - stay SILENT while the tablet starts talking (about the first 30 s);
 *   - when you hear the BEEP over the tablet's voice, TALK over it, from about 2 m, until it stops.
 * The beep is a harness cue, not a console sound. Detections in the second it sounds are blanked -
 * and logged, so a self-barge on the cue cannot hide inside the blanking.
 */
@RunWith(AndroidJUnit4::class)
class ConvergenceGuardDeviceTest {

    @get:Rule
    val micPermission: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.RECORD_AUDIO)

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val audioManager: AudioManager
        get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Test
    fun a_cold_echo_canceller_never_barges_in_on_the_console_itself(): Unit = runBlocking {
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

        // The microphone is only live with the console actually on screen (see AecHardwareGateTest).
        ActivityScenario.launch(MainActivity::class.java).use {
            delay(1_500) // let the shell's own startup self-check release its recorder

            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (max * 0.7).toInt(), 0)

            Log.i(TAG, "=== CONVERGENCE GUARD ===")
            Log.i(TAG, "window: ${ConvergenceGuard.DEFAULT_WINDOW_MS} ms of playback exposure")
            Log.i(TAG, "gate:   ${SpeechGate.DEFAULT_THRESHOLD_DBFS} dBFS sustained over " +
                "${SpeechGate.DEFAULT_CONSECUTIVE_FRAMES} frames")
            Log.i(TAG, "HUMAN: stay silent until the BEEP, then talk over the tablet from ~2 m")

            // A COLD session, opened and immediately spoken over: the console's first TTS after boot.
            val mic = MicPipeline(audioManager, AudioConfig())
            assertTrue("hardware AEC is not enabled; the guard is measuring nothing", mic.start().enabled)

            val detector = BargeInDetector().apply { onCaptureStarted() }
            val rawGate = SpeechGate() // the unguarded path, for comparison: what the console WOULD have done

            val probe = ProbingSinkFactory(AudioTrackSinkFactory(audioManager, AudioConfig()))
            val playing = AtomicBoolean(true)
            var playback: Job? = null

            val leaks = mutableListOf<Detection>()      // raw gate, inside the window: the self-barge avoided
            val cold = mutableListOf<Detection>()       // barge-in ACTIONS inside the window: must be none
            val convergedQuiet = mutableListOf<Detection>() // actions after the guard, human still silent: must be none
            val human = mutableListOf<Detection>()      // actions once the human speaks: must be at least one
            val blanked = mutableListOf<Detection>()    // actions during the cue beep: excluded, but never hidden

            var peakInWindow = SILENCE_DBFS
            var frames = 0
            var cuedAtMs = -1L
            var convergedAtMs = -1L

            // Sampled while the answer is still playing, never after: the sink releases its
            // AudioTrack when the answer ends, and a released track cannot be asked what it played.
            var framesPlayed = 0
            var routedTo: String? = null

            coroutineScope {
                playback = launch(Dispatchers.IO) { play(pcm, probe); playing.set(false) }
                val started = System.currentTimeMillis()

                withContext(Dispatchers.IO) {
                    try {
                        mic.frames().collect { frame ->
                            val atMs = System.currentTimeMillis() - started
                            val level = rmsDbfs(frame)
                            frames++

                            probe.last?.let { sink ->
                                runCatching { sink.framesPlayed }.getOrNull()
                                    ?.let { if (it > framesPlayed) framesPlayed = it }
                                runCatching { sink.routedDeviceName }.getOrNull()
                                    ?.let { routedTo = it }
                            }

                            val wasCold = !detector.isConverged
                            val action = detector.accept(frame, playing = playing.get())
                            val raw = rawGate.accept(frame)

                            if (wasCold) {
                                if (level > peakInWindow) peakInWindow = level
                                if (raw) {
                                    leaks += Detection(atMs, level)
                                    Log.w(TAG, "  LEAK (suppressed) at ${atMs}ms ${"%.1f".format(level)} dBFS")
                                }
                                if (action) cold += Detection(atMs, level)
                            }

                            if (!detector.isConverged) {
                                // still cold - nothing below applies yet
                            } else {
                                if (convergedAtMs < 0) {
                                    convergedAtMs = atMs
                                    Log.i(TAG, "GUARD LIFTED at ${atMs}ms - barge-in is live")
                                }
                                // A quiet stretch first: converged echo alone must fire nothing. Without
                                // it, residual echo could masquerade as the human below.
                                if (cuedAtMs < 0 && atMs - convergedAtMs >= CONVERGED_QUIET_MS) {
                                    cuedAtMs = atMs
                                    Log.i(TAG, "=== BEEP - SPEAK NOW, over the tablet, from ~2 m ===")
                                    launch(Dispatchers.IO) { beep() }
                                }
                                if (action) {
                                    val d = Detection(atMs, level)
                                    when {
                                        cuedAtMs < 0 -> {
                                            convergedQuiet += d
                                            Log.w(TAG, "  ACTION while converged and quiet at ${atMs}ms ${"%.1f".format(level)} dBFS")
                                        }
                                        atMs - cuedAtMs < BLANK_MS -> {
                                            blanked += d
                                            Log.w(TAG, "  ACTION during the cue beep at ${atMs}ms ${"%.1f".format(level)} dBFS (blanked)")
                                        }
                                        else -> {
                                            human += d
                                            Log.i(TAG, "  BARGE-IN at ${atMs}ms ${"%.1f".format(level)} dBFS")
                                        }
                                    }
                                }
                            }

                            if (!playing.get() || atMs >= MAX_MS) throw StopCapture()
                        }
                    } catch (stop: StopCapture) {
                        // the answer ended; the only way out of an endless capture flow
                    }
                }
                playback?.cancelAndJoin()
            }

            mic.close()

            Log.i(TAG, "---")
            Log.i(TAG, "RESULT captured $frames frames, played $framesPlayed frames out of $routedTo")
            Log.i(TAG, "RESULT leak (unguarded gate, cold): ${leaks.size} detection(s) at " +
                "${leaks.joinToString { "${it.atMs}ms/${"%.1f".format(it.dbfs)}dBFS" }} peak=${"%.1f".format(peakInWindow)} dBFS")
            Log.i(TAG, "RESULT barge-in actions while cold: ${cold.size} (must be 0)")
            Log.i(TAG, "RESULT barge-in actions while converged and quiet: ${convergedQuiet.size} (must be 0)")
            Log.i(TAG, "RESULT barge-in actions on the human: ${human.size} (must be >= 1)")
            Log.i(TAG, "RESULT actions blanked by the cue beep: ${blanked.size}")

            // The tablet really spoke - otherwise there was no echo to leak and no answer to barge in on.
            assertTrue(
                "AudioTrack played $framesPlayed frames: the tablet never spoke, so nothing here was measured.",
                framesPlayed > TTS_RATE * 30,
            )

            // Did the leak reproduce? Reported, never asserted - and never silently.
            //
            // It did on the morning of 2026-07-12 (detections at 1.3s/1.7s/5.4s, peaking at -15
            // dBFS) and it did not that same afternoon, on the same build, the same volume and the
            // same room, in this harness and in T-04's own (docs/aec-spike.md). A test that demands
            // the leak would now fail on a device that is behaving; a test that quietly passes
            // without it would let "the guard suppressed nothing" read as "the guard works". So the
            // run says which of the two it was, in as many words.
            if (leaks.isEmpty()) {
                Log.w(TAG, "NOTE the leak did not reproduce: the cold canceller peaked at " +
                    "${"%.1f".format(peakInWindow)} dBFS and never tripped the gate. The guard " +
                    "suppressed nothing this run - it was not asked to. What is proven below is " +
                    "that it acts on no echo and does not stand in a person's way; that it holds " +
                    "the leak back is proven by the unit tests and by the measurement in " +
                    "docs/aec-spike.md, not by this run.")
            } else {
                Log.i(TAG, "NOTE the leak reproduced and the guard suppressed all ${leaks.size} of " +
                    "it: the console would have interrupted itself ${leaks.size} time(s).")
            }

            // THE GUARD.
            assertEquals(
                "The console barged in on its own voice ${cold.size} time(s) while the echo canceller " +
                    "was cold - it interrupts itself on its first answer after boot (REQ-05).",
                0,
                cold.size,
            )
            assertEquals(
                "Converged echo alone produced ${convergedQuiet.size} barge-in action(s) with nobody " +
                    "speaking. The hardware gate says this must be zero (REQ-05).",
                0,
                convergedQuiet.size,
            )

            // AND THE GUARD LIFTS: a guard that never lets go would pass everything above and kill
            // barge-in outright (REQ-04). The person spoke in the same turn, over the same answer.
            assertTrue(
                "Barge-in never fired on the human. Either the guard never lifted - REQ-04 is dead and " +
                    "the console cannot be interrupted - or nobody spoke after the beep. The log above " +
                    "says which: the guard lifted at ${convergedAtMs}ms and the cue sounded at ${cuedAtMs}ms.",
                human.isNotEmpty(),
            )
            Log.i(TAG, "VERDICT PASS - the console acted on no echo of its own" +
                (if (leaks.isEmpty()) "" else " (${leaks.size} leak detection(s) suppressed)") +
                ", the guard lifted, and the person was heard ${human.size} time(s)")
        }
    }

    /** Play the answer out of the built-in speakers, exactly the way the console will. */
    private suspend fun play(pcm: ByteArray, factory: AudioSinkFactory) {
        TtsPlayer(factory, AudioConfig()).use { player ->
            player.onAudioStart(TTS_RATE)
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
     * The harness's cue to the human, mixed over the answer. Not a console sound.
     *
     * Two tones, well above the talking voice it has to cut through - a single quiet blip is
     * indistinguishable from the answer stuttering, which is exactly how the first run of this test
     * failed to cue anyone. And a track must be given time to play what was written to it before it
     * is released: a blocking write returns when the buffer takes the audio, not when the speaker
     * has finished with it, so releasing straight away truncates the cue to a click.
     */
    private fun beep() {
        val tone = ByteArray(BEEP_SAMPLES * 2)
        for (i in 0 until BEEP_SAMPLES) {
            val fade = minOf(1.0, minOf(i, BEEP_SAMPLES - i) / (TTS_RATE * 0.02))
            val v = (0.6 * fade * Short.MAX_VALUE *
                kotlin.math.sin(2 * Math.PI * 880 * i / TTS_RATE)).toInt().toShort()
            tone[i * 2] = (v.toInt() and 0xFF).toByte()
            tone[i * 2 + 1] = ((v.toInt() shr 8) and 0xFF).toByte()
        }
        val gap = ByteArray(BEEP_SAMPLES) // 125 ms of silence between the two tones
        AudioTrackSink(TTS_RATE).use { sink ->
            sink.write(tone)
            sink.write(gap)
            sink.write(tone)
            Thread.sleep(BEEP_PLAYOUT_MS)
        }
    }

    /** Keeps hold of the real track so the harness can prove audio actually left the device. */
    private class ProbingSinkFactory(private val inner: AudioSinkFactory) : AudioSinkFactory {
        var last: AudioTrackSink? = null
        override fun create(sampleRateHz: Int): AudioSink =
            inner.create(sampleRateHz).also { last = it as? AudioTrackSink }
    }

    private data class Detection(val atMs: Long, val dbfs: Double)

    private class StopCapture : RuntimeException()

    private companion object {
        const val TAG = "Converge"
        const val TTS_FILE = "tts60.pcm"
        const val TTS_RATE = 24_000
        const val CHUNK = 4_800 // 100 ms at 24 kHz

        /** Converged, with nobody speaking: echo alone must fire nothing before the human is cued. */
        const val CONVERGED_QUIET_MS = 8_000L

        /** 250 ms per tone, twice, over the answer: audible as a cue and not as a stutter. */
        const val BEEP_SAMPLES = TTS_RATE / 4
        const val BEEP_PLAYOUT_MS = 900L

        /** The cue beep's own echo is the harness talking, not the console. Excluded, and logged. */
        const val BLANK_MS = 2_500L

        /** A backstop: the answer is 60 s and the capture flow does not end on its own. */
        const val MAX_MS = 75_000L
    }
}
