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
import com.hermesconsole.audio.AudioSink
import com.hermesconsole.audio.AudioSinkFactory
import com.hermesconsole.audio.AudioTrackSink
import com.hermesconsole.audio.AudioTrackSinkFactory
import com.hermesconsole.audio.BargeInDetector
import com.hermesconsole.audio.MicPipeline
import com.hermesconsole.audio.NativeSoftwareAec
import com.hermesconsole.audio.SILENCE_DBFS
import com.hermesconsole.audio.SpeechGate
import com.hermesconsole.audio.TtsPlayer
import com.hermesconsole.audio.rmsDbfs
import com.hermesconsole.hermesclient.HermesEvent
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
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * THE REQ-05 MEASUREMENT (T-15, re-run for T-11), on the SM-X210 against the live VoiceManager.
 *
 * The console must never interrupt itself, wake itself, or transcribe itself. T-04 proved the
 * physics at the audio layer - hardware AEC suppresses the tablet's own voice below the barge-in
 * gate - but the requirement is about the LOOP, not the layer: the shipping capture path feeds
 * three listeners at once (barge-in, wake word, STT), and any one of them believing the echo is a
 * person breaks the console in a different way. Barge-in fires and the console cuts itself off
 * mid-sentence; the wake word fires and the next turn opens on nobody; STT transcribes the reply
 * and the endpoint-hold supersedes the run with the console's own words. So this runs all three
 * against the real thing at once: real microphone, real software echo canceller (T-11, REQ-01),*
 * real speaker at normal volume, the production [BargeInDetector] on its production window, the
 * production [WakeWordDetector] on the same frames, and live STT streaming for the whole turn -
 * three separate 60 s playbacks, in a room where nobody is speaking.
 *
 * The canceller under test is the SOFTWARE AEC (the OTA broke this device's hardware AEC - docs/
 * aec-spike.md "The tenth"): the loop is wired with the production aecFactory (one AEC3 session
 * per capture session, seeded with the measured output latency), and the capture stream the
 * listeners see is the cancelled one. REQ-05 now rests on this path, not on the hardware effect
 * that the self-check claims is on while cancelling nothing.
 *
 * PASS: across all three playbacks, zero barge-in triggers, zero wake-word triggers, and zero
 * non-empty STT partials from the echo path.
 *
 * What is scripted, and why it may be:
 *
 *  - The QUESTION that starts each turn is the T-14 fixture, injected into the capture stream.
 *    A turn must start the way a real one does - wake, STT, dispatch - because that is what leaves
 *    the microphone streaming to STT while the console speaks, and that live STT path is a third
 *    of what this test measures. But the question's own audio is not what REQ-05 is about, and a
 *    human reading it three times would not be silent for the 60 s that follow.
 *  - The AGENT is scripted: a fixed ~90 s reply, so each playback is deterministically long enough
 *    and the measurement never depends on how chatty Hermes felt. The echo path this test proves
 *    runs speaker -> room -> microphone -> AEC; Hermes is upstream of all of it.
 *  - The WAKE that opens each turn is scripted for the same reason as in T-14 - the phrase itself
 *    is measured live by T-34 - but the real detector still hears every frame of the session, and
 *    every detection it makes is counted against REQ-05.
 *
 * Skipped unless VoiceManager's key is available (REQ-28: nothing is compiled in, nothing is
 * logged). The key comes from the console's settings store (T-41: an arg-passed key is logged
 * by adbd); an explicit vmKey=/vmHost= argument still overrides, for scratch backends:
 *
 *     scripts/connected-test.sh NeverSelfBargeTest
 *
 * THE ROOM MUST BE QUIET. A person speaking near the tablet during a playback is a real barge-in,
 * and the test will count it as a failure it is right to count. Walk away for five minutes.
 */
@RunWith(AndroidJUnit4::class)
class NeverSelfBargeTest {

