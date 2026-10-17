package com.hermesconsole.app

import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hermesconsole.audio.AudioCapture
import com.hermesconsole.audio.AudioConfig
import com.hermesconsole.audio.AudioSink
import com.hermesconsole.audio.AudioSinkFactory
import com.hermesconsole.audio.TtsPlayer
import com.hermesconsole.hermesclient.ApprovalChoice
import com.hermesconsole.hermesclient.HermesClient
import com.hermesconsole.hermesclient.HermesConfig
import com.hermesconsole.hermesclient.HermesEvent
import com.hermesconsole.voiceclient.Transcript
import com.hermesconsole.voiceclient.TranscriptSegment
import com.hermesconsole.voiceclient.TtsEvent
import com.hermesconsole.wakeword.WakeWord
import com.hermesconsole.wakeword.WakeWordSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * THE T-27 LIVE CHECK, on the SM-X210 against the real Hermes gateway: a run stalls on a real
 * `approval.request` - the dangerous-command detector, tripped by a recursive delete of a scratch
 * /tmp path - the pane rises on the real [ConsoleShell] while the run stalls, and all three
 * endings of REQ-14 are exercised. Approve resolves at the gateway and the run completes with the
 * command executed; a spoken deny (the transcript path the wall console watches) posts the denial
 * and stops the run - it ends cancelled, the acceptance's "cancelled/refused" - and a request
 * nobody answers is auto-denied by the console at the 60 s deadline, after which the model
 * finishes the turn without the tool. No leg leaves a run stalled at the gateway.
 *
 * The rig is [ArtifactLiveTurnTest]'s: typed dispatch ([VoiceLoop.say]), an ack-only voice link,
 * the approval desk on the loop's event tap exactly as MainActivity wires it, and the shell
 * photographed at 1920x1200 into `/sdcard/Android/data/com.hermesconsole/files/t27/`. Touch and
 * voice enter through the same doors the shipping wiring uses - the pill's
 * [ApprovalController.resolve] and the transcript watcher's [ApprovalController.onTranscript] -
 * because the doors, not the fingers, are what the console ships.
 *
 * Skipped unless the gateway's key is available (REQ-28, T-41):
 *
 *     scripts/connected-test.sh ApprovalLiveTurnTest
 */
