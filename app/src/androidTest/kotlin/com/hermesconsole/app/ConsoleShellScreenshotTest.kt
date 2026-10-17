package com.hermesconsole.app

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.hermesconsole.app.artifacts.Artifact
import com.hermesconsole.app.artifacts.ImageApplet
import com.hermesconsole.app.artifacts.MarkdownApplet
import com.hermesconsole.audio.AudioCapture
import com.hermesconsole.audio.AudioConfig
import com.hermesconsole.audio.AudioSink
import com.hermesconsole.audio.AudioSinkFactory
import com.hermesconsole.audio.TtsPlayer
import com.hermesconsole.hermesclient.HermesEvent
import com.hermesconsole.voiceclient.Transcript
import com.hermesconsole.voiceclient.TranscriptSegment
import com.hermesconsole.voiceclient.TtsEvent
import com.hermesconsole.wakeword.WakeWord
import com.hermesconsole.wakeword.WakeWordSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * THE SHELL EVIDENCE (T-39; REQ-35, REQ-38): one scripted end-to-end turn through the real
 * [VoiceLoop], with the shell on the real landscape screen, photographed in every state it
 * passes through.
 *
 * Scripted means the *inputs* are scripted - the loop, the state machine and the shell are the
 * shipping code. The ports the loop was built around ([VoiceLink], [Agent], [WakeWordSource]) are
 * driven from the test instead of from a microphone and two servers, which is what makes the
 * sequence deterministic enough to photograph: the wake word lands when the test says so, the
 * transcript endpoints when the test says so, and the run speaks exactly the sentence the test
 * wrote for it.
 *
 * Screenshots land in the app's external files dir, pullable at
 * `/sdcard/Android/data/com.hermesconsole/files/t39/`.
 */
@RunWith(AndroidJUnit4::class)
class ConsoleShellScreenshotTest {

    @get:Rule
    val micPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /** No microphone: the wake word and the transcript are the script's to fire. */
    private class NoCapture : AudioCapture {
        override val config = AudioConfig()
        override fun frames(): Flow<ByteArray> = emptyFlow()
        override fun close() = Unit
    }

    /** A wake word that lands on cue. */
    private class ScriptedWake : WakeWordSource {
        private val signals = MutableSharedFlow<WakeWord>(extraBufferCapacity = 4)
        fun fire() = check(signals.tryEmit(WakeWord("hey computer", System.currentTimeMillis())))
        override fun detections(frames: Flow<ByteArray>): Flow<WakeWord> =
            merge(frames.transform { }, signals)
        override fun reset() = Unit
        override fun close() = Unit
    }

    /** VoiceManager, as the script plays it: the transcript and the TTS stream are inputs here. */
    private class ScriptedVoice : VoiceLink {
        private val _transcript = MutableStateFlow(Transcript())
        override val transcript: StateFlow<Transcript> = _transcript
        private val _tts = MutableSharedFlow<TtsEvent>(extraBufferCapacity = 64)
        override val tts: Flow<TtsEvent> = _tts
        val spokenText = StringBuilder()
        private var turns = 0

        override suspend fun turnStart(): Int = ++turns
        override fun sendCapture(frame: ByteArray) = Unit
        override fun sendTtsText(requestId: String, text: String, done: Boolean) {
            spokenText.append(text)
        }
        override fun cancelTts(requestId: String) = Unit

        fun hear(turnId: Int, text: String) {
            _transcript.value = Transcript(
                listOf(TranscriptSegment(index = 0, text = text, isFinal = true, endpointed = true, turnId = turnId)),
            )
        }

        fun tts(event: TtsEvent) = check(_tts.tryEmit(event))
    }

    /** Hermes, as the script plays it: the reply arrives delta by delta, on cue. */
    private class ScriptedAgent : Agent {
        private val events = MutableSharedFlow<HermesEvent>(extraBufferCapacity = 64)
        override suspend fun dispatch(input: String, sessionId: String): String = RUN_ID
        override fun events(runId: String): Flow<HermesEvent> =
            events.transformWhile { emit(it); !it.isTerminal }
        override suspend fun stop(runId: String) = Unit
        fun emit(event: HermesEvent) = check(events.tryEmit(event))
    }

    /** A speaker that swallows PCM: this test photographs the screen, it does not make sound. */
    private class SilentSink(override val sampleRateHz: Int) : AudioSink {
        override fun write(pcm: ByteArray) = Unit
        override fun stop() = Unit
        override fun close() = Unit
    }

