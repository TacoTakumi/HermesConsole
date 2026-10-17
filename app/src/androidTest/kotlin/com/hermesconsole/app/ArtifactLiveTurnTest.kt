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
import com.hermesconsole.app.artifacts.ArtifactRouter
import com.hermesconsole.app.artifacts.ImageApplet
import com.hermesconsole.app.artifacts.MarkdownStream
import com.hermesconsole.app.artifacts.MdCode
import com.hermesconsole.app.artifacts.MdHeading
import com.hermesconsole.app.artifacts.MdList
import com.hermesconsole.app.artifacts.MdTable
import com.hermesconsole.audio.AudioCapture
import com.hermesconsole.audio.AudioConfig
import com.hermesconsole.audio.AudioSink
import com.hermesconsole.audio.AudioSinkFactory
import com.hermesconsole.audio.TtsPlayer
import com.hermesconsole.hermesclient.HermesClient
import com.hermesconsole.hermesclient.HermesConfig
import com.hermesconsole.voiceclient.Transcript
import com.hermesconsole.voiceclient.TtsEvent
import com.hermesconsole.wakeword.WakeWord
import com.hermesconsole.wakeword.WakeWordSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * THE T-40 LIVE CHECK, on the SM-X210 against the real Hermes gateway with the render_artifact
 * plugin deployed: a live turn invokes the tool, its args ride `tool.started` whole over the
 * patched SSE stream, and [ArtifactRouter] routes the payload to a pane that renders on the real
 * screen while the reply is still running - the clause T-24 and T-26 stayed blocked on. Both v1
 * kinds are exercised (REQ-16): markdown first - a document carrying all four element kinds of
 * T-26's acceptance (heading, list, code block, table), asserted as shapes through the shipping
 * parser - then image in a second turn, which also proves the pane lifecycle live - a new run's
 * first artifact replaces the prior run's panes (REQ-15).
 *
 * The turn is typed ([VoiceLoop.say], T-17) - the same dispatch the debug console drives, and the
 * artifact seam is downstream of dispatch - but from there everything is the shipping path: real
 * gateway, real event stream, the reply chunked through the real TTS pipe (to an ack-only link:
 * a live VoiceManager in a CI loop would put synthesis latency, not evidence, in the timings),
 * and the wall is the real [ConsoleShell], photographed; shots land in
 * `/sdcard/Android/data/com.hermesconsole/files/t40/`.
 *
 * Skipped unless the gateway's key is available (REQ-28). It comes from the console's settings
 * store (T-41: an arg-passed key is logged by adbd); hermesKey=/hermesHost= still override:
 *
 *     scripts/connected-test.sh ArtifactLiveTurnTest
 */
@RunWith(AndroidJUnit4::class)
class ArtifactLiveTurnTest {

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

    /**
     * VoiceManager reduced to what a typed turn needs: the two TTS acknowledgements that let the
     * spoken reply complete. No socket, no synthesis - the turn's lifecycle is real, the speech
     * is elided (the [SessionAccumulationDisplayTest] convention).
     */
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
    fun a_live_turn_renders_the_pane_while_the_reply_runs() = runBlocking {
        val backends = TestBackends.resolve()
        assumeTrue(TestBackends.NEEDS_HERMES, backends.hermesKey.isNotBlank())
        val hermes = HermesClient(
            HermesConfig(
                host = backends.hermesHost,
                port = backends.hermesPort,
                apiKey = backends.hermesKey,
            ),
        )

        // The artifact seam, wired exactly as the debug console carries it (T-24): the router on
        // the loop's event tap, its panes on the shell.
        val router = ArtifactRouter()
        val loop = VoiceLoop(
            capture = NoCapture(),
            wake = NoWake(),
            voice = AckVoice(),
            agent = hermes.asAgent(),
            player = TtsPlayer(AudioSinkFactory { SilentSink(it) }),
            sessionId = hermes.visitSession(),
            eventTap = router::onEvent,
        )

        val scroll = ScrollState(0)
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra("debug", true) // no microphone console behind the test's back (T-41)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
                // A dark display runs no frames (rig truth): hold the screen for the whole run.
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity.setContent {
                    val state by loop.state.collectAsState()
                    val turn by loop.turn.collectAsState()
                    val panes by router.panes.collectAsState()
                    ConsoleShell(
                        state = state,
                        turn = turn,
                        panes = panes.map { pane -> ConsolePane(pane.title) { pane.Content() } },
                        contentScroll = scroll,
                    )
                }
            }

