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
import com.hermesconsole.hermesclient.HermesClient
import com.hermesconsole.hermesclient.HermesConfig
import com.hermesconsole.hermesclient.createSession
import com.hermesconsole.hermesclient.sessionMessages
import com.hermesconsole.voiceclient.Transcript
import com.hermesconsole.voiceclient.TtsEvent
import com.hermesconsole.wakeword.WakeWord
import com.hermesconsole.wakeword.WakeWordSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * THE T-43 BACK-LOG CHECK, on the SM-X210 against the real Hermes gateway: two turns spoken in
 * session A, a detour through session B, and back - the conversation pane must open on both A
 * turns; a session created a moment ago must open empty; and the next spoken turn must append
 * after the restored lines without duplicating any of them.
 *
 * The turns are typed ([VoiceLoop.say]), as in [SessionContinuityTest]: the dispatch path from
 * the transcript onward is the shipping one, and what is under verify here is the pane, not the
 * pipes. For the same reason VoiceManager is reduced to its acknowledgements ([AckVoice]) - the
 * spoken pipe is T-22/T-34's proven ground, and a second live socket would only add a variable.
 * The wall itself is the real [ConsoleShell], rendered on the real screen and photographed;
 * shots land in `/sdcard/Android/data/com.hermesconsole/files/t43/`.
 *
 * Skipped unless the gateway's key is available (REQ-28). It comes from the console's settings
 * store (T-41: an arg-passed key is logged by adbd); hermesKey=/hermesHost= still override:
 *
 *     scripts/connected-test.sh SessionBacklogDisplayTest
 */
