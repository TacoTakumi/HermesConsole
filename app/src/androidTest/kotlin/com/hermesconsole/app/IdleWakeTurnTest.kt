package com.hermesconsole.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioManager
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.hermesconsole.audio.AudioCapture
import com.hermesconsole.audio.AudioConfig
import com.hermesconsole.audio.AudioTrackSinkFactory
import com.hermesconsole.audio.MicPipeline
import com.hermesconsole.audio.TtsPlayer
import com.hermesconsole.hermesclient.HermesClient
import com.hermesconsole.hermesclient.HermesConfig
import com.hermesconsole.voiceclient.VoiceLinkState
import com.hermesconsole.voiceclient.VoiceManagerClient
import com.hermesconsole.voiceclient.VoiceManagerConfig
import com.hermesconsole.wakeword.WakeWordDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * THE T-21 LIVE CHECK (REQ-22), on the SM-X210 against the real Hermes and the real VoiceManager:
 * the console goes dark when nobody needs it, and both ways back - a touch and a wake word - light
 * it inside a second, with the wake word's turn running to a spoken reply.
 *
 * The screensaver's clock and its presentation-only contract are the JVM suite's
 * ([IdleControllerTest]); the 300 s default timeout going dark on the wall and a touch restoring
 * the real MainActivity in 65 ms were measured on-device when the code shipped. What stayed
 * blocked until T-22's live loop existed and a VoiceManager was up is this file: the restore
 * driven by the SHIPPING keyword spotter hearing the wake phrase on the live capture path, and
 * the acceptance's third clause - a wake word from the idle state still completes a full voice
 * turn.
 *
 * The rig is the suite's established live pattern. The capture is the real microphone with the
 * wake fixture spliced into its stream one frame at a time (the T-14 discipline - the phrase
 * cannot arrive through the air because the tablet's own speaker is echo-cancelled out of its
 * own capture, REQ-06); the spotter, both backends, the speaker and the shell are the shipping
 * code. The shell is composed exactly as MainActivity composes it - the [IdleController] on the
 * loop's own state flow, the restore latency stamped by the frame that brings the shell back -
 * with only the timeout shortened, through the same constructor seam the JVM tests use: waiting
 * out the shipping 300 s twice would put five idle minutes, not evidence, in every run.
 *
 * The touch is real input (`input tap` through the system's input pipeline into the shell's own
 * press observer), and the screenshots are checked in the pixels: the dark frame must actually be
 * black - a screensaver, not a dimmed dashboard (REQ-22) - and the restored frame must not be.
 * Shots land in `/sdcard/Android/data/com.hermesconsole/files/t21/`.
 *
 * Skipped unless both backend keys are available (REQ-28); they come from the console's settings
 * store (T-41). The fixtures are build inputs:
 *
 *     python3 scripts/make-screenoff-fixture.py
 *     scripts/connected-test.sh IdleWakeTurnTest
 */
@RunWith(AndroidJUnit4::class)
class IdleWakeTurnTest {

