package com.hermesconsole.app

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.PowerManager
import android.util.Log
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
import com.hermesconsole.audio.rmsDbfs
import com.hermesconsole.hermesclient.HermesClient
import com.hermesconsole.hermesclient.HermesConfig
import com.hermesconsole.voiceclient.VoiceLinkState
import com.hermesconsole.voiceclient.VoiceManagerClient
import com.hermesconsole.voiceclient.VoiceManagerConfig
import com.hermesconsole.wakeword.WakeWordDetector
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * THE REQ-26 MEASUREMENT (T-29, extended by T-57), on the SM-X210 against the real Hermes and the
 * real VoiceManager: a wake word spoken to a display One UI has already slept triggers a full turn
 * - STT, Hermes run, spoken reply - AND powers the panel back on, so the reply lands on a lit wall.
 *
 * The threat this measures is the operating system, not the room. Since Android 12 a process
 * whose activity has left the screen is handed digital silence instead of microphone audio -
 * every earlier rig in this suite carries the "mic is only live while the console is on screen"
 * caveat for exactly that reason - and a dozing SM-X210 suspends the CPU under the wake-word
 * engine unless something holds it. T-28's microphone foreground service plus T-29's partial
 * wake lock are the claim that neither happens to the console; this test is that claim run for
 * real, in the dark:
 *
 *   1. The anchor is started the compliant way (from a visible activity), the screen is then
 *      turned off by the power manager itself, and the test refuses to proceed until the display
 *      is provably non-interactive.
 *   2. The REAL microphone must then deliver live audio: frames with a room's noise floor in
 *      them, not the exact zeros of a silenced stream. If One UI has cut capture, this fails
 *      here and says so.
 *   3. The wake phrase is spliced into the live stream and must be found by the SHIPPING keyword
 *      spotter - the engine whose screen-off survival is the whole point. The splice is the T-14
 *      discipline: the recorder still paces the stream through the same AudioRecord and echo
 *      canceller the console ships; the fixture stands in for the room for exactly as long as it
 *      lasts. (It cannot arrive through the air: the tablet's own speaker is echo-cancelled out
 *      of the capture stream by design - REQ-06 - and this rig has no other mouth in the room.
 *      The phrase-through-the-air measurement is T-34's, screen on - and the same turn was also
 *      run by a person in the room against the dark shipping console: files/t29/.)
 *      The fixture is a synthesized voice at the stream's own level, and both halves of that are
 *      measured, not chosen (scripts/make-screenoff-fixture.py): the shipping spotter scores the
 *      range-session utterance cuts at ZERO even replayed offline - they are not shipping-path
 *      audio - while the synthesized phrase detects across a 20 dB plateau; and a fixture
 *      normalized to studio level saturates the detector's 6x makeup gain and also scores zero,
 *      which is exactly how the first cut of this rig failed.
 *   4. The wake word must then power the panel back on (T-57): the display was provably off when
 *      the phrase was spoken, and must read interactive once the wake lands. The screen-on is the
 *      activity's - setShowWhenLocked/setTurnScreenOn set on the host, and the console brought to
 *      the foreground on the wake so setTurnScreenOn fires. (An ACQUIRE_CAUSES_WAKEUP screen wake
 *      lock was measured NOT to bring this device out of doze - the activity path is what does.)
 *      MainActivity's console path owns this watcher off the loop's own state; the debug host this
 *      test launches (T-41) skips that path, so the rig carries the same wiring - that the real
 *      path holds it is a source fact, checked in review.
 *   5. The question rides the open STT window, and the whole turn - transcript, run, streamed
 *      reply out the real speaker - completes on the now-lit wall, verified interactive at the end.
 *
 * Skipped unless both backend keys are available (REQ-28). They come from the console's settings
 * store (T-41); the wake fixture is a build input:
 *
 *     python3 scripts/make-screenoff-fixture.py
 *     scripts/connected-test.sh ScreenOffTurnTest
 */
@RunWith(AndroidJUnit4::class)
class ScreenOffTurnTest {

