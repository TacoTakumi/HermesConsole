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
import com.hermesconsole.audio.AudioTrackOneShotSink
import com.hermesconsole.audio.AudioTrackSink
import com.hermesconsole.audio.AudioTrackSinkFactory
import com.hermesconsole.audio.BargeInDetector
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
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * WHICH LIVE PLAYBACK PATTERN DROPS THE HARDWARE CANCELLER (A3-A8).
 *
 * `AecHardwareGateTest` passes on this device and this OS build: 3 x 60 s of the tablet's own TTS,
 * mean -119 dBFS on the cancelled stream, zero detections, 66 dB of suppression
 * (docs/aec-spike.md, "The eleventh"). The console self-barges anyway, at -27 to -30 dBFS - the
 * level of *uncancelled* echo. So the canceller is not broken; it drops out, and the drop is total
 * while it lasts.
 *
 * The gate cannot see the drop because of how it plays: one AudioTrack, opened once, written
 * continuously in even 100 ms chunks, with nothing else on the output. The console does none of
 * those things. This test keeps the gate's measurement exactly - same fixture, same capture path,
 * same [SpeechGate], same sweep - and varies only the shape of the playback, one difference at a
 * time, until a -30 dBFS leak reproduces.
 *
 * The four differences, each measured over its own 60 s window with a steady window before it to
 * re-converge the canceller:
 *
 * - **A3 idle track between replies.** The live track is REUSED, not rebuilt: `TtsPlayer`
 *   reuses the sink whenever the declared rate repeats, and `stopImmediately` only runs on the
 *   cancel paths. So between replies a long-lived MODE_STREAM track simply runs dry, and the mixer
 *   disables a starved track and restarts it on the next reply (`prepareTracks_l BUFFER TIMEOUT`,
 *   docs/aec-spike.md). Every reply after the first meets the canceller this way.
 * - **A4 tool-call gap mid-reply.** One reply, one `audio_start`, with 8 s of silence inside it
 *   where the model ran a tool. The eighth sighting is exactly this shape.
 * - **A5 jittered, starved writes.** Chunks of uneven size arriving slower than real time - a
 *   reply being delivered over a network in fits, with the track running dry between them.
 * - **A6 earcon on a second track.** A cue is a separate MODE_STATIC one-shot on the same route,
 *   and it is never part of any reference the canceller was given for the reply underneath it.
 *
 * This is an experiment, not a gate. It asserts only two things - that the rig is live (the
 * positive control) and that the steady baseline still reproduces the gate's clean result - and it
 * asserts them at the END, so a dirty baseline still yields the full table rather than aborting on
 * the first window. Everything else is reported for reading.
 *
 * Run: `scripts/connected-test.sh AecDropoutReproTest` (about 8 minutes of playback).
 */
@RunWith(AndroidJUnit4::class)
class AecDropoutReproTest {