    @get:Rule
    val micPermission: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.RECORD_AUDIO)

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val audioManager: AudioManager
        get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /**
     * The production capture path with the question spliced in - the same splice as T-14, plus a
     * level tap so each playback window can report what the AEC-processed stream actually carried.
     * The stats are written only by the capture collector thread; readers see a settled window.
     */
    private class ScriptedCapture(private val mic: MicPipeline) : AudioCapture {
        override val config: AudioConfig get() = mic.config

        private val scripted = ConcurrentLinkedQueue<ByteArray>()

        /** When the last scripted frame went in - how a barge-in is told apart from a fixture. */
        @Volatile var lastScriptedFrameAtMillis = 0L
            private set

        val scriptedPending: Boolean get() = scripted.isNotEmpty()

        @Volatile private var windowOpen = false
        @Volatile private var windowFrames = 0
        @Volatile private var windowPeakDbfs = SILENCE_DBFS
        @Volatile private var windowSumDbfs = 0.0

        /** Say [pcm] into the microphone: 16 kHz mono PCM16, as the fixture was synthesised. */
        fun inject(pcm: ByteArray) {
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

        fun windowStart() {
            windowFrames = 0
            windowPeakDbfs = SILENCE_DBFS
            windowSumDbfs = 0.0
            windowOpen = true
        }

        fun windowClose(): String {
            windowOpen = false
            val frames = windowFrames
            val mean = if (frames > 0) windowSumDbfs / frames else SILENCE_DBFS
            return "$frames frames, mean ${"%.1f".format(mean)} dBFS, " +
                "peak ${"%.1f".format(windowPeakDbfs)} dBFS"
        }

        override fun frames(): Flow<ByteArray> = mic.frames().map { live ->
            val spoken = scripted.poll()
            if (spoken != null) lastScriptedFrameAtMillis = System.currentTimeMillis()
            val frame = spoken ?: live
            if (windowOpen) {
                val level = rmsDbfs(frame)
                windowFrames++
                windowSumDbfs += level
                if (level > windowPeakDbfs) windowPeakDbfs = level
            }
            frame
        }

        override fun close() = mic.close()
    }

    /**
     * The real wake-word detector, listening to every frame of the session - with its verdicts
     * counted before they reach the loop, because the loop deliberately ignores a wake during a
     * turn and REQ-05 is about the detector TRIGGERING, not about what the loop does next. The
     * scripted [fire] is additive: it opens each turn without a human, exactly as in T-14.
     *
     * [reset] is a no-op here, deliberately, for two reasons. Stricter: the loop resets the
     * detector at every turn end to stop a turn's audio waking the next one, and this measurement
     * wants the opposite - a hypothesis the echo half-built must stay and get its chance to fire,
     * or a trigger could be hidden by the very hygiene that exists because of it. Safer: sherpa's
     * OnlineStream is not thread-safe, and the loop calls reset() off the collector thread; the
     * detector already resets itself after every hit (WakeWordDetector), so nothing is retained
     * across a detection either way.
     */
    private class CountingWake(private val real: WakeWordDetector) : WakeWordSource {
        val echoDetections = AtomicInteger(0)
        private val scripted = Channel<WakeWord>(Channel.UNLIMITED)

        override fun detections(frames: Flow<ByteArray>): Flow<WakeWord> = merge(
            real.detections(frames).onEach {
                echoDetections.incrementAndGet()
                Log.w(TAG, "WAKE TRIGGER from the console's own voice: $it")
            },
            scripted.consumeAsFlow(),
        )

        fun fire() = check(scripted.trySend(WakeWord(WAKE_PHRASE, System.currentTimeMillis())).isSuccess)

        override fun reset() = Unit
        override fun close() = real.close()
    }

    /**
     * The agent, scripted: the same ~90 s reply for every turn, delivered as streaming deltas the
     * way Hermes delivers them. [dispatches] and [stops] are counters in this measurement's
     * evidence: a barge-in or a supersede would stop a run and dispatch another, so across three
     * clean playbacks the counts must read exactly three and exactly zero.
     */
    private class ScriptedAgent(private val reply: List<String>) : Agent {
        val dispatches = AtomicInteger(0)
        val stops = AtomicInteger(0)

        override suspend fun dispatch(input: String, sessionId: String): String =
            "self-barge-run-${dispatches.incrementAndGet()}"

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

    /** Keeps hold of the real track so each playback can prove audio actually left the device. */
    private class ProbingSinkFactory(private val inner: AudioSinkFactory) : AudioSinkFactory {
        @Volatile var last: AudioTrackSink? = null
        @Volatile var lastRate: Int = 0

        override fun create(sampleRateHz: Int): AudioSink {
            lastRate = sampleRateHz
            return inner.create(sampleRateHz).also { last = it as? AudioTrackSink }
        }
    }

    @Test
    fun three_60s_playbacks_produce_zero_barge_ins_zero_wakes_zero_echo_partials(): Unit = runBlocking {
        val backends = TestBackends.resolve()
        assumeTrue(TestBackends.NEEDS_VM, backends.vmKey.isNotBlank())
        val question = fixture(QUESTION_FILE)

        // The mic is only live while the console is actually on screen (Android 12+ hands a
        // background process silence, not an error - T-04 learned this the hard way).
        // The debug launch (T-17), for the same reason as CueQuietTest: on a store-configured
        // device the shipping console would hold the capture path against this test's own rig.
        val activity = Intent(context, MainActivity::class.java).putExtra("debug", true)
        ActivityScenario.launch<MainActivity>(activity).use {
            delay(1_500) // let the shell's startup self-check finish and release its recorder

            val volume = setMediaVolumeToNormal()
            Log.i(TAG, "=== REQ-05: NEVER SELF-BARGE ===")
            Log.i(TAG, "volume: $volume")

            // POSITIVE CONTROL - the honesty check this measurement cannot be trusted without.
            // The voice-communication path gates non-speech to digital zeros, so "the microphone
            // heard nothing" is both this test's PASS and exactly what a dead rig looks like.
            // Prove sound travels speaker -> room -> microphone before concluding anything.
            proveTheEchoPathIsLive()

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
            Log.i(TAG, "mic: ${aec.describe()}")

            // REQ-05 rests on the SOFTWARE canceller now (T-11): prove the library builds a
            // session here, then let the loop build its own at capture start through the shipping
            // factory. A device that cannot create one would run raw capture and self-barge -
            // which is a verdict, but not the one REQ-05 wants, so the probe is load-bearing.
            val probeAec = runCatching { NativeSoftwareAec.create() }.getOrNull()
            assertTrue(
                "the software AEC could not be created; REQ-05 has no canceller to rest on",
                probeAec != null,
            )
            probeAec?.close()
            Log.i(TAG, "software AEC: session probe OK; the loop builds its own at capture start")

            val capture = ScriptedCapture(mic)
            val wake = CountingWake(WakeWordDetector(context))
            val agent = ScriptedAgent(REPLY)
            val detector = BargeInDetector() // the production detector, production window
            val probe = ProbingSinkFactory(AudioTrackSinkFactory(audioManager, AudioConfig()))
            val loop = VoiceLoop(
                capture = capture,
                wake = wake,
                voice = voice.asVoiceLink(),
                agent = agent,
                player = TtsPlayer(probe),
                sessionId = { "session-never-self-barge" },
                bargeIn = detector,
                // T-11: the shipping factory - one AEC3 session per capture session, seeded with
                // the measured output latency, reference fed from the played PCM (T-08/T-09). The
                // factory may return null (no library); then this measurement runs raw capture.
                aecFactory = { runCatching { NativeSoftwareAec.create() }.getOrNull() },
            )

            try {
                loop.start()
                val partialTotals = (1..RUNS).map { run ->
                    playback(run, loop, capture, wake, agent, voice, question, detector, probe)
                }

                Log.i(TAG, "---")
                Log.i(TAG, "RESULT barge-in triggers = ${if (loop.lastBargeIn.value == null) 0 else 1}+ (must be 0)")
                Log.i(TAG, "RESULT wake-word triggers = ${wake.echoDetections.get()} (must be 0)")
                Log.i(TAG, "RESULT non-empty echo partials = ${partialTotals.sum()} (must be 0)")
                Log.i(TAG, "RESULT runs dispatched = ${agent.dispatches.get()} (must be $RUNS), stopped = ${agent.stops.get()} (must be 0)")
                val pass = loop.lastBargeIn.value == null && wake.echoDetections.get() == 0 &&
                    partialTotals.sum() == 0 && agent.stops.get() == 0 && agent.dispatches.get() == RUNS
                Log.i(TAG, "VERDICT ${if (pass) "PASS" else "FAIL"}")

                assertNull(
                    "the console barged in on itself: ${loop.lastBargeIn.value}",
                    loop.lastBargeIn.value,
                )
                assertEquals(
                    "the console's own voice triggered the wake-word detector",
                    0,
                    wake.echoDetections.get(),
                )
                assertEquals("the echo path produced STT partials", 0, partialTotals.sum())
                assertEquals("a run was stopped in a session nobody interrupted", 0, agent.stops.get())
                assertEquals(
                    "more runs were dispatched than turns were started - a turn was superseded " +
                        "by something STT heard, and nobody spoke",
                    RUNS,
                    agent.dispatches.get(),
                )
            } finally {
                loop.close()
                voice.shutdown()
            }
        }
    }

    /** One scripted turn with a ~90 s spoken reply, measured. Returns the echo-partial count. */
    private suspend fun playback(
        run: Int,
        loop: VoiceLoop,
        capture: ScriptedCapture,
        wake: CountingWake,
        agent: ScriptedAgent,
        voice: VoiceManagerClient,
        question: ByteArray,
        detector: BargeInDetector,
        probe: ProbingSinkFactory,
    ): Int = coroutineScope {
        assertEquals(
            "run $run cannot start: the console is not READY - " +
                "the previous turn's echo did something to it",
            ConsoleState.READY,
            loop.state.value,
        )

        wake.fire()
        withTimeout(5_000) { loop.state.first { it == ConsoleState.LISTENING } }
        capture.inject(question)
        withTimeout(QUESTION_TIMEOUT_MS) { loop.state.first { it == ConsoleState.THINKING } }
        val prompt = loop.turn.value.prompt
        assertTrue("run $run: STT heard nothing of the question", prompt.isNotBlank())
        Log.i(TAG, "RUN $run/$RUNS: heard \"$prompt\", speaking")

        withTimeout(SPEAK_START_TIMEOUT_MS) { loop.state.first { it == ConsoleState.SPEAKING } }
        val speakingAt = System.currentTimeMillis()
        // The sink outlives the turn, so its frame counter is cumulative: measure this run's delta.
        val framesAtStart = runCatching { probe.last?.framesPlayed ?: 0 }.getOrElse { 0 }
        capture.windowStart()

        // Everything STT has said so far belongs to the question. Anything non-blank it says
        // beyond this - a new segment, or a revision of one it had settled - can only be the
        // console's own voice coming back through the microphone, because nobody else is speaking.
        val baseline = snapshot(voice.transcript.value)
        val partials = LinkedHashSet<String>()
        val watcher = launch {
            voice.transcript.collect { transcript ->
                echoText(transcript, baseline).forEach {
                    if (partials.add(it)) Log.w(TAG, "STT PARTIAL from the echo path: $it")
                }
            }
        }

        val outcome = withTimeout(PLAYBACK_TIMEOUT_MS) {
            loop.state.first { it == ConsoleState.READY || it == ConsoleState.LISTENING }
        }
        val speakingMs = System.currentTimeMillis() - speakingAt
        watcher.cancelAndJoin()
        // Belt and braces: a StateFlow conflates rapid updates, so re-diff the settled transcript.
        echoText(voice.transcript.value, baseline).forEach { partials.add(it) }
        val levels = capture.windowClose()

        // LISTENING here means barge-in: the console cut its own reply off. Before failing,
        // capture the one thing that says WHY - the frames that fired the gate went to STT too,
        // and what they transcribe as is the difference between the echo leaking through the
        // canceller (nothing intelligible, or the reply's own words) and somebody in the room
        // making a noise the test is right to count (their words, plainly).
        if (outcome == ConsoleState.LISTENING) {
            val bargeIn = loop.lastBargeIn.value
            val intoPlaybackMs = bargeIn?.let { it.speechAtMillis - speakingAt }
            val heard = withTimeoutOrNull(INTERRUPTION_EVIDENCE_MS) {
                voice.transcript.first { echoText(it, baseline).isNotEmpty() }
            }
            val evidence = heard?.let { echoText(it, baseline).joinToString() }
                ?: "nothing transcribable within ${INTERRUPTION_EVIDENCE_MS / 1000} s"
            Log.e(TAG, "RUN $run/$RUNS: BARGE-IN ${intoPlaybackMs}ms into playback: $bargeIn")
            Log.e(TAG, "RUN $run/$RUNS: what STT made of the 'interruption': $evidence")

            // If scripted audio was still being spliced in when the gate confirmed speech, then a
            // real voice - the fixture's - WAS speaking over the console, and the console was right
            // to stop (REQ-04). That is the harness barging in on itself: an early endpoint let the
            // reply start before the question finished. Report it as its own kind of failure so it
            // is never read as the echo leaking.
            val fixtureWasSpeaking = capture.scriptedPending || (bargeIn != null &&
                bargeIn.speechAtMillis - capture.lastScriptedFrameAtMillis < ARTIFACT_MARGIN_MS)
            if (fixtureWasSpeaking) {
                fail(
                    "run $run: HARNESS ARTIFACT, not a REQ-05 violation - VoiceManager endpointed " +
                        "the question early (dispatched \"$prompt\") and the fixture's tail spoke " +
                        "over the reply; the barge-in was REQ-04 behaving. STT heard: $evidence. " +
                        "Re-run; if it recurs, the opener fixture needs to be shorter or cleaner.",
                )
            }
            fail(
                "run $run: the console interrupted itself ${intoPlaybackMs}ms into its own " +
                    "playback, with nobody speaking. STT heard: $evidence. ($bargeIn)",
            )
        }

        val turn = loop.turn.value
        // Guarded: a torn-down turn releases its track, and evidence must never crash the court.
        // A fresh sink starts its counter over, so a delta below the start means "count from zero".
        val framesEnd = runCatching { probe.last?.framesPlayed ?: 0 }.getOrElse { -1 }
        val framesPlayed = when {
            framesEnd < 0 -> -1
            framesEnd >= framesAtStart -> framesEnd - framesAtStart
            else -> framesEnd
        }
        val rate = probe.lastRate
        Log.i(TAG, "RUN $run/$RUNS: spoke ${"%.1f".format(speakingMs / 1000.0)} s, " +
            "${turn.audioBytes} bytes received, $framesPlayed frames played @ $rate Hz")
        Log.i(TAG, "RUN $run/$RUNS: AEC-processed capture during playback: $levels")
        Log.i(TAG, "RUN $run/$RUNS: canceller converged=${detector.isConverged}, " +
            "barge-ins=${if (loop.lastBargeIn.value == null) 0 else 1}, " +
            "wakes=${wake.echoDetections.get()}, partials=${partials.size}")
        assertTrue(
            "run $run: playback lasted only ${speakingMs / 1000} s - REQ-05 asks for 60 s of it",
            speakingMs >= MIN_PLAYBACK_MS,
        )
        assertTrue(
            "run $run: the device consumed only $framesPlayed frames at $rate Hz - " +
                "less than 60 s of audio actually left the speaker",
            rate > 0 && framesPlayed >= MIN_PLAYBACK_MS / 1000 * rate,
        )
        assertNull("run $run: barge-in triggered: ${loop.lastBargeIn.value}", loop.lastBargeIn.value)
        assertEquals("run $run: wake-word triggered on the echo", 0, wake.echoDetections.get())
        assertEquals("run $run: echo partials: $partials", 0, partials.size)
        assertEquals("run $run: a run was stopped", 0, agent.stops.get())
        assertEquals("run $run: a turn was superseded and redispatched", run, agent.dispatches.get())

        // Let the echo tail die before the next turn - and prove it dies quietly: a wake or a
        // barge-in trigger arriving after the turn ended is still the console hearing itself.
        delay(SETTLE_MS)
        assertEquals(
            "run $run: the console left READY after its turn ended, with nobody speaking",
            ConsoleState.READY,
            loop.state.value,
        )
        partials.size
    }

    /** The transcript as (turn, segment) -> text, so any later non-blank change stands out. */
    private fun snapshot(transcript: Transcript): Map<String, String> =
        transcript.segments.associate { "turn ${it.turnId} segment ${it.index}" to it.text }

    private fun echoText(transcript: Transcript, baseline: Map<String, String>): List<String> =
        transcript.segments
            .filter { it.text.isNotBlank() && baseline["turn ${it.turnId} segment ${it.index}"] != it.text }
            .map { "turn ${it.turnId} segment ${it.index}: \"${it.text}\"" }

    /**
     * Play the T-04 fixture out of the speakers and listen on the UNPROCESSED source, where the
     * tablet's own voice must be plainly loud. If it is not, the speakers never spoke or the
     * microphone never listened, and every zero this test reports would be an artefact of a dead
     * rig rather than evidence about the echo canceller.
     */
    private suspend fun proveTheEchoPathIsLive() = coroutineScope {
        val pcm = fixture(CONTROL_FILE)
        val slice = pcm.copyOfRange(0, minOf(pcm.size, (CONTROL_MS / 1000).toInt() * CONTROL_RATE * 2))
        val record = openUnprocessedCapture()
        var peak = SILENCE_DBFS

        val playback = launch(Dispatchers.IO) {
            TtsPlayer(AudioTrackSinkFactory(audioManager, AudioConfig())).use { player ->
                player.onAudioStart(CONTROL_RATE)
                var offset = 0
                while (offset < slice.size && isActive) {
                    val end = minOf(offset + CONTROL_CHUNK, slice.size)
                    player.onChunk(slice.copyOfRange(offset, end))
                    offset = end
                }
                player.onAudioEnd()
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
        playback.cancelAndJoin()
        record.stop()
        record.release()

        Log.i(TAG, "POSITIVE CONTROL: unprocessed mic peak ${"%.1f".format(peak)} dBFS while the tablet spoke")
        assertTrue(
            "The unprocessed microphone heard only ${"%.1f".format(peak)} dBFS while the tablet " +
                "was speaking - the echo path does not exist, so the zeros below would prove " +
                "nothing. Check the speakers, the volume, and the output route.",
            peak > SpeechGate.DEFAULT_THRESHOLD_DBFS,
        )
    }

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

    /** The scripted speech and control fixtures. Synthesised, not committed - see scripts/. */
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
        const val TAG = "NeverSelfBarge"

        /**
         * The turn-opener: one clause, one breath, no commas (scripts/make-bargein-speech.py).
         * T-14's question was tried first and its comma pauses invited an early endpoint - the
         * reply started while the fixture was still speaking, and the console rightly barged in.
         */
        const val QUESTION_FILE = "t15-question.pcm"

        /** The T-04 fixture, reused for the positive control. 24 kHz mono PCM16. */
        const val CONTROL_FILE = "tts60.pcm"
        const val CONTROL_RATE = 24_000
        const val CONTROL_MS = 8_000L
        const val CONTROL_CHUNK = 4_800 // 100 ms at 24 kHz

        const val RUNS = 3

        /** REQ-05's floor. The scripted reply speaks for ~90 s, comfortably past it. */
        const val MIN_PLAYBACK_MS = 60_000L

        /** STT has to hear the question out and decide the speaker has finished. */
        const val QUESTION_TIMEOUT_MS = 30_000L

        /** The scripted agent answers instantly; only synthesis sits between it and the speaker. */
        const val SPEAK_START_TIMEOUT_MS = 30_000L

        /** ~90 s of reply, plus the trailing-sentence flush and slack. */
        const val PLAYBACK_TIMEOUT_MS = 180_000L

        /** Between runs: long enough for the echo tail to prove it wakes nothing. */
        const val SETTLE_MS = 2_000L

        /** After a self-barge: how long to wait for STT's verdict on what "interrupted". */
        const val INTERRUPTION_EVIDENCE_MS = 12_000L

        /**
         * A barge-in this close behind the last scripted frame is the fixture's own voice: the
         * gate spends ~100 ms confirming speech, and the queue may drain while it does.
         */
        const val ARTIFACT_MARGIN_MS = 1_000L

        const val DELTA_PACING_MS = 100L

        /**
         * ~1190 characters ~= 90 s of speech at the rate the T-04 fixture measured. Contains
         * neither "hey" nor "computer": a TTS voice reading the wake phrase out loud is a
         * legitimate detection, not an echo artefact, and it is not what REQ-05 measures.
         */
        val REPLY = listOf(
            "All decks report ready, and every diagnostic on the board reads green from the bridge down to the shuttle bay.",
            "The long range array is sweeping its assigned sector, and the navigational deflector is holding a steady field against the dust of the outer belt.",
            "Auxiliary power is routed through the secondary grid while the engineering team recalibrates the plasma manifolds along the port nacelle.",
            "Life support is nominal on every level, the arboretum sits at standard humidity, and the water reclamation loop is running a fraction ahead of projection.",
            "The science section has finished cataloguing the survey data from the last three systems and is preparing a summary for the morning briefing.",
            "Cargo transfer to the forward hold is complete, the manifests are reconciled, and the quartermaster has signed off on the remaining inventory.",
            "Internal sensors show calm corridors on every deck, and the night watch has nothing unusual to report from any station.",
            "Long range communications remain clear, subspace traffic is light, and the next scheduled relay window opens in a little under four hours.",
            "Taken together, the ship is quiet, the crew is rested, and the mission is proceeding exactly as planned.",
        )
    }
}