    @get:Rule
    val micPermission: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.RECORD_AUDIO)

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private val audioManager: AudioManager
        get() = instrumentation.targetContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val powerManager: PowerManager
        get() = instrumentation.targetContext.getSystemService(Context.POWER_SERVICE) as PowerManager

    /**
     * The production capture path with the script spliced into it, plus the liveness question
     * T-29 hangs on: are the live frames REAL audio? A frame of digital silence has no noise
     * floor at all, and a silenced stream is nothing but them - so counting frames that carry
     * measurable signal is what separates "the room is quiet" from "the OS cut the microphone".
     */
    private class SplicedCapture(private val mic: MicPipeline) : AudioCapture {
        override val config: AudioConfig get() = mic.config

        private val scripted = ConcurrentLinkedQueue<ByteArray>()

        /** Live (unspliced) frames whose RMS clears the liveness floor since the last reset. */
        val liveFrames = AtomicInteger(0)

        fun resetLiveness() = liveFrames.set(0)

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
            val spoken = scripted.poll()
            if (spoken == null) {
                if (rmsDbfs(live) > LIVENESS_FLOOR_DBFS) liveFrames.incrementAndGet()
                live
            } else {
                spoken
            }
        }

        override fun close() = mic.close()

        private companion object {
            /**
             * VOICE_COMMUNICATION's noise suppressor gates room tone to ~-105 dBFS mean on this
             * device (AudioConfig) - but gated is not silenced: those frames still carry nonzero
             * samples, and even one LSB in a 20 ms frame measures above -150. The OS's silenced
             * stream is EXACT zeros, which rmsDbfs floors at -180. This threshold splits the two
             * regimes down the middle.
             */
            const val LIVENESS_FLOOR_DBFS = -150.0
        }
    }

    @Test
    fun a_wake_word_triggers_a_full_turn_with_the_display_off(): Unit = runBlocking {
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
            // The real speaker: the reply is audible in the dark room, and is meant to be.
            player = TtsPlayer(AudioTrackSinkFactory(audioManager, AudioConfig())),
            sessionId = hermes.visitSession(),
        )

        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra("debug", true) // no microphone console behind the test's back (T-41)
        lateinit var host: MainActivity
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            // The anchor, started exactly as the console starts it: from a visible activity,
            // with RECORD_AUDIO granted (the Android 14+ compliant path for a mic-typed FGS).
            // It is what licenses everything that follows the dark.
            scenario.onActivity { activity ->
                host = activity
                // T-57: the shipping screen-on is the activity's - setTurnScreenOn wakes the panel
                // as the console is brought to the foreground. The console path sets these in
                // onCreate; the debug host this test launches (T-41) skips that path, so the test
                // sets them here, exactly as the other live rigs do, and drives the same
                // bring-to-front on the wake edge below.
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
                activity.startForegroundService(Intent(activity, MicForegroundService::class.java))
            }
            instrumentation.waitForIdleSync()

            var screenWaker: Job? = null
            try {
                Log.i(TAG, capture.config.let { "capture ${it.sampleRateHz} Hz, ${it.chunkBytes} B frames" })
                // The same capture session the console ships, with its AEC verdict on the record
                // (REQ-02) - opened on screen, kept across the lights going out.
                Log.i(TAG, mic.start().describe())
                loop.start()
                // The shipping screen-on, wired off the loop's state exactly as MainActivity's
                // console path wires it (T-57): the loop leaving READY while the panel is asleep
                // brings the console to the foreground, and setTurnScreenOn (set on the host above)
                // wakes the panel. The debug host this test launches skips the console path, so the
                // watcher is carried here to drive the real bring-to-front on the real panel.
                screenWaker = launch {
                    var wasReady = true
                    loop.state.collect { s ->
                        val leftReady = wasReady && s != ConsoleState.READY
                        wasReady = s == ConsoleState.READY
                        if (leftReady && !powerManager.isInteractive) {
                            host.startActivity(
                                Intent(host, MainActivity::class.java)
                                    .putExtra("debug", true)
                                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                            )
                        }
                    }
                }
                // Let the capture session open and settle on screen before the lights go out.
                delay(1_500)

                // Lights out - and proven out, not assumed. KEYCODE_SLEEP is the power button's
                // sleep half: the display state that One UI actually manages the device by.
                shell("input keyevent KEYCODE_SLEEP")
                withTimeout(10_000) {
                    while (powerManager.isInteractive) delay(100)
                }
                Log.i(TAG, "display off (isInteractive=false)")

                // THE MICROPHONE IS ALIVE IN THE DARK, or nothing else here means anything: a
                // silenced stream is exact zeros forever, a live one carries the room's floor.
                capture.resetLiveness()
                withTimeout(LIVENESS_TIMEOUT_MS) {
                    while (capture.liveFrames.get() < LIVE_FRAMES_REQUIRED) delay(100)
                }
                Log.i(TAG, "capture live with display off: ${capture.liveFrames.get()} frames over the floor")

                // The wake phrase - a human voice through the shipping keyword spotter.
                capture.inject(wakePhrase)
                withTimeout(WAKE_TIMEOUT_MS) { loop.state.first { it == ConsoleState.LISTENING } }
                Log.i(TAG, "wake word detected with display off -> LISTENING")

                // T-57: the same wake word now powers the slept panel back on. Off before, on after.
                withTimeout(SCREEN_ON_TIMEOUT_MS) { while (!powerManager.isInteractive) delay(100) }
                Log.i(TAG, "display back on after the wake word (isInteractive=true)")

                // The question rides the open window; then the whole turn, still in the dark.
                capture.inject(question)
                withTimeout(TURN_TIMEOUT_MS) {
                    loop.state.first { it == ConsoleState.SPEAKING }
                    loop.state.first { it == ConsoleState.READY }
                }

                val turn = loop.turn.value
                Log.i(TAG, "=== REQ-26 TURN (display off) ===")
                Log.i(TAG, "prompt:        ${turn.prompt.take(120)}")
                Log.i(TAG, "run:           ${turn.runId}")
                Log.i(TAG, "reply:         ${turn.reply.take(120)}")
                Log.i(TAG, "run.completed: ${turn.runCompletedAtMillis}")
                Log.i(TAG, "audio played:  ${turn.audioBytes} bytes")

                assertNotNull("the turn was not opened by the wake word", turn.wakeAtMillis)
                assertTrue("the STT heard nothing - no transcript was dispatched", turn.prompt.isNotBlank())
                assertNotNull("the run never completed", turn.runCompletedAtMillis)
                assertTrue("the reply was empty", turn.reply.isNotBlank())
                assertTrue("no audio ever reached the speaker", turn.audioBytes > 0)
                assertTrue(
                    "the wake word never lit the wall - a turn woken in the dark must bring the " +
                        "panel back on (T-57), not just answer into it",
                    powerManager.isInteractive,
                )
            } finally {
                screenWaker?.cancel()
                loop.close()
                voice.shutdown()
                // Leave the wall lit for whoever looks at the rig next.
                shell("input keyevent KEYCODE_WAKEUP")
                shell("wm dismiss-keyguard")
            }
        }
    }

    /** Run a shell command via the instrumentation's automation, draining its output. */
    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).readBytes()
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
        const val TAG = "ScreenOffTurn"

        /** The wake phrase, at the capture stream's own level (scripts/make-screenoff-fixture.py). */
        const val WAKE_FILE = "screenoff-wake.pcm"

        /** T-15's turn-opener: one clause, one breath - the endpoint lands only at its end. */
        const val QUESTION_FILE = "t15-question.pcm"

        /** Half a second of provably-live room audio before the wake word is even attempted. */
        const val LIVE_FRAMES_REQUIRED = 25
        const val LIVENESS_TIMEOUT_MS = 15_000L

        /** Detection ran ~0.5-0.7 s past phrase end on this device (T-34); 10 s is generous. */
        const val WAKE_TIMEOUT_MS = 10_000L

        /** The panel should light within a beat of the wake landing; the lock wakes it synchronously. */
        const val SCREEN_ON_TIMEOUT_MS = 5_000L
        const val TURN_TIMEOUT_MS = 120_000L
    }
}