    @get:Rule
    val micPermission: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.RECORD_AUDIO)

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private val audioManager: AudioManager
        get() = instrumentation.targetContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** The production capture path with the script spliced into it (the [ScreenOffTurnTest] rig). */
    private class SplicedCapture(private val mic: MicPipeline) : AudioCapture {
        override val config: AudioConfig get() = mic.config

        private val scripted = ConcurrentLinkedQueue<ByteArray>()

        /** Frames delivered downstream since start - a stream that stops flowing is a dead mic. */
        val framesDelivered = java.util.concurrent.atomic.AtomicInteger(0)

        /** Has every scripted frame been spliced into the stream? */
        val drained: Boolean get() = scripted.isEmpty()

        /** Say [pcm] into the microphone: 16 kHz mono PCM16, spliced one 20 ms frame at a time. */
        fun inject(pcm: ByteArray) {
            var offset = 0
            while (offset < pcm.size) {
                val end = minOf(offset + config.chunkBytes, pcm.size)
                scripted += pcm.copyOfRange(offset, end)
                offset = end
            }
        }

        override fun frames(): Flow<ByteArray> = mic.frames().map { live ->
            framesDelivered.incrementAndGet()
            scripted.poll() ?: live
        }

        override fun close() = mic.close()
    }

    /** One restore, as the frame that rendered it saw it. */
    private data class Restore(val controllerLatencyMs: Long, val frameAtMillis: Long)

    @Test
    fun the_dark_console_restores_under_a_second_by_touch_and_by_wake_word(): Unit = runBlocking {
        val backends = TestBackends.resolve()
        assumeTrue(
            TestBackends.NEEDS_BOTH,
            backends.vmKey.isNotBlank() && backends.hermesKey.isNotBlank(),
        )
        val wakePhrase = fixture(WAKE_FILE)
        val question = fixture(QUESTION_FILE)

        val voice = VoiceManagerClient(
            VoiceManagerConfig(host = backends.vmHost, port = backends.vmPort, apiKey = backends.vmKey),
        )
        val hermes = HermesClient(
            HermesConfig(host = backends.hermesHost, port = backends.hermesPort, apiKey = backends.hermesKey),
        )

        voice.connect()
        withTimeout(15_000) { voice.state.first { it is VoiceLinkState.Connected } }

        val context = instrumentation.targetContext
        val mic = MicPipeline(audioManager, AudioConfig())
        val capture = SplicedCapture(mic)
        val loop = VoiceLoop(
            capture = capture,
            wake = WakeWordDetector(context),
            voice = voice.asVoiceLink(),
            agent = hermes.asAgent(),
            // The real speaker: the reply is audible in the room, exactly as the wall speaks it.
            player = TtsPlayer(AudioTrackSinkFactory(audioManager, AudioConfig())),
            sessionId = hermes.visitSession(),
        )

        // MainActivity's wiring, on MainActivity's dispatcher: the loop's state is the restore
        // trigger, and nothing about the screensaver is ever told to the loop.
        val idleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val idleController = IdleController(
            state = loop.state,
            scope = idleScope,
            sessionTimeoutMillis = { IDLE_TIMEOUT_MS },
            screensaverTimeoutMillis = { IDLE_TIMEOUT_MS },
        ).also { it.start() }

        // Every restore, stamped by the frame that rendered it (the LaunchedEffect below).
        val restores = Channel<Restore>(Channel.UNLIMITED)

        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra("debug", true) // no microphone console behind the test's back (T-41)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            // Let the debug launch's own (async) content land first, so ours replaces it.
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
                // A dark display runs no frames (rig truth): hold the screen for the whole run -
                // the screensaver under test is the shell's, not the OS's (that one is T-47's).
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity.setContent {
                    val state by loop.state.collectAsState()
                    val turn by loop.turn.collectAsState()
                    val idle by idleController.idle.collectAsState()
                    // MainActivity's restore stamp, verbatim: the backlight goes with the black
                    // pixels, and the latency is logged from the frame the person actually sees.
                    LaunchedEffect(idle) {
                        activity.window.attributes = activity.window.attributes.also {
                            it.screenBrightness =
                                if (idle) IDLE_BRIGHTNESS
                                else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                        }
                        if (!idle) {
                            idleController.restored()?.let { latency ->
                                check(restores.trySend(Restore(latency, System.currentTimeMillis())).isSuccess)
                            }
                        }
                    }
                    ConsoleShell(
                        state = state,
                        turn = turn,
                        idle = idle,
                        onInteraction = idleController::touched,
                    )
                }
            }

            try {
                Log.i(TAG, mic.start().describe())
                loop.start()

                // DARK. The countdown ran out with the loop READY and untouched; the frame on the
                // panel must be the screensaver's black, not a dimmed dashboard (REQ-22).
                withTimeout(IDLE_TIMEOUT_MS + DARK_GRACE_MS) { idleController.idle.first { it } }
                instrumentation.waitForIdleSync()
                Thread.sleep(SETTLE_MS)
                val dark = screenshot("1-idle-dark")
                assertEquals(
                    "the idle frame carries lit pixels - a screensaver, not a dimmed dashboard, " +
                        "is the acceptance",
                    0,
                    litSamples(dark),
                )
                assertEquals(
                    "the loop must never learn the screen went dark",
                    ConsoleState.READY,
                    loop.state.value,
                )

                // RESTORE BY TOUCH: real input, through the system's pipeline into the shell's
                // own press observer, timed to the frame that brings the console back.
                tap(TAP_X.toFloat(), TAP_Y.toFloat())
                val byTouch = withTimeout(RESTORE_TIMEOUT_MS) { restores.receive() }
                Log.i(TAG, "touch restore: ${byTouch.controllerLatencyMs} ms to the restored frame")
                assertTrue(
                    "a touch must restore the ready state within 1 s, took ${byTouch.controllerLatencyMs} ms",
                    byTouch.controllerLatencyMs < 1_000,
                )
                assertEquals(ConsoleState.READY, loop.state.value)

                // DARK AGAIN: the touch bought one timeout, and the console spent it untouched.
                withTimeout(IDLE_TIMEOUT_MS + DARK_GRACE_MS) { idleController.idle.first { it } }
                Log.i(TAG, "dark again; speaking the wake phrase into the live stream")

                // RESTORE BY WAKE WORD: the phrase through the SHIPPING spotter on the live
                // capture path. By the time anything can see it, the wake is the state leaving
                // READY - and that transition must both light the shell and open the turn.
                // Detection on the live stream is a rate, not a certainty (T-34 measured the
                // rate; this rig observed misses on this very fixture), so the rig does what the
                // person in the room does with an unanswered call: says it again. The restore
                // clauses are measured from the attempt the spotter actually heard. Each splice
                // must drain at the recorder's own pace - a queue that sits full is a dead mic,
                // and the failure should say that, not "no detection".
                var wakeAttempts = 0
                while (loop.state.value == ConsoleState.READY) {
                    assertTrue(
                        "the spotter missed the wake phrase $wakeAttempts times in a row - " +
                            "that is a detection-rate problem, not an idle-restore one",
                        wakeAttempts < WAKE_ATTEMPTS,
                    )
                    wakeAttempts++
                    val framesBefore = capture.framesDelivered.get()
                    capture.inject(wakePhrase)
                    withTimeout(SPLICE_TIMEOUT_MS) { while (!capture.drained) delay(50) }
                    Log.i(
                        TAG,
                        "wake attempt $wakeAttempts spliced " +
                            "(${capture.framesDelivered.get() - framesBefore} frames this splice, " +
                            "${capture.framesDelivered.get()} total since start)",
                    )
                    withTimeoutOrNull(WAKE_DETECT_MS) {
                        loop.state.first { it != ConsoleState.READY }
                    }
                }
                Log.i(TAG, "wake detected on attempt $wakeAttempts")
                val byWake = withTimeout(WAKE_TIMEOUT_MS) { restores.receive() }
                withTimeout(WAKE_TIMEOUT_MS) { loop.state.first { it == ConsoleState.LISTENING } }
                val wakeAt = loop.turn.value.wakeAtMillis
                assertNotNull("the turn was not opened by the wake word", wakeAt)
                val wakeToFrame = byWake.frameAtMillis - wakeAt!!
                Log.i(
                    TAG,
                    "wake restore: ${byWake.controllerLatencyMs} ms from the state transition, " +
                        "$wakeToFrame ms from the spotter's own stamp to the restored frame",
                )
                assertTrue(
                    "a wake word must restore the ready state within 1 s, took $wakeToFrame ms " +
                        "from detection to the restored frame",
                    wakeToFrame < 1_000,
                )

                // THE TURN, begun in the dark, runs to a spoken reply (the acceptance's third
                // clause): question in on the open STT window, reply out the real speaker.
                capture.inject(question)
                withTimeout(TURN_TIMEOUT_MS) {
                    loop.state.first { it == ConsoleState.SPEAKING }
                    loop.state.first { it == ConsoleState.READY }
                }

                val turn = loop.turn.value
                Log.i(TAG, "=== T-21 WAKE-FROM-IDLE TURN ===")
                Log.i(TAG, "prompt:        ${turn.prompt.take(120)}")
                Log.i(TAG, "run:           ${turn.runId}")
                Log.i(TAG, "reply:         ${turn.reply.take(120)}")
                Log.i(TAG, "audio played:  ${turn.audioBytes} bytes")
                assertTrue("the STT heard nothing - no transcript was dispatched", turn.prompt.isNotBlank())
                assertNotNull("the run never completed", turn.runCompletedAtMillis)
                assertTrue("the reply was empty", turn.reply.isNotBlank())
                assertTrue("no audio ever reached the speaker", turn.audioBytes > 0)

                // The restored wall, with the turn it just ran on it - provably not black.
                instrumentation.waitForIdleSync()
                Thread.sleep(SETTLE_MS)
                val lit = screenshot("2-restored-turn")
                val litCount = litSamples(lit)
                Log.i(TAG, "restored frame: $litCount lit samples")
                assertTrue(
                    "the restored frame reads black ($litCount lit samples) - the shell never " +
                        "came back. (LCARS is mostly black by design: the measured lit shell " +
                        "carries thousands of lit samples at this stride, the screensaver zero.)",
                    litCount > LIT_SAMPLES_FLOOR,
                )
            } finally {
                idleScope.cancel()
                loop.close()
                voice.shutdown()
            }
        }
    }

    /**
     * How many of a dense grid of samples are lit (any channel over the noise floor)? LCARS is
     * mostly black by design - sparse points can land between the glyphs and the chrome - so the
     * frame is judged by a 10 px stride over the whole panel: ~23k samples, zero of them lit on
     * the screensaver, thousands on the live shell (measured).
     */
    private fun litSamples(shot: Bitmap): Int {
        var lit = 0
        for (x in 0 until shot.width step 10) {
            for (y in 0 until shot.height step 10) {
                val pixel = shot.getPixel(x, y)
                if (Color.red(pixel) >= 8 || Color.green(pixel) >= 8 || Color.blue(pixel) >= 8) lit++
            }
        }
        return lit
    }

    private fun screenshot(name: String): Bitmap {
        val shot = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull("screenshot $name came back null - is the screen on?", shot)
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "t21").apply { mkdirs() }
        val file = File(dir, "$name.png")
        FileOutputStream(file).use { shot!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "screenshot: ${file.absolutePath} (${shot.width}x${shot.height})")
        return shot
    }

    /**
     * A real tap through the system's input pipeline: injected down+up at the automation level -
     * the same plane a finger arrives on - and dispatched to the window by position, exactly as
     * the wall's glass delivers one. (The first cut shelled out to `input tap`, which never
     * delivered while the automation connection held the input plane.)
     */
    private fun tap(x: Float, y: Float) {
        val downAt = android.os.SystemClock.uptimeMillis()
        val down = android.view.MotionEvent.obtain(
            downAt, downAt, android.view.MotionEvent.ACTION_DOWN, x, y, 0,
        ).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
        val up = android.view.MotionEvent.obtain(
            downAt, android.os.SystemClock.uptimeMillis() + 50,
            android.view.MotionEvent.ACTION_UP, x, y, 0,
        ).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
        try {
            assertTrue("the tap's down half failed to inject", instrumentation.uiAutomation.injectInputEvent(down, true))
            assertTrue("the tap's up half failed to inject", instrumentation.uiAutomation.injectInputEvent(up, true))
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    private fun fixture(name: String): ByteArray = try {
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }
    } catch (e: FileNotFoundException) {
        throw AssertionError(
            "Missing the speech fixture app/src/androidTest/assets/$name. It is a build input, " +
                "not a source file:\n" +
                "    python3 scripts/make-screenoff-fixture.py   (wake phrase; needs no backend)\n" +
                "    VM_TOKEN=... python3 scripts/make-bargein-speech.py --only $QUESTION_FILE",
            e,
        )
    }

    private companion object {
        const val TAG = "T21IdleWake"

        /** The wake phrase at the capture stream's own level - the [ScreenOffTurnTest] fixture. */
        const val WAKE_FILE = "screenoff-wake.pcm"

        /** T-15's turn-opener: one clause, one breath - the endpoint lands only at its end. */
        const val QUESTION_FILE = "t15-question.pcm"

        /**
         * The test's idle timeout, through the same constructor seam the JVM tests inject
         * (IdleController.timeoutMillis). The shipping 300 s countdown reaching the screensaver
         * on the wall is already on the record from the task's first half; waiting it out twice
         * here would put ten idle minutes, not evidence, in every run.
         */
        const val IDLE_TIMEOUT_MS = 8_000L
        const val DARK_GRACE_MS = 7_000L

        /** MainActivity's idle backlight (REQ-22), applied to this rig's window the same way. */
        const val IDLE_BRIGHTNESS = 0.05f

        /** Landscape 1920x1200: the tap lands mid-panel, on the screensaver's black. */
        const val TAP_X = 960
        const val TAP_Y = 600

        const val RESTORE_TIMEOUT_MS = 10_000L

        /** The ~0.8 s phrase splices at the recorder's own 20 ms pace; 5 s means the mic is dead. */
        const val SPLICE_TIMEOUT_MS = 5_000L

        /** The lit shell measured 6114 lit samples at this stride; the screensaver measured 0. */
        const val LIT_SAMPLES_FLOOR = 500

        /** Detection ran ~0.5-0.7 s past phrase end on this device (T-34); 10 s is generous. */
        const val WAKE_TIMEOUT_MS = 10_000L

        /**
         * The rig repeats an unanswered call, as the room would; this many consecutive misses
         * stops being idle-restore flake and starts being a detection-rate finding.
         */
        const val WAKE_ATTEMPTS = 3

        /** How long one attempt is given before the phrase is repeated. */
        const val WAKE_DETECT_MS = 3_000L
        const val TURN_TIMEOUT_MS = 120_000L
        const val SETTLE_MS = 500L
    }
}