    @Test
    fun a_scripted_turn_is_photographed_in_all_four_states_and_the_artifact_stack() {
        val wake = ScriptedWake()
        val voice = ScriptedVoice()
        val agent = ScriptedAgent()
        val loop = VoiceLoop(
            capture = NoCapture(),
            wake = wake,
            voice = voice,
            agent = agent,
            player = TtsPlayer(AudioSinkFactory { SilentSink(it) }),
            sessionId = { "session-screenshot" },
            newRequestId = { TTS_ID },
        )

        // Every state the loop passes through, in order, for the sequence assertion at the end.
        val observed = mutableListOf(loop.state.value)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
                // A dark display runs no frames: nothing lays out, and every screenshot is black.
                // Hold the screen for the whole run; waking it is the runner's job (rig truth:
                // wake the device right before `am instrument`, not before the gradle build).
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity.setContent {
                    val state by loop.state.collectAsState()
                    val turn by loop.turn.collectAsState()
                    ConsoleShell(state = state, turn = turn)
                }
            }

            loop.start()
            val watcher = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
                loop.state.collect { if (observed.last() != it) observed.add(it) }
            }

            try {
                // READY: active, unengaged.
                shoot(loop, ConsoleState.READY, "1-ready")

                // Wake word -> LISTENING.
                wake.fire()
                shoot(loop, ConsoleState.LISTENING, "2-listening")

                // The transcript endpoints -> the turn dispatches -> THINKING.
                voice.hear(turnId = 1, text = PROMPT)
                shoot(loop, ConsoleState.THINKING, "3-thinking")

                // The reply streams, and its audio starts -> SPEAKING.
                REPLY.split(" ").forEach { agent.emit(HermesEvent.MessageDelta(RUN_ID, "$it ")) }
                voice.tts(TtsEvent.Start(TTS_ID, 24_000))
                voice.tts(TtsEvent.Audio(ByteArray(4_800), 24_000))
                shoot(loop, ConsoleState.SPEAKING, "4-speaking")

                // The run completes and the audio drains -> READY again: the sequence is closed.
                agent.emit(HermesEvent.RunCompleted(RUN_ID))
                voice.tts(TtsEvent.End(TTS_ID))
                shoot(loop, ConsoleState.READY, "5-ready-again")

                assertEquals(
                    "the scripted turn must pass through all four states in sequence",
                    listOf(
                        ConsoleState.READY,
                        ConsoleState.LISTENING,
                        ConsoleState.THINKING,
                        ConsoleState.SPEAKING,
                        ConsoleState.READY,
                    ),
                    observed,
                )
                assertTrue("the reply was never sent to TTS", voice.spokenText.isNotBlank())
            } finally {
                watcher.cancel()
            }

            // The two-artifact turn (REQ-20): an image pane and a long markdown pane, stacked
            // vertically in the shell's content column, photographed at the top and at the bottom
            // of the scroll - the whole document is reachable, none of it clipped sideways.
            // The image rides the shipping applet (T-25) exactly as a routed artifact would: a
            // 16:9 test card written to disk and fetched back through the payload's `url`, so
            // shot 6 photographs ImageApplet's own fetch, decode and aspect-pinned layout.
            val testCard = File(instrumentation.targetContext.filesDir, "test-card.png")
            FileOutputStream(testCard).use { testPattern().compress(Bitmap.CompressFormat.PNG, 100, it) }
            val composedBefore = ImageApplet.composed.value
            val scroll = ScrollState(0)
            val turn = loop.turn.value
            scenario.onActivity { activity ->
                activity.setContent {
                    ConsoleShell(
                        state = ConsoleState.READY,
                        turn = turn,
                        panes = listOf(
                            ConsolePane("image artifact") {
                                ImageApplet.Render(
                                    Artifact(
                                        runId = RUN_ID,
                                        type = "image",
                                        args = buildJsonObject {
                                            put("kind", "image")
                                            put("url", "file://${testCard.absolutePath}")
                                        },
                                    ),
                                )
                            },
                            ConsolePane("markdown artifact") {
                                // The shipping applet renders the payload exactly as a routed
                                // artifact would arrive (T-26): shot 7 photographs the document's
                                // tail, where all four element kinds sit formatted.
                                MarkdownApplet.Render(
                                    Artifact(
                                        runId = RUN_ID,
                                        type = "markdown",
                                        args = buildJsonObject {
                                            put("kind", "markdown")
                                            put("markdown", longMarkdown())
                                        },
                                    ),
                                )
                            },
                        ),
                        contentScroll = scroll,
                    )
                }
            }
            // The artifact stack is a real document now (T-26) - wait for its layout, not a guess:
            // ScrollState reports Int.MAX_VALUE until the content column has actually measured.
            instrumentation.waitForIdleSync()
            val deadline = System.currentTimeMillis() + STATE_TIMEOUT_MS
            while (scroll.maxValue == Int.MAX_VALUE && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertTrue("the artifact stack never laid out", scroll.maxValue != Int.MAX_VALUE)
            // And the image pane is an async fetch (T-25): wait until the applet has composed the
            // bitmap itself, so shot 6 shows the test card and not the RETRIEVING readout.
            runBlocking {
                withTimeout(STATE_TIMEOUT_MS) { ImageApplet.composed.first { it > composedBefore } }
            }
            instrumentation.waitForIdleSync()
            Thread.sleep(SETTLE_MS)
            screenshot("6-artifacts-top")

            runBlocking { withContext(Dispatchers.Main) { scroll.scrollTo(scroll.maxValue) } }
            instrumentation.waitForIdleSync()
            Thread.sleep(SETTLE_MS)
            screenshot("7-artifacts-bottom")

            assertTrue(
                "the markdown artifact must be long enough to scroll - otherwise this proves nothing",
                scroll.maxValue > 0,
            )
            assertEquals("the bottom of the document must be reachable", scroll.maxValue, scroll.value)
        }

        loop.close()
    }

    /**
     * The long markdown artifact, still long enough to force a scroll (T-39) - and now carrying
     * REQ-16's four element kinds in its tail section, so the bottom screenshot shows headings,
     * a list, a code block and a table rendered as structure rather than raw text (T-26).
     */
    private fun longMarkdown(): String = buildString {
        appendLine("# Warp core diagnostic")
        appendLine()
        repeat(12) { section ->
            appendLine("## Section ${section + 1}")
            appendLine()
            appendLine(
                "The plasma injector assembly reports **nominal** flow across all manifolds, " +
                    "with dilithium matrix alignment holding at ninety-nine point seven percent " +
                    "efficiency through the last duty cycle.",
            )
            appendLine()
            appendLine("- intermix ratio stable")
            appendLine("- coolant loop pressure within tolerance")
            appendLine("- no microfractures detected")
            appendLine()
        }
        appendLine("## Injector summary")
        appendLine()
        appendLine("| Manifold | Flow | Status |")
        appendLine("| --- | --- | --- |")
        appendLine("| Port | 99.7% | nominal |")
        appendLine("| Starboard | 99.2% | nominal |")
        appendLine()
        appendLine("1. seal the injectors")
        appendLine("2. recheck the dilithium matrix")
        appendLine()
        appendLine("> Engineering signs off on the duty cycle.")
        appendLine()
        appendLine("```kotlin")
        appendLine("core.status = STABLE")
        appendLine("```")
        appendLine()
        appendLine("END OF DOCUMENT")
    }

    /** An LCARS-coloured 16:9 test card - written to disk for the shipping applet to fetch back. */
    private fun testPattern(): Bitmap {
        val bmp = Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint()
        val bands = listOf(0xFFFF9900, 0xFFCC99CC, 0xFF99CCFF, 0xFFFFCC00, 0xFFCC6666, 0xFF6688CC)
        val bandWidth = bmp.width / bands.size.toFloat()
        bands.forEachIndexed { i, colour ->
            paint.color = colour.toInt()
            canvas.drawRect(i * bandWidth, 0f, (i + 1) * bandWidth, bmp.height.toFloat(), paint)
        }
        return bmp
    }

    /** Wait for the loop to reach [state], let the frame land, photograph it. */
    private fun shoot(loop: VoiceLoop, state: ConsoleState, name: String) {
        runBlocking { withTimeout(STATE_TIMEOUT_MS) { loop.state.first { it == state } } }
        instrumentation.waitForIdleSync()
        Thread.sleep(SETTLE_MS)
        screenshot(name)
    }

    private fun screenshot(name: String) {
        val shot = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull("screenshot $name came back null - is the screen on?", shot)
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "t39").apply { mkdirs() }
        val file = File(dir, "$name.png")
        FileOutputStream(file).use { shot!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "screenshot: ${file.absolutePath} (${shot.width}x${shot.height})")
    }

    private companion object {
        const val TAG = "T19Screenshot"
        const val RUN_ID = "run-t39"
        const val TTS_ID = "tts-t39"
        const val PROMPT = "Report the status of the warp core."
        const val REPLY =
            "The warp core is stable. Plasma flow is nominal across all injector manifolds, and " +
                "the intermix ratio is holding at one to one. No anomalies detected this cycle."
        const val STATE_TIMEOUT_MS = 10_000L
        const val SETTLE_MS = 350L
    }
}
