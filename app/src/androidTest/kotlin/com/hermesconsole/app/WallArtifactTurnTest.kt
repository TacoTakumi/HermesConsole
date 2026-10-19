package com.hermesconsole.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.hermesconsole.audio.AudioCapture
import com.hermesconsole.audio.AudioConfig
import com.hermesconsole.audio.AudioTrackOneShotSink
import com.hermesconsole.audio.AudioTrackSinkFactory
import com.hermesconsole.audio.MicPipeline
import com.hermesconsole.hermesclient.HermesConfig
import com.hermesconsole.lcars.LcarsCuePlayer
import com.hermesconsole.voiceclient.TtsEvent
import com.hermesconsole.voiceclient.VoiceLinkState
import com.hermesconsole.voiceclient.VoiceManagerClient
import com.hermesconsole.voiceclient.VoiceManagerConfig
import com.hermesconsole.wakeword.WakeWordDetector
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * T-49's on-device half: a SPOKEN turn on the SHIPPING wall assembly puts the artifact pane on
 * the wall. [ArtifactLiveTurnTest] proved the router against the live gateway - in a rig of its
 * own construction; this test drives [WallConsole] itself, the class the wall actually runs:
 * real microphone capture with the utterances spliced in (the ScreenOffTurnTest pattern - the
 * tablet cannot wake itself through its own speaker, that is the echo canceller working), the
 * shipping keyword spotter, live VoiceManager STT, the live Hermes gateway calling
 * render_artifact, and the pane coming out of the console's own [WallConsole.panes] onto the
 * real [ConsoleShell], photographed.
 *
 * The question fixture is synthesized at test start over the console's own VoiceManager link, so
 * the run needs no pre-built asset for it and no key ever crosses a command line (T-41, REQ-28);
 * the wake fixture stays the proven build input (scripts/make-screenoff-fixture.py) - its level
 * sits in the spotter's measured plateau, which a TTS default level does not (T-29's trap 1).
 *
 *     scripts/connected-test.sh WallArtifactTurnTest
 */
@RunWith(AndroidJUnit4::class)
class WallArtifactTurnTest {