    @get:Rule
    val micPermission: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.RECORD_AUDIO)

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val audioManager: AudioManager
        get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Test
    fun which_live_playback_pattern_drops_the_hardware_canceller() = runBlocking {
        val pcm = runCatching {
            InstrumentationRegistry.getInstrumentation().context.assets.open(TTS_FILE)
                .use { it.readBytes() }
        }.getOrElse {
            throw AssertionError(
                "Missing the speech fixture app/src/androidTest/assets/$TTS_FILE - the same one " +
                    "AecHardwareGateTest uses, so the two runs stay comparable.",
            )
        }

        // The `debug` extra puts an Activity on screen with no capture path in the process, so the
        // only recorder is this test's own. Without it the wall console's recorder silences ours
        // and every window reads digital zero (docs/aec-spike.md, false-PASS trap 3).
        val onScreen = Intent(context, MainActivity::class.java).putExtra("debug", true)
        ActivityScenario.launch<MainActivity>(onScreen).use {
            delay(1_500)

            val volume = setMediaVolumeToNormal()
            Log.i(TAG, "=== AEC DROPOUT REPRO (A3-A8) ===")
            Log.i(TAG, "gate: ${SpeechGate.DEFAULT_THRESHOLD_DBFS} dBFS sustained over " +
                "${SpeechGate.DEFAULT_CONSECUTIVE_FRAMES} frames (${SpeechGate.DEFAULT_CONSECUTIVE_FRAMES * 20} ms)")
            Log.i(TAG, "volume: $volume of media stream max")
            Log.i(TAG, "tts: ${"%.1f".format(pcm.size / (TTS_RATE * 2.0))} s @ $TTS_RATE Hz")

            // POSITIVE CONTROL, first and always: the unprocessed mic must hear the tablet plainly.
            // A quiet reading on the production path proves nothing on its own - the voice path
            // gates room tone to near-digital-zero, so a dead mic and a perfect canceller look
            // identical. This is the check that caught the silenced recorder on 2026-08-18.
            val control = measure("POSITIVE CONTROL (unprocessed)", CONTROL_MS, pcm, aec = false) { p, d ->
                steady(p, d)
            }

            val mic = MicPipeline(audioManager, AudioConfig())
            val micStatus = mic.start()
            Log.i(TAG, "capture: ${micStatus.describe()}")

            // One detector for every window below, as the console has one for the life of its
            // capture session. Rebuilt per window it would pay the 10 s cold-boot hold each time
            // and never reach the 2 s re-arm regime the live console actually runs in.
            val console = ConsoleModel()
            console.onCaptureStarted()

            // One capture session for every window below, exactly as the console holds one open all
            // day. Opening a fresh one per window would measure a cold canceller each time.
            measure("WARM-UP (converging)", WARMUP_MS, pcm, aec = true, openMic = mic, model = console) { p, d ->
                steady(p, d)
            }

            // The experiment's own control: the gate's playback shape, in this harness, on this
            // capture session. If this does not come back clean, no variant below means anything.
            val baseline = measure("BASELINE (steady, = the gate)", WINDOW_MS, pcm, true, mic, console) { p, d ->
                steady(p, d)
            }

            val variants = listOf(
                Variant("A3 idle track between replies") { p, d -> idleBetweenReplies(p, d) },
                Variant("A4 tool-call gap mid-reply") { p, d -> toolGapMidReply(p, d) },
                Variant("A5 jittered, starved writes") { p, d -> jitteredStarvedWrites(p, d) },
                Variant("A6 earcon on a second track") { p, d -> earconDuringReply(p, d) },
                Variant("A7 live burst cadence") { p, d -> burstDelivery(p, d) },
                Variant("A8 long reply after the live idle") { p, d -> longReplyAfterIdle(p, d) },
            )

            val results = variants.map { variant ->
                // Back to steady playback first: every variant starts from the same converged
                // canceller, so what the variant window shows belongs to the variant.
                measure("  re-converge before ${variant.name}", RECONVERGE_MS, pcm, true, mic, console) { p, d ->
                    steady(p, d)
                }
                variant.name to measure(variant.name, WINDOW_MS, pcm, true, mic, console, variant.pattern)
            }

            mic.close()

            Log.i(TAG, "---")
            Log.i(TAG, "RESULT control (uncancelled): mean=${"%.1f".format(control.meanDbfs)} dBFS " +
                "peak=${"%.1f".format(control.peakDbfs)} dBFS")
            Log.i(TAG, "RESULT baseline (steady): ${baseline.describe()}")
            results.forEach { (name, r) ->
                val verdict = when {
                    r.wouldBargeIn > 0 -> "REPRODUCED, and the guard did NOT hold it"
                    r.detections > 0 -> "REPRODUCED, guard held it"
                    else -> "clean"
                }
                Log.i(TAG, "RESULT $name: ${r.describe()} -> $verdict")
            }
            Log.i(TAG, "---")
            Log.i(TAG, "SWEEP baseline: ${baseline.sweep}")
            results.forEach { (name, r) -> Log.i(TAG, "SWEEP $name: ${r.sweep}") }

            val selfBarged = results.filter { (_, r) -> r.wouldBargeIn > 0 }
            Log.i(
                TAG,
                if (selfBarged.isEmpty()) {
                    "GUARD held every leak that reproduced - the console would not have barged in " +
                        "on any of them, so the live self-barge needs something this run did not do."
                } else {
                    "GUARD did not hold: the console would have barged in on itself in " +
                        selfBarged.joinToString { "${it.first} (${it.second.wouldBargeIn}x)" }
                },
            )

            val reproduced = results.filter { (_, r) -> r.detections > 0 }
            Log.i(
                TAG,
                if (reproduced.isEmpty()) {
                    "VERDICT no pattern reproduced the dropout - the difference is elsewhere " +
                        "(combination of two, the reference the console gives the canceller, or " +
                        "something only a real turn does). Read the peaks and the sweep before " +
                        "designing the next window."
                } else {
                    "VERDICT reproduced by: ${reproduced.joinToString { it.first }}"
                },
            )

            // Asserted last, so a bad rig or a dirty baseline still leaves the whole table on the
            // record rather than aborting at the first window.
            assertTrue(
                "The unprocessed mic heard ${"%.1f".format(control.peakDbfs)} dBFS while the tablet " +
                    "was speaking - the echo path does not exist, so nothing below measured " +
                    "cancellation of anything. Check the speakers and the volume.",
                control.peakDbfs > SpeechGate.DEFAULT_THRESHOLD_DBFS,
            )
            assertTrue(
                "The tablet never spoke during the control: ${control.framesPlayed} frames played.",
                control.framesPlayed > TTS_RATE * (CONTROL_MS / 1000 / 2),
            )
            assertTrue("hardware AEC is not enabled; every window below is meaningless", micStatus.enabled)
            assertEquals(
                "The steady baseline tripped the gate ${baseline.detections} time(s), so this " +
                    "harness did not reproduce AecHardwareGateTest's clean run and the A3-A6 " +
                    "windows cannot be read against it. Fix the baseline first.",
                0,
                baseline.detections,
            )
        }
    }

    // ---- the playback patterns ------------------------------------------------------------

    /**
     * The gate's shape, and the baseline here: one track, opened once, written continuously in even
     * chunks, nothing else on the output. The fixture loops so the window can outlast it.
     */
    private suspend fun steady(playback: Playback, pcm: ByteArray) {
        playback.start(TTS_RATE)
        var offset = 0
        while (currentCoroutineContext().isActive) {
            offset = writeChunk(playback, pcm, offset)
        }
    }

    /**
     * A3: replies on a reused track, with the track left to run dry between them.
     *
     * The declared rate repeats, so [TtsPlayer.onAudioStart] reuses the open sink - the live path,
     * where only a cancel calls `stopImmediately`. What the canceller meets is therefore not a
     * fresh track but a starved one being restarted by the mixer.
     */
    private suspend fun idleBetweenReplies(playback: Playback, pcm: ByteArray) {
        var offset = 0
        while (currentCoroutineContext().isActive) {
            playback.start(TTS_RATE)
            val until = System.currentTimeMillis() + REPLY_MS
            while (currentCoroutineContext().isActive && System.currentTimeMillis() < until) {
                offset = writeChunk(playback, pcm, offset)
            }
            playback.end()
            delay(IDLE_MS)
        }
    }

    /**
     * A4: one reply with a silent tool call inside it - no second `audio_start`, the track open and
     * dry throughout the gap, then speech again (docs/aec-spike.md, "The eighth").
     */
    private suspend fun toolGapMidReply(playback: Playback, pcm: ByteArray) {
        var offset = 0
        while (currentCoroutineContext().isActive) {
            playback.start(TTS_RATE)
            offset = writeFor(playback, pcm, offset, PRE_GAP_MS)
            delay(TOOL_GAP_MS)
            offset = writeFor(playback, pcm, offset, POST_GAP_MS)
            playback.end()
        }
    }

    /**
     * A5: a reply arriving over a network in fits - uneven chunk sizes, delivered slower than real
     * time, so the track repeatedly runs dry mid-reply. Seeded, so two runs are comparable.
     */
    private suspend fun jitteredStarvedWrites(playback: Playback, pcm: ByteArray) {
        val rng = Random(42)
        playback.start(TTS_RATE)
        var offset = 0
        while (currentCoroutineContext().isActive) {
            val want = CHUNK * (1 + rng.nextInt(3)) // 100-300 ms of audio at a time
            val start = offset
            val end = minOf(start + want, pcm.size)
            playback.chunk(pcm.copyOfRange(start, end))
            offset = if (end >= pcm.size) 0 else end
            // Longer than the audio just handed over, so the track cannot keep up with itself.
            val audioMs = (end - start) / 2L * 1_000L / TTS_RATE
            delay(audioMs + rng.nextLong(80, 400))
        }
    }

    /**
     * A6: steady speech with an earcon fired on a separate one-shot track every few seconds - the
     * same output route, and audio the canceller was never handed as part of this reply.
     */
    private suspend fun earconDuringReply(playback: Playback, pcm: ByteArray) = coroutineScope {
        val cues = launch(Dispatchers.IO) {
            val sink = AudioTrackOneShotSink(audioManager, AudioConfig())
            val clip = chirp()
            while (isActive) {
                delay(CUE_EVERY_MS)
                runCatching { sink.play(clip, CUE_RATE) }
            }
        }
        try {
            steady(playback, pcm)
        } finally {
            cues.cancel()
        }
    }

    /**
     * A7: the live delivery cadence - one ~555 ms chunk handed over every ~600 ms, on one open
     * track, for the whole window.
     *
     * Every other difference between this harness and the live console has been held equal and the
     * gate still passes at 68.4 dB (docs/aec-spike.md, "The fourteenth"): same device, build,
     * volume, output usage and route, sample rate, engine and content, capture path and
     * AudioConfig. This is what is left. The period is measured from the reply log, not chosen -
     * the write itself blocks until the track accepts the audio, so only the remainder of the
     * period is slept, which is what makes the arrival-to-arrival spacing the live 600 ms rather
     * than 600 ms on top of the write.
     */
    private suspend fun burstDelivery(playback: Playback, pcm: ByteArray) {
        val burstBytes = (TTS_RATE * 2L * BURST_AUDIO_MS / 1_000L).toInt()
        playback.start(TTS_RATE)
        var offset = 0
        while (currentCoroutineContext().isActive) {
            val at = System.currentTimeMillis()
            val end = minOf(offset + burstBytes, pcm.size)
            playback.chunk(pcm.copyOfRange(offset, end))
            offset = if (end >= pcm.size) 0 else end
            val spent = System.currentTimeMillis() - at
            if (spent < BURST_PERIOD_MS) delay(BURST_PERIOD_MS - spent)
        }
    }

    /**
     * A8: A3's shape at the operator's measured scale - an 18 s wait, then a 30 s reply.
     *
     * The point is not the idle, which A3 already covers at 8 s. It is the reply: long enough that
     * the guard converges 2 s in and everything after that is a barge-in the console would have
     * acted on, which is exactly what the live records show and what no window here has produced.
     */
    private suspend fun longReplyAfterIdle(playback: Playback, pcm: ByteArray) {
        var offset = 0
        while (currentCoroutineContext().isActive) {
            delay(LONG_IDLE_MS)
            playback.start(TTS_RATE)
            offset = writeFor(playback, pcm, offset, LONG_REPLY_MS)
            playback.end()
        }
    }

    /** Hand over one chunk and return the next offset, looping the fixture. */
    private fun writeChunk(playback: Playback, pcm: ByteArray, offset: Int): Int {
        val end = minOf(offset + CHUNK, pcm.size)
        playback.chunk(pcm.copyOfRange(offset, end))
        return if (end >= pcm.size) 0 else end
    }

    /** Keep handing over chunks for [durationMs] of wall clock, and return where the fixture got to. */
    private suspend fun writeFor(playback: Playback, pcm: ByteArray, offset: Int, durationMs: Long): Int {
        var at = offset
        val until = System.currentTimeMillis() + durationMs
        while (currentCoroutineContext().isActive && System.currentTimeMillis() < until) {
            at = writeChunk(playback, pcm, at)
        }
        return at
    }

    /** A 150 ms tone with fades, standing in for an LCARS earcon: short, on the same route. */
    private fun chirp(): ByteArray {
        val samples = CUE_RATE * 150 / 1000
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val fade = minOf(1.0, minOf(i, samples - i) / (samples * 0.15))
            val v = sin(2 * PI * 1_000.0 * i / CUE_RATE) * 0.3 * fade
            val s = (v * Short.MAX_VALUE).toInt().toShort()
            out[i * 2] = (s.toInt() and 0xFF).toByte()
            out[i * 2 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    // ---- the measurement (the gate's, unchanged except for the pattern and underruns) --------

    private suspend fun measure(
        label: String,
        durationMs: Long,
        pcm: ByteArray,
        aec: Boolean,
        openMic: MicPipeline? = null,
        model: ConsoleModel = ConsoleModel(),
        pattern: suspend (Playback, ByteArray) -> Unit,
    ): Measurement = coroutineScope {
        val unprocessed = if (aec) null else openUnprocessedCapture()

        val gate = SpeechGate()
        val sweep = GateSweep()
        var detections = 0
        var wouldBargeIn = 0
        var frames = 0
        var peak = SILENCE_DBFS
        var sumDbfs = 0.0
        val deadline = System.currentTimeMillis() + durationMs

        val probe = ProbingSinkFactory(AudioTrackSinkFactory(audioManager, AudioConfig()))
        val playback: Job = launch(Dispatchers.IO) {
            TtsPlayer(probe, AudioConfig()).use { player -> pattern(Playback(player, model), pcm) }
        }

        withContext(Dispatchers.IO) {
            val source = openMic?.frames() ?: unprocessedFrames(unprocessed!!)
            try {
                source.collect { frame ->
                    val level = rmsDbfs(frame)
                    frames++
                    sumDbfs += level
                    if (level > peak) peak = level
                    sweep.accept(frame)

                    // The shipped decision, on the same frame, from the same detector the console
                    // runs - so the leak and the console's answer to it are read together.
                    val acts = model.accept(frame)
                    if (acts) wouldBargeIn++

                    if (gate.accept(frame)) {
                        detections++
                        val trigger = model.detector.trigger()
                        Log.w(
                            TAG,
                            "  DETECTION in $label at frame $frames (${"%.1f".format(level)} dBFS) " +
                                "guard: converged=${trigger.converged} " +
                                "exposure=${trigger.exposureMillis}ms speaking=${model.speaking} " +
                                "-> ${if (acts) "CONSOLE WOULD BARGE IN" else "held by the guard"}",
                        )
                    }
                    if (System.currentTimeMillis() >= deadline) throw StopCapture()
                }
            } catch (stop: StopCapture) {
                // the window closed; the only way out of an endless capture flow
            }
        }

        // Read the track before the pattern's `use` block releases it.
        val framesPlayed = probe.last?.let { runCatching { it.framesPlayed }.getOrDefault(0) } ?: 0
        val underruns = probe.last?.let { runCatching { it.underrunCount }.getOrDefault(0) } ?: 0
        val routedTo = probe.last?.let { runCatching { it.routedDeviceName }.getOrNull() } ?: "nothing"
        playback.cancelAndJoin()
        unprocessed?.run { stop(); release() }

        Measurement(
            frames = frames,
            detections = detections,
            wouldBargeIn = wouldBargeIn,
            peakDbfs = peak,
            meanDbfs = if (frames > 0) sumDbfs / frames else SILENCE_DBFS,
            framesPlayed = framesPlayed,
            underruns = underruns,
            routedTo = routedTo,
            sweep = sweep.report(),
        ).also { Log.i(TAG, "$label: ${it.describe()} played=${it.framesPlayed} out of ${it.routedTo}") }
    }

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
        val buffer = ByteArray(640) // 20 ms at 16 kHz, the production framing
        while (currentCoroutineContext().isActive) {
            val read = record.read(buffer, 0, buffer.size)
            if (read <= 0) break
            emit(buffer.copyOf(read))
        }
    }

    private fun setMediaVolumeToNormal(): String {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val level = (max * 0.7).toInt()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
        return "$level/$max (70%)"
    }

    /**
     * The console's own barge-in decision, driven exactly as `VoiceLoop` drives it.
     *
     * The leak is reproduced; the question this answers is whether the shipped guard would have
     * stopped the console acting on it. So this is the real [BargeInDetector], fed the real capture
     * frames, with the three signals VoiceLoop gives it and nothing else:
     *
     * - `onReplyStart` = `onTtsStart`: re-arm the guard, and the state becomes SPEAKING.
     * - `onChunk` = the `TtsEvent.Audio` branch: re-arm again when a chunk arrives more than
     *   [REARM_ON_PLAYBACK_GAP_MILLIS] after the previous one - the intra-reply tool-gap re-arm
     *   from the eighth sighting.
     * - `onReplyEnd` = `onTtsEnd` -> `endTurn`: the state leaves SPEAKING.
     *
     * `playing` is the SPEAKING state, which is what VoiceLoop passes and what stays true straight
     * through a mid-reply tool gap. One instance for the whole test, like the console's own: a
     * guard rebuilt per window would pay its 10 s cold-boot window again and mask the failure.
     */
    private class ConsoleModel {
        val detector = BargeInDetector()

        @Volatile
        var speaking = false
            private set

        private var lastChunkAt = 0L

        fun onCaptureStarted() = detector.onCaptureStarted()

        fun onReplyStart() {
            detector.onPlaybackStarted()
            lastChunkAt = 0L
            speaking = true
        }

        fun onChunk() {
            val at = System.currentTimeMillis()
            if (lastChunkAt != 0L && at - lastChunkAt >= REARM_ON_PLAYBACK_GAP_MILLIS) {
                detector.onPlaybackStarted()
            }
            lastChunkAt = at
        }

        fun onReplyEnd() {
            speaking = false
        }

        /** One capture frame, judged the way the capture loop judges it. True = the console acts. */
        fun accept(frame: ByteArray): Boolean = detector.accept(frame, playing = speaking) && speaking
    }

    /** A player and the console model that the same call has to move, so no pattern can drive one and not the other. */
    private class Playback(private val player: TtsPlayer, private val model: ConsoleModel) {
        fun start(rateHz: Int) {
            player.onAudioStart(rateHz)
            model.onReplyStart()
        }

        fun chunk(pcm: ByteArray) {
            player.onChunk(pcm)
            model.onChunk()
        }

        fun end() {
            player.onAudioEnd()
            model.onReplyEnd()
        }
    }

    private class ProbingSinkFactory(private val inner: AudioSinkFactory) : AudioSinkFactory {
        var last: AudioTrackSink? = null
        override fun create(sampleRateHz: Int): AudioSink =
            inner.create(sampleRateHz).also { last = it as? AudioTrackSink }
    }

    private class StopCapture : RuntimeException()

    private class Variant(
        val name: String,
        val pattern: suspend (Playback, ByteArray) -> Unit,
    )

    private data class Measurement(
        val frames: Int,
        val detections: Int,
        val wouldBargeIn: Int,
        val peakDbfs: Double,
        val meanDbfs: Double,
        val framesPlayed: Int,
        val underruns: Int,
        val routedTo: String,
        val sweep: String = "",
    ) {
        fun describe(): String =
            "frames=$frames detections=$detections wouldBargeIn=$wouldBargeIn " +
                "peak=${"%.1f".format(peakDbfs)} dBFS " +
                "mean=${"%.1f".format(meanDbfs)} dBFS underruns=$underruns"
    }

    private companion object {
        const val TAG = "AecRepro"
        const val TTS_FILE = "tts60.pcm"
        const val TTS_RATE = 48_000
        const val CHUNK = 9_600 // 100 ms at 48 kHz, the gate's chunk

        /** Each variant gets the same window the gate gives a run. */
        const val WINDOW_MS = 60_000L
        const val WARMUP_MS = 20_000L
        const val RECONVERGE_MS = 20_000L
        const val CONTROL_MS = 10_000L

        /** A3: a reply's length, and the quiet between replies that starves the track. */
        const val REPLY_MS = 6_000L
        const val IDLE_MS = 8_000L

        /** A4: the eighth sighting's shape - speech, a tool call's silence, speech. */
        const val PRE_GAP_MS = 4_000L
        const val TOOL_GAP_MS = 8_000L
        const val POST_GAP_MS = 8_000L

        /**
         * Mirrors VoiceLoop.REARM_ON_PLAYBACK_GAP_MILLIS, which is private to its companion.
         * Keep the two in step: this is the gap that re-arms the guard inside one reply.
         */
        const val REARM_ON_PLAYBACK_GAP_MILLIS = 2_000L

        /** A6: an earcon every few seconds, at the cue path's own rate. */
        const val CUE_EVERY_MS = 5_000L

        /**
         * A7: the live delivery cadence, measured off the console's own reply log on 2026-08-19
         * (docs/aec-spike.md, "The fourteenth"). A reply arrives as ~555 ms of audio per chunk,
         * one chunk every ~600 ms - marginally slower than real time, so the deep-buffer track is
         * paced at about 50 ms of backlog for the whole reply and never builds a cushion. The
         * gate's own shape (100 ms chunks written back to back) fills the track instead.
         */
        const val BURST_AUDIO_MS = 555L
        const val BURST_PERIOD_MS = 600L

        /**
         * A8: the operator's own session, measured off the 2026-08-19 demo - an 18 s wait between
         * replies (his wait while the run thinks, not an idle console) and then a 32 s reply.
         *
         * A3's replies are 6 s, so its leaks are only ever observed inside the guard's 2 s re-arm
         * window and it cannot answer the question the live trace raises: does the leak DIE about
         * 1.8 s after playback resumes, or does it persist? Reply 2 of the demo leaked from +2.05 s
         * to +22.25 s of one unbroken reply. A reply long enough to outlast the window is the only
         * way to tell those two apart.
         */
        const val LONG_IDLE_MS = 18_000L
        const val LONG_REPLY_MS = 30_000L
        const val CUE_RATE = 24_000
    }
}