@RunWith(AndroidJUnit4::class)
class SessionBacklogDisplayTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private class NoCapture : AudioCapture {
        override val config = AudioConfig()
        override fun frames(): Flow<ByteArray> = emptyFlow()
        override fun close() = Unit
    }

    private class NoWakeWord : WakeWordSource {
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
     * VoiceManager reduced to the two acknowledgements a typed turn needs to complete: an
     * `audio_start` when the first token goes out to be spoken, an `audio_end` when the reply is
     * flushed. No socket, no synthesis - the turn's lifecycle is real, the speech is elided.
     */
    private class AckVoice : VoiceLink {
        private val _transcript = MutableStateFlow(Transcript())
        override val transcript: StateFlow<Transcript> = _transcript
        private val _tts = MutableSharedFlow<TtsEvent>(extraBufferCapacity = 64)
        override val tts: Flow<TtsEvent> = _tts
        private val started = mutableSetOf<String>()
        private var turns = 0

        override suspend fun turnStart(): Int = ++turns
        override fun sendCapture(frame: ByteArray) = Unit
        override fun cancelTts(requestId: String) = Unit

        override fun sendTtsText(requestId: String, text: String, done: Boolean) {
            if (started.add(requestId)) check(_tts.tryEmit(TtsEvent.Start(requestId, 22_050)))
            if (done) check(_tts.tryEmit(TtsEvent.End(requestId)))
        }
    }

    @Test
    fun the_pane_opens_on_the_entered_sessions_stored_history() = runBlocking {
        val backends = TestBackends.resolve()
        assumeTrue(TestBackends.NEEDS_HERMES, backends.hermesKey.isNotBlank())
        val hermes = HermesClient(
            HermesConfig(
                host = backends.hermesHost,
                port = backends.hermesPort,
                apiKey = backends.hermesKey,
            ),
        )

        // Two fresh conversations, made exactly as the ready screen makes one (REQ-23). Titles
        // are stamped unique: the gateway refuses a reused title, and this test must be rerunnable.
        val stamp = System.currentTimeMillis()
        val a = hermes.createSession(title = "backlog A $stamp")
        val b = hermes.createSession(title = "backlog B $stamp")
        Log.i(TAG, "sessions: A=${a.id} B=${b.id}")

        // The shipping wiring, exactly as WallConsole has it: the switcher's session scopes each
        // dispatch, the display read feeds the pane, entry clears the stale turn off the wall.
        val scope = CoroutineScope(Dispatchers.Default)
        lateinit var scoped: VoiceLoop
        val sessions = SessionBacklog(
            fetch = { hermes.sessionMessages(it) },
            clearWall = { scoped.clearTurn() },
            scope = scope,
        )
        scoped = VoiceLoop(
            capture = NoCapture(),
            wake = NoWakeWord(),
            voice = AckVoice(),
            agent = hermes.asAgent(),
            player = TtsPlayer(AudioSinkFactory { SilentSink(it) }),
            sessionId = { sessions.currentOrCreate { hermes.createSession().id } },
        )

        val scroll = ScrollState(0)
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra("debug", true) // no microphone console behind the test's back
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
                activity.setContent {
                    val state by scoped.state.collectAsState()
                    val turn by scoped.turn.collectAsState()
                    val backlog by sessions.backlog.collectAsState()
                    ConsoleShell(state = state, turn = turn, backlog = backlog, contentScroll = scroll)
                }
            }

            try {
                scoped.start()

                // Speak two turns in session A.
                sessions.select(a.id)
                turn(scoped, "The codeword for this conversation is INDIGO9. Reply with exactly: OK.")
                turn(scoped, "What is the codeword? Reply with only the codeword.")

                // The detour: into B, then back into A. Entry must restore both A turns.
                sessions.select(b.id)
                sessions.select(a.id)
                val restored = withTimeout(FETCH_TIMEOUT_MS) {
                    sessions.backlog.first { it != null && it.size == 4 }!!
                }
                assertEquals(
                    "both A turns, as user/assistant lines in spoken order",
                    listOf("user", "assistant", "user", "assistant"),
                    restored.map { it.role },
                )
                assertTrue("the first turn's prompt is restored", restored[0].content.contains("INDIGO9"))
                assertTrue("the recall reply is restored", restored[3].content.contains("INDIGO9"))
                assertEquals(
                    "entry cleared the stale turn - nothing on the wall to duplicate a restored line",
                    TurnLog(),
                    scoped.turn.value,
                )
                settleAndShoot("1-backlog-restored")

                // A session created a moment ago opens to an empty pane.
                val fresh = hermes.createSession(title = "backlog fresh $stamp")
                sessions.opened(fresh.id)
                assertEquals(emptyList<Any>(), sessions.backlog.value)
                settleAndShoot("2-fresh-empty")

                // Back in A, the next spoken turn appends after the back-log, duplicating nothing.
                sessions.select(a.id)
                withTimeout(FETCH_TIMEOUT_MS) { sessions.backlog.first { it != null && it.size == 4 } }
                turn(scoped, "Reply with exactly: APPEND CONFIRMED.")

                assertEquals(
                    "the back-log is fetched on entry and never refreshed behind a turn",
                    4,
                    sessions.backlog.value!!.size,
                )
                assertEquals("Reply with exactly: APPEND CONFIRMED.", scoped.turn.value.prompt)
                assertTrue("the live reply is on the wall", scoped.turn.value.reply.isNotBlank())
                assertFalse(
                    "the appended turn is live, not a restored line",
                    sessions.backlog.value!!.any { it.content.contains("APPEND CONFIRMED") },
                )
                settleAndShoot("3-appended-top")
                runBlocking { withContext(Dispatchers.Main) { scroll.scrollTo(scroll.maxValue) } }
                settleAndShoot("4-appended-bottom")
            } finally {
                scoped.close()
                scope.cancel()
            }
        }
    }

    /** One typed turn, driven to completion: dispatched, acknowledged, and the loop READY again. */
    private suspend fun turn(loop: VoiceLoop, prompt: String) {
        loop.say(prompt)
        withTimeout(TURN_TIMEOUT_MS) {
            loop.state.first { it != ConsoleState.READY }
            loop.state.first { it == ConsoleState.READY }
        }
    }

    private fun settleAndShoot(name: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(SETTLE_MS)
        val shot = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull("screenshot $name came back null - is the screen on?", shot)
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "t43").apply { mkdirs() }
        val file = File(dir, "$name.png")
        FileOutputStream(file).use { shot!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "screenshot: ${file.absolutePath} (${shot.width}x${shot.height})")
    }

    private companion object {
        const val TAG = "T43Backlog"
        const val TURN_TIMEOUT_MS = 120_000L
        const val FETCH_TIMEOUT_MS = 10_000L
        const val SETTLE_MS = 350L
    }
}