            try {
                loop.start()

                // Turn one, markdown: the pane must be up before the turn is over - the artifact
                // renders WHILE the reply streams, never instead of it (REQ-15). The document
                // carries all four element kinds of T-26's acceptance (REQ-16), asserted below
                // through the shipping parser - the block list the pane renders from.
                loop.say(
                    "Use the render_artifact tool exactly once, with kind set to markdown, to " +
                        "display a short status document containing, in order: a level-one " +
                        "heading, a two-item bullet list, a fenced code block with a language " +
                        "tag, and a table with two columns and two data rows. Then reply with " +
                        "one short sentence.",
                )
                val stateAtPane = withTimeout(PANE_TIMEOUT_MS) {
                    router.panes.first { it.isNotEmpty() }
                    loop.state.value
                }
                assertEquals("markdown", router.panes.value.single().artifact.type)
                // The four kinds, as the shapes the renderer switches on (T-26): the pane is a
                // pure function of this block list, so a shape here IS structure on the wall.
                val markdown =
                    (router.panes.value.single().artifact.args["markdown"] as? JsonPrimitive)?.contentOrNull
                assertNotNull("the payload's markdown field", markdown)
                val shapes = MarkdownStream().sync(markdown!!).map { it::class }
                listOf(MdHeading::class, MdList::class, MdCode::class, MdTable::class).forEach { kind ->
                    assertTrue(
                        "the live markdown artifact carries a ${kind.simpleName}, got $shapes",
                        kind in shapes,
                    )
                }
                assertNotEquals(
                    "the pane went up while the turn was still running, not after it",
                    ConsoleState.READY,
                    stateAtPane,
                )
                awaitReady(loop)
                assertTrue("the reply spoke", loop.turn.value.reply.isNotBlank())
                settleAndShoot(scroll, "1-markdown-pane-live")

                // Turn two, image: the other v1 kind (REQ-16), and the lifecycle - the new run's
                // first artifact takes the screen, the markdown pane retires with its run.
                val composedBefore = ImageApplet.composed.value
                loop.say(
                    "Use the render_artifact tool exactly once, with kind set to image, to " +
                        "display the picture at $PROBE_IMAGE_URL - pass that URL through " +
                        "unchanged. Then reply with one short sentence.",
                )
                withTimeout(PANE_TIMEOUT_MS) {
                    router.panes.first { panes -> panes.any { it.artifact.type == "image" } }
                }
                awaitReady(loop)
                assertEquals(
                    "the new run's artifact replaced the old run's pane",
                    listOf("image"),
                    router.panes.value.map { it.artifact.type },
                )
                assertTrue("the reply spoke", loop.turn.value.reply.isNotBlank())
                // The pane renders the image itself, not a readout about it (T-25): wait for the
                // applet to have fetched, decoded and composed the live URL's bitmap before the
                // camera fires - this is the line the placeholder pane could never get past.
                withTimeout(PANE_TIMEOUT_MS) { ImageApplet.composed.first { it > composedBefore } }
                settleAndShoot(scroll, "2-image-pane-live")
            } finally {
                loop.close()
            }
        }
    }

    /** The turn is over when the loop is READY again. */
    private suspend fun awaitReady(loop: VoiceLoop) {
        withTimeout(TURN_TIMEOUT_MS) { loop.state.first { it == ConsoleState.READY } }
    }

    private fun settleAndShoot(scroll: ScrollState, name: String) {
        // The pane is a real document: wait for its layout, not a guess (rig truth - ScrollState
        // reports Int.MAX_VALUE until the content column has actually measured).
        instrumentation.waitForIdleSync()
        val deadline = System.currentTimeMillis() + LAYOUT_TIMEOUT_MS
        while (scroll.maxValue == Int.MAX_VALUE && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("the content column never laid out", scroll.maxValue != Int.MAX_VALUE)
        Thread.sleep(SETTLE_MS)
        val shot = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull("screenshot $name came back null - is the screen on?", shot)
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "t40").apply { mkdirs() }
        val file = File(dir, "$name.png")
        FileOutputStream(file).use { shot!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "screenshot: ${file.absolutePath} (${shot.width}x${shot.height})")
    }

    private companion object {
        const val TAG = "T40ArtifactLive"
        const val PROBE_IMAGE_URL = "https://upload.wikimedia.org/wikipedia/commons/3/3a/Cat03.jpg"
        const val PANE_TIMEOUT_MS = 120_000L
        const val TURN_TIMEOUT_MS = 120_000L
        const val LAYOUT_TIMEOUT_MS = 15_000L
        const val SETTLE_MS = 500L
    }
}