    @get:Rule
    val micPermission: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.RECORD_AUDIO)

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private val audioManager: AudioManager
        get() = instrumentation.targetContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** The shipping capture path with the script spliced in (ScreenOffTurnTest's pattern). */
    private class SplicedCapture(private val mic: MicPipeline) : AudioCapture {
        override val config: AudioConfig get() = mic.config

        private val scripted = ConcurrentLinkedQueue<ByteArray>()

        /** Say [pcm] into the microphone: 16 kHz mono PCM16, spliced one frame at a time. */
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

    @Test
    fun a_spoken_turn_renders_the_artifact_pane_on_the_wall(): Unit = runBlocking {
        val backends = TestBackends.resolve()
        assumeTrue(
            TestBackends.NEEDS_BOTH,
            backends.vmKey.isNotBlank() && backends.hermesKey.isNotBlank(),
        )
        val voiceConfig =
            VoiceManagerConfig(host = backends.vmHost, port = backends.vmPort, apiKey = backends.vmKey)

        val wakePhrase = fixture(WAKE_FILE)
        val question = synthesize(voiceConfig, QUESTION)
        Log.i(TAG, "question fixture synthesized: ${question.size} B at ${AudioConfig().sampleRateHz} Hz")

        val mic = MicPipeline(audioManager, AudioConfig())
        val capture = SplicedCapture(mic)
        val sinks = AudioTrackSinkFactory(audioManager, AudioConfig())
        val cues = LcarsCuePlayer(AudioTrackOneShotSink(audioManager, AudioConfig()))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        // THE ASSEMBLY UNDER TEST: the wall's own class, its router in its tap (T-49) - only the
        // capture seam differs, and only by the splice riding it.
        val console = WallConsole(
            backends = DebugBackends(
                hermes = HermesConfig(
                    host = backends.hermesHost,
                    port = backends.hermesPort,
                    apiKey = backends.hermesKey,
                ),
                voice = voiceConfig,
            ),
            cues = cues::play,
            sinks = sinks,
            capture = capture,
            wake = WakeWordDetector(instrumentation.targetContext),
            // Zero, deliberately: the shipping default (10 s) would hold the mic open after the
            // reply, and any room noise in that window could start a turn that retires the pane
            // this test exists to photograph. The window is runtime config, not assembly.
            followUpWindowMillis = { 0L },
            bargeInEnabled = { false },
            scope = scope,
        )

        val scroll = ScrollState(0)
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra("debug", true) // no second console grabbing the mic behind the test's back (T-41)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                // The shell, wired exactly as MainActivity wires the live console: the loop's
                // state and turn, and the console's own panes - not a rig's.
                activity.setContent {
                    val state by console.loop.state.collectAsState()
                    val turn by console.loop.turn.collectAsState()
                    val panes by console.panes.collectAsState()
                    ConsoleShell(
                        state = state,
                        turn = turn,
                        panes = panes,
                        contentScroll = scroll,
                    )
                }
            }
            instrumentation.waitForIdleSync()

            try {
                // The AEC verdict on the record from the session the loop runs on (REQ-02),
                // exactly as the activity logs it before console.start().
                Log.i(TAG, mic.start().describe())
                console.start()
                withTimeout(CONNECT_TIMEOUT_MS) {
                    console.health.voice.first { it == BackendHealth.OK }
                }

                // The wake word, through the shipping spotter.
                capture.inject(wakePhrase)
                withTimeout(WAKE_TIMEOUT_MS) {
                    console.loop.state.first { it == ConsoleState.LISTENING }
                }
                Log.i(TAG, "wake word detected -> LISTENING")

                // The question rides the open window; live STT dispatches its endpoint.
                capture.inject(question)

                // THE ASSERTION: the pane is up while the turn is still running (REQ-15), out of
                // the wall assembly's own tap - the first pane any shipping assembly ever routed.
                val stateAtPane = withTimeout(PANE_TIMEOUT_MS) {
                    console.panes.first { it.isNotEmpty() }
                    console.loop.state.value
                }
                assertNotEquals(
                    "the pane went up while the turn was still running, not after it",
                    ConsoleState.READY,
                    stateAtPane,
                )

                withTimeout(TURN_TIMEOUT_MS) { console.loop.state.first { it == ConsoleState.READY } }
                val turn = console.loop.turn.value
                Log.i(TAG, "=== T-49 SPOKEN ARTIFACT TURN ===")
                Log.i(TAG, "prompt: ${turn.prompt.take(160)}")
                Log.i(TAG, "run:    ${turn.runId}")
                Log.i(TAG, "reply:  ${turn.reply.take(160)}")
                Log.i(TAG, "panes:  ${console.panes.value.map { it.title }}")
                assertNotNull("the turn was not opened by the wake word", turn.wakeAtMillis)
                assertTrue("the STT heard nothing - no transcript was dispatched", turn.prompt.isNotBlank())
                assertTrue("the reply was empty", turn.reply.isNotBlank())
                assertTrue(
                    "expected a markdown pane on the wall, got ${console.panes.value.map { it.title }}",
                    console.panes.value.any { it.title == "markdown" },
                )

                settleAndShoot(scroll, "wall-artifact-pane")
            } finally {
                console.close()
                cues.close()
                scope.cancel()
            }
        }
    }

    /**
     * Synthesize [text] into a capture-ready utterance over the console's own VoiceManager: the
     * make-bargein-speech.py pipeline, run where the key already lives. Resampled to the capture
     * rate, leading silence trimmed, normalized to the question fixtures' -20 dBFS RMS - STT
     * level, not spotter level: this utterance only ever meets the recognizer.
     */
    private suspend fun synthesize(config: VoiceManagerConfig, text: String): ByteArray {
        val vm = VoiceManagerClient(config)
        try {
            vm.connect()
            withTimeout(CONNECT_TIMEOUT_MS) { vm.state.first { it is VoiceLinkState.Connected } }
            val pcm = ByteArrayOutputStream()
            var rate = 22_050
            coroutineScope {
                val done = CompletableDeferred<Unit>()
                val collector = launch {
                    vm.tts.collect { event ->
                        when (event) {
                            is TtsEvent.Start -> rate = event.sampleRate
                            is TtsEvent.Audio -> {
                                pcm.write(event.pcm)
                                rate = event.sampleRate
                            }
                            is TtsEvent.End -> done.complete(Unit)
                        }
                    }
                }
                vm.sendTtsText("t49-fixture", text, done = true)
                withTimeout(SYNTH_TIMEOUT_MS) { done.await() }
                collector.cancel()
            }
            return captureReady(pcm.toByteArray(), rate)
        } finally {
            vm.shutdown()
        }
    }

    /** Resample to the capture rate, trim the engine's leading silence, normalize to -20 dBFS. */
    private fun captureReady(pcm: ByteArray, srcRate: Int): ByteArray {
        val config = AudioConfig()
        val src = ShortArray(pcm.size / 2) { i ->
            ((pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)).toShort()
        }
        assertTrue("the synthesized fixture is empty", src.isNotEmpty())

        // Linear resample: the fixture is speech, not a measurement signal.
        val length = (src.size.toLong() * config.sampleRateHz / srcRate).toInt()
        val resampled = DoubleArray(length) { i ->
            val t = i.toDouble() * srcRate / config.sampleRateHz
            val i0 = t.toInt().coerceAtMost(src.size - 1)
            val i1 = (i0 + 1).coerceAtMost(src.size - 1)
            src[i0] * (1 - (t - i0)) + src[i1] * (t - i0)
        }

        // Trim the leading silence: the fixture stands in for a person, and a person does not
        // spend the endpointer's patience on half a second of digital quiet.
        val samplesPerFrame = config.chunkBytes / 2
        var onset = 0
        while (onset + samplesPerFrame <= resampled.size &&
            frameRmsDbfs(resampled, onset, samplesPerFrame) <= SILENCE_FLOOR_DBFS
        ) {
            onset += samplesPerFrame
        }
        onset = maxOf(0, onset - samplesPerFrame) // one frame of pre-roll, so nothing is clipped

        // Normalize to the question fixtures' level.
        var sumSquares = 0.0
        for (i in onset until resampled.size) sumSquares += resampled[i] * resampled[i]
        val rms = sqrt(sumSquares / (resampled.size - onset)) / 32767.0
        val gain = Math.pow(10.0, TARGET_RMS_DBFS / 20.0) / maxOf(rms, 1e-9)

        val out = ByteArray((resampled.size - onset) * 2)
        for (i in onset until resampled.size) {
            val sample = (resampled[i] * gain).coerceIn(-32768.0, 32767.0).toInt()
            out[(i - onset) * 2] = (sample and 0xFF).toByte()
            out[(i - onset) * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun frameRmsDbfs(samples: DoubleArray, offset: Int, count: Int): Double {
        var sumSquares = 0.0
        for (i in offset until offset + count) sumSquares += samples[i] * samples[i]
        return 20 * log10(maxOf(sqrt(sumSquares / count) / 32767.0, 1e-9))
    }

    private fun settleAndShoot(scroll: ScrollState, name: String) {
        instrumentation.waitForIdleSync()
        val deadline = System.currentTimeMillis() + LAYOUT_TIMEOUT_MS
        while (scroll.maxValue == Int.MAX_VALUE && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("the content column never laid out", scroll.maxValue != Int.MAX_VALUE)
        Thread.sleep(SETTLE_MS)
        val shot = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull("screenshot $name came back null - is the screen on?", shot)
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "t49").apply { mkdirs() }
        val file = File(dir, "$name.png")
        FileOutputStream(file).use { shot!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "screenshot: ${file.absolutePath} (${shot.width}x${shot.height})")
    }

    private fun fixture(name: String): ByteArray = try {
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }
    } catch (e: FileNotFoundException) {
        throw AssertionError(
            "Missing the speech fixture app/src/androidTest/assets/$name. It is a build input, " +
                "not a source file:\n    python3 scripts/make-screenoff-fixture.py",
            e,
        )
    }

    private companion object {
        const val TAG = "T49WallArtifact"

        /** The wake phrase at spotter level (scripts/make-screenoff-fixture.py, T-29's plateau). */
        const val WAKE_FILE = "screenoff-wake.pcm"

        /**
         * One clause, one breath, no commas (the t15-question rule: a pause is an invitation for
         * the endpoint to fire early). Asks for markdown, the artifact kind that needs no URL.
         */
        const val QUESTION = "Use the render artifact tool to show a markdown status document " +
            "with a heading and a bullet list and then reply with one short sentence."

        /** The question fixtures' level (make-bargein-speech.py): a person at the wall mount. */
        const val TARGET_RMS_DBFS = -20.0

        /** Below this is the engine's leading silence, not the utterance. */
        const val SILENCE_FLOOR_DBFS = -60.0

        const val CONNECT_TIMEOUT_MS = 15_000L
        const val SYNTH_TIMEOUT_MS = 60_000L
        const val WAKE_TIMEOUT_MS = 10_000L
        const val PANE_TIMEOUT_MS = 120_000L
        const val TURN_TIMEOUT_MS = 120_000L
        const val LAYOUT_TIMEOUT_MS = 15_000L
        const val SETTLE_MS = 500L
    }
}