@RunWith(AndroidJUnit4::class)
class ApprovalLiveTurnTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private class NoCapture : AudioCapture {
        override val config = AudioConfig()
        override fun frames(): Flow<ByteArray> = emptyFlow()
        override fun close() = Unit
    }

    private class NoWake : WakeWordSource {
        override fun detections(frames: Flow<ByteArray>): Flow<WakeWord> = emptyFlow()
        override fun reset() = Unit
        override fun close() = Unit
    }

    private class SilentSink(override val sampleRateHz: Int) : AudioSink {
        override fun write(pcm: ByteArray) = Unit
        override fun stop() = Unit
        override fun close() = Unit
    }

    /** VoiceManager reduced to the two TTS acks a typed turn needs (the T-40 rig's convention). */
    private class AckVoice : VoiceLink {
        override val transcript: StateFlow<Transcript> = MutableStateFlow(Transcript())
        private val _tts = MutableSharedFlow<TtsEvent>(extraBufferCapacity = 64)
        override val tts: Flow<TtsEvent> = _tts
        private val started = mutableSetOf<String>()

        override suspend fun turnStart(): Int = 0
        override fun sendCapture(frame: ByteArray) = Unit
        override fun cancelTts(requestId: String) = Unit

        override fun sendTtsText(requestId: String, text: String, done: Boolean) {
            if (started.add(requestId)) check(_tts.tryEmit(TtsEvent.Start(requestId, 22_050)))
            if (done) check(_tts.tryEmit(TtsEvent.End(requestId)))
        }
    }

    @Test
    fun approve_deny_and_expiry_each_resolve_the_stalled_run_visibly() = runBlocking {
        val backends = TestBackends.resolve()
        assumeTrue(TestBackends.NEEDS_HERMES, backends.hermesKey.isNotBlank())
        val hermes = HermesClient(
            HermesConfig(
                host = backends.hermesHost,
                port = backends.hermesPort,
                apiKey = backends.hermesKey,
            ),
        )

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val approvals = ApprovalController(gateway = hermes.asApprovalGateway(), scope = scope)

        // The 1 s clause is measured across two threads, so the stamps must carry a real
        // happens-before: the tap stamps the request BEFORE the controller raises (an atomic,
        // written on the collector's thread), and the pane's rise is stamped on the test thread
        // the moment awaitPane returns - the display write that resumes it orders the two. The
        // first cut stamped the rise in the tap AFTER onEvent and lost the race twice on this
        // device: the resumed test thread outran the collector still inside the raise's Log call.
        val requestSeenAtMillis = AtomicLong(0)
        val loop = VoiceLoop(
            capture = NoCapture(),
            wake = NoWake(),
            voice = AckVoice(),
            agent = hermes.asAgent(),
            player = TtsPlayer(AudioSinkFactory { SilentSink(it) }),
            sessionId = hermes.visitSession(),
            eventTap = { event ->
                if (event is HermesEvent.ApprovalRequest) requestSeenAtMillis.set(System.currentTimeMillis())
                approvals.onEvent(event)
            },
        )

        val scroll = ScrollState(0)
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra("debug", true) // no microphone console behind the test's back (T-41)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity.setContent {
                    val state by loop.state.collectAsState()
                    val turn by loop.turn.collectAsState()
                    ConsoleShell(
                        state = state,
                        turn = turn,
                        approval = { ApprovalHost(approvals) },
                        contentScroll = scroll,
                    )
                }
            }

            try {
                loop.start()

                // Leg one, approve by touch: the pill's own entry point. The command is harmless
                // - a recursive delete of a scratch path - but the detector cannot know that,
                // which is the whole point: a real approval.request off a real run.
                sayDangerousThing(loop, "leg1")
                val pending = awaitPane(approvals)
                val paneUpAtMillis = System.currentTimeMillis()
                assertTrue(
                    "the pane rose while the run was stalled, not after it",
                    loop.state.value != ConsoleState.READY,
                )
                assertTrue("the request names the command", pending.request.command.contains("rm -rf"))
                assertTrue("the pane carries the wire's choices", "once" in pending.request.choices)
                assertTrue(
                    "the pane rose within 1 s of the event (took ${paneUpAtMillis - requestSeenAtMillis.get()} ms)",
                    paneUpAtMillis - requestSeenAtMillis.get() in 0..1_000,
                )
                settleAndShoot(scroll, "1-approval-pending")

                approvals.resolve(ApprovalChoice.ONCE)
                awaitOutcome(approvals, ApprovalOutcome.APPROVED)
                awaitReady(loop)
                assertTrue("the approved run spoke its reply", loop.turn.value.reply.isNotBlank())
                settleAndShoot(scroll, "2-approved-run-completed")

                // Leg two, deny by voice: the transcript watcher's entry point, fed the utterance
                // the wall console's collector would feed it. The denial posts AND the run is
                // stopped: it must end as cancelled/refused, not carry on without the tool.
                sayDangerousThing(loop, "leg2")
                awaitPane(approvals)
                approvals.onTranscript(
                    Transcript(
                        listOf(TranscriptSegment(index = 0, text = "deny", isFinal = true, endpointed = true, turnId = 7)),
                    ),
                )
                awaitOutcome(approvals, ApprovalOutcome.DENIED)
                awaitReady(loop)
                assertNotNull(
                    "the denied run terminated - the stream saw its terminal event",
                    loop.turn.value.runCompletedAtMillis,
                )
                settleAndShoot(scroll, "3-denied-by-voice-run-stopped")

                // Leg three, nobody answers: the console must deny at the 60 s deadline rather
                // than leave the run stalled (REQ-14) - and say so on the wall while the model
                // finishes the turn without its tool.
                sayDangerousThing(loop, "leg3")
                awaitPane(approvals)
                withTimeout(ApprovalController.AUTO_DENY_MILLIS + 30_000) {
                    approvals.display.first {
                        (it as? ApprovalDisplay.Resolved)?.outcome == ApprovalOutcome.AUTO_DENIED
                    }
                }
                awaitReady(loop)
                assertNotNull(
                    "the expired run terminated on its own after the auto-deny",
                    loop.turn.value.runCompletedAtMillis,
                )
                settleAndShoot(scroll, "4-expired-auto-denied")
            } finally {
                loop.close()
                scope.cancel()
            }
        }
    }

    /** A prompt the dangerous-command detector must stall on: recursive delete of a scratch path. */
    private fun sayDangerousThing(loop: VoiceLoop, tag: String) {
        loop.say(
            "Use your terminal tool to run exactly this command: " +
                "rm -rf /tmp/hermesconsole-approval-$tag - do not substitute a safer command. " +
                "Then reply with one short sentence.",
        )
    }

    private suspend fun awaitPane(approvals: ApprovalController): ApprovalDisplay.Pending =
        withTimeout(PANE_TIMEOUT_MS) {
            approvals.display.first { it is ApprovalDisplay.Pending } as ApprovalDisplay.Pending
        }

    private suspend fun awaitOutcome(approvals: ApprovalController, outcome: ApprovalOutcome) {
        withTimeout(PANE_TIMEOUT_MS) {
            approvals.display.first { (it as? ApprovalDisplay.Resolved)?.outcome == outcome }
        }
    }

    /** The turn is over when the loop is READY again: the stalled run terminated, one way or another. */
    private suspend fun awaitReady(loop: VoiceLoop) {
        withTimeout(TURN_TIMEOUT_MS) { loop.state.first { it == ConsoleState.READY } }
    }

    private fun settleAndShoot(scroll: ScrollState, name: String) {
        instrumentation.waitForIdleSync()
        val deadline = System.currentTimeMillis() + LAYOUT_TIMEOUT_MS
        while (scroll.maxValue == Int.MAX_VALUE && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("the content column never laid out", scroll.maxValue != Int.MAX_VALUE)
        Thread.sleep(SETTLE_MS)
        val shot = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull("screenshot $name came back null - is the screen on?", shot)
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "t27").apply { mkdirs() }
        val file = File(dir, "$name.png")
        FileOutputStream(file).use { shot!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "screenshot: ${file.absolutePath} (${shot.width}x${shot.height})")
    }

    private companion object {
        const val TAG = "T27ApprovalLive"
        const val PANE_TIMEOUT_MS = 120_000L
        const val TURN_TIMEOUT_MS = 180_000L
        const val LAYOUT_TIMEOUT_MS = 15_000L
        const val SETTLE_MS = 500L
    }
}
