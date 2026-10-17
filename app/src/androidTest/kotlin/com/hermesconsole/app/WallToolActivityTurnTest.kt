package com.hermesconsole.app

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hermesconsole.app.tools.ToolActivityRouter
import com.hermesconsole.app.tools.linesForSession
import com.hermesconsole.audio.AudioCapture
import com.hermesconsole.audio.AudioConfig
import com.hermesconsole.audio.AudioSink
import com.hermesconsole.audio.AudioSinkFactory
import com.hermesconsole.audio.TtsPlayer
import com.hermesconsole.hermesclient.HermesEvent
import com.hermesconsole.voiceclient.Transcript
import com.hermesconsole.voiceclient.TtsEvent
import com.hermesconsole.wakeword.WakeWord
import com.hermesconsole.wakeword.WakeWordSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * tool-output-pane T-10 / REQ-10, REQ-15, REQ-09: tool lines woven inline into the reply on the wall.
 *
 * The ArtifactLiveTurnTest pattern, made deterministic and mirroring the wall's own wiring: a real
 * [VoiceLoop] with the tool router in its tap, the router tagging each line with the live session and
 * the reply offset where it fired, and the shell weaving the run's lines back into that run's reply
 * via `toolLinesByRun`. On the stream: a delta, then a `tool.completed`, then another delta - so the
 * row must land BETWEEN the two reply segments, in the scrolling content column (REQ-15), not bumped
 * to the bottom, and a tap reveals the reader's body (REQ-03). Then the session flips: the line
 * belongs to session A, so switching to B drops it and back restores it. A screenshot lands under
 * files/wall/.
 *
 *     scripts/connected-test.sh WallToolActivityTurnTest
 */
@RunWith(AndroidJUnit4::class)
class WallToolActivityTurnTest {

    @get:Rule
    val compose = createComposeRule()

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

    private class FakeAgent : Agent {
        private val events = Channel<HermesEvent>(Channel.UNLIMITED)

        override suspend fun dispatch(input: String, sessionId: String): String = RUN_ID
        override suspend fun stop(runId: String) = Unit

        override fun events(runId: String): Flow<HermesEvent> = flow {
            for (event in events) {
                emit(event)
                if (event.isTerminal) break
            }
        }

        fun emit(event: HermesEvent) {
            events.trySend(event)
        }
    }

    @Test
    fun a_tool_completed_weaves_inline_into_the_reply_and_scopes_to_its_session() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = MutableStateFlow<String?>(SESSION_A)
        val agent = FakeAgent()
        lateinit var loop: VoiceLoop
        val router = ToolActivityRouter(
            currentSession = { session.value },
            // The wall's own offset source: where the reply stands when the tool completes.
            replyLengthOf = { loop.turn.value.reply.length },
        )
        loop = VoiceLoop(
            capture = NoCapture(),
            wake = NoWake(),
            voice = AckVoice(),
            agent = agent,
            player = TtsPlayer(AudioSinkFactory { SilentSink(it) }),
            sessionId = { SESSION_A },
            scope = scope,
            eventTap = router::onEvent,
        )

        val scroll = ScrollState(0)
        compose.setContent {
            val state by loop.state.collectAsState()
            val turn by loop.turn.collectAsState()
            val spoken by loop.spoken.collectAsState()
            val lines by router.lines.collectAsState()
            val currentSession by session.collectAsState()
            ConsoleShell(
                state = state,
                turn = turn,
                spoken = spoken,
                // The activity's mapping, verbatim: the current session's lines, grouped by run (T-10).
                toolLinesByRun = linesForSession(lines, currentSession).groupBy { it.runId },
                contentScroll = scroll,
            )
        }

        try {
            loop.start()
            loop.say("run the build")

            // The reply, split by a tool that fires between the two halves.
            agent.emit(HermesEvent.MessageDelta(RUN_ID, "Let me run it. "))
            agent.emit(
                HermesEvent.ToolCompleted(
                    runId = RUN_ID,
                    tool = "execute_code",
                    durationSeconds = 0.42,
                    result = executeCodeResult("build ok\n$BODY_MARKER 47 tasks passed"),
                ),
            )
            agent.emit(HermesEvent.MessageDelta(RUN_ID, "It passed."))
            agent.emit(HermesEvent.RunCompleted(RUN_ID, output = ""))

            compose.waitUntil(TIMEOUT_MS) { router.lines.value.isNotEmpty() }
            compose.waitForIdle()

            // REQ-15: the row is on the wall, inside the scrolling content column - not pinned chrome.
            compose.onNode(hasText("execute_code") and hasAnyAncestor(hasScrollAction())).assertIsDisplayed()
            // T-10: both reply halves render - the tool landed between them, not after the whole reply.
            compose.onNodeWithText("Let me run it.", substring = true).assertIsDisplayed()
            compose.onNodeWithText("It passed.", substring = true).assertIsDisplayed()
            // No trailing TOOL ACTIVITY heading anywhere.
            compose.onNodeWithText("TOOL ACTIVITY").assertDoesNotExist()
            // Collapsed by default; a tap reveals the reader's body (REQ-03).
            compose.onNodeWithText(BODY_MARKER, substring = true).assertDoesNotExist()
            compose.onNodeWithText("execute_code").performClick()
            compose.onNodeWithText(BODY_MARKER, substring = true).assertIsDisplayed()
            shoot("tool-activity-inline")

            // T-07/T-10: the line belongs to session A. Switch to B and it is gone; back and it returns.
            session.value = SESSION_B
            compose.waitForIdle()
            compose.onNodeWithText("execute_code").assertDoesNotExist()

            session.value = SESSION_A
            compose.waitForIdle()
            compose.onNodeWithText("execute_code").assertIsDisplayed()
        } finally {
            loop.close()
            scope.cancel()
        }
    }

    private fun executeCodeResult(output: String): JsonPrimitive {
        val inner = buildJsonObject {
            put("output", output)
            put("return_code", 0)
        }
        return JsonPrimitive(inner.toString())
    }

    private fun shoot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.getExternalFilesDir(null), "wall").apply { mkdirs() }
        val file = File(dir, "$name.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "screenshot: ${file.absolutePath} (${bitmap.width}x${bitmap.height})")
    }

    private companion object {
        const val TAG = "T10WallToolActivity"
        const val RUN_ID = "run-1"
        const val SESSION_A = "session-A"
        const val SESSION_B = "session-B"
        const val BODY_MARKER = "SECRET_TOOL_BODY"
        const val TIMEOUT_MS = 10_000L
    }
}
