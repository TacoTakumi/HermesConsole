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
import com.hermesconsole.voiceclient.TranscriptSegment
import com.hermesconsole.voiceclient.TtsEvent
import com.hermesconsole.wakeword.WakeWord
import com.hermesconsole.wakeword.WakeWordSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * THE T-44 ACCUMULATION CHECK, on the SM-X210 against the real Hermes gateway: three spoken turns
 * in one session, each driven through the shipping wake path - the retire point under verify is
 * [VoiceLoop]'s own `onWake` - and the wall must hold every finished exchange at each new wake
 * instead of wiping back to STANDING BY. Then the detour: another session and back, and the pane
 * must reset to the freshly fetched back-log with every line exactly once.
 *
 * The wake word and the transcript are scripted ([FireableWake], [WakeVoice]) - a microphone and
 * a live STT socket would put a human voice in a CI loop - but from the endpointed transcript
 * onward the turn is the shipping one: real dispatch, real gateway, real session store. As in
 * [SessionBacklogDisplayTest], the wall is the real [ConsoleShell], rendered on the real screen
 * and photographed; shots land in `/sdcard/Android/data/com.hermesconsole/files/t44/`.
 *
 * Skipped unless the gateway's key is available (REQ-28). It comes from the console's settings
 * store (T-41: an arg-passed key is logged by adbd); hermesKey=/hermesHost= still override:
 *
 *     scripts/connected-test.sh SessionAccumulationDisplayTest
 */
@RunWith(AndroidJUnit4::class)
class SessionAccumulationDisplayTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private class NoCapture : AudioCapture {
        override val config = AudioConfig()
        override fun frames(): Flow<ByteArray> = emptyFlow()
        override fun close() = Unit
    }

    private class SilentSink(override val sampleRateHz: Int) : AudioSink {
        override fun write(pcm: ByteArray) = Unit
        override fun stop() = Unit
        override fun close() = Unit
    }

    /** The wake phrase on demand: each fire drives the shipping `onWake` - T-44's retire point. */
    private class FireableWake : WakeWordSource {
        private val heard = Channel<WakeWord>(Channel.UNLIMITED)

        override fun detections(frames: Flow<ByteArray>): Flow<WakeWord> = flow {
            coroutineScope {
                // The real detector consumes the capture flow; so must this one.
                launch { frames.collect { } }
                for (word in heard) emit(word)
            }
        }

        fun fire() {
            check(heard.trySend(WakeWord("hey computer", System.currentTimeMillis())).isSuccess)
        }

        override fun reset() = Unit
        override fun close() {
            heard.close()
        }
    }

    /**
     * VoiceManager reduced to what a spoken turn needs: the turn_started ack (observable, so the
     * test can wait for the loop to open the turn before speaking into it), a transcript the test
     * scripts, and the two TTS acknowledgements that complete a turn. No socket, no synthesis -
     * the turn's lifecycle is real, the speech is elided.
     */
    private class WakeVoice : VoiceLink {
        private val _transcript = MutableStateFlow(Transcript())
        override val transcript: StateFlow<Transcript> = _transcript
        private val _tts = MutableSharedFlow<TtsEvent>(extraBufferCapacity = 64)
        override val tts: Flow<TtsEvent> = _tts
        private val started = mutableSetOf<String>()

        /** The server-side turn counter (D-18), observable so the test can await the ack. */
        val openTurns = MutableStateFlow(0)

        override suspend fun turnStart(): Int = openTurns.updateAndGet { it + 1 }
        override fun sendCapture(frame: ByteArray) = Unit
        override fun cancelTts(requestId: String) = Unit

        override fun sendTtsText(requestId: String, text: String, done: Boolean) {
            if (started.add(requestId)) check(_tts.tryEmit(TtsEvent.Start(requestId, 22_050)))
            if (done) check(_tts.tryEmit(TtsEvent.End(requestId)))
        }

        /** The speaker finishes a thought, stamped with the open turn as the server stamps it. */
        fun hear(index: Int, text: String) {
            _transcript.value = Transcript(
                _transcript.value.segments.filterNot { it.index == index } +
                    TranscriptSegment(
                        index = index,
                        text = text,
                        isFinal = true,
                        endpointed = true,
                        turnId = openTurns.value,
                    ),
            )
        }
    }

    @Test
    fun three_spoken_turns_accumulate_on_the_wall_and_survive_every_wake() = runBlocking {
        val backends = TestBackends.resolve()
        assumeTrue(TestBackends.NEEDS_HERMES, backends.hermesKey.isNotBlank())
        val hermes = HermesClient(
            HermesConfig(
                host = backends.hermesHost,
                port = backends.hermesPort,
                apiKey = backends.hermesKey,
            ),
        )

        val stamp = System.currentTimeMillis()
        val a = hermes.createSession(title = "accumulate A $stamp")
        val b = hermes.createSession(title = "accumulate B $stamp")
        Log.i(TAG, "sessions: A=${a.id} B=${b.id}")

        val prompt1 = "The codeword for this conversation is TEAL3. Reply with exactly: OK."
        val prompt2 = "What is the codeword? Reply with only the codeword."
        val prompt3 = "Reply with exactly: THIRD CONFIRMED."

        // The shipping wiring, exactly as WallConsole has it (T-43): the switcher's session
        // scopes each dispatch, the display read feeds the pane, entry clears the wall.
        val scope = CoroutineScope(Dispatchers.Default)
        lateinit var scoped: VoiceLoop
        val sessions = SessionBacklog(
            fetch = { hermes.sessionMessages(it) },
            clearWall = { scoped.clearTurn() },
            scope = scope,
        )
        val wake = FireableWake()
        val voice = WakeVoice()
        scoped = VoiceLoop(
            capture = NoCapture(),
            wake = wake,
            voice = voice,
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
                    val spoken by scoped.spoken.collectAsState()
                    ConsoleShell(
                        state = state,
                        turn = turn,
                        backlog = backlog,
                        spoken = spoken,
                        contentScroll = scroll,
                    )
                }
            }

            try {
                scoped.start()
                sessions.select(a.id)

                // Turn one, spoken: wake, then the thought, then the gateway's reply.
                spokenTurn(scoped, wake, voice, turnId = 1, prompt = prompt1)
                assertEquals(prompt1, scoped.turn.value.prompt)
                assertTrue("nothing is retired while the finished turn is live", scoped.spoken.value.isEmpty())
                settleAndShoot("1-first-exchange")

                // THE WAKE THAT USED TO WIPE THE WALL. The finished exchange must move up into
                // the spoken record, on display, while the new turn opens clean below it.
                wake.fire()
                withTimeout(ACK_TIMEOUT_MS) { voice.openTurns.first { it == 2 } }
                assertEquals(ConsoleState.LISTENING, scoped.state.value)
                assertEquals(
                    "the prior exchange is on the wall at the new wake, not wiped off it",
                    1,
                    scoped.spoken.value.size,
                )
                assertEquals(prompt1, scoped.spoken.value[0].prompt)
                assertEquals("the new turn opens clean", "", scoped.turn.value.prompt)
                settleAndShoot("2-wake-holds-the-wall")

                // Turn two: the recall proves the accumulated wall and the session scope agree.
                voice.hear(index = 2, text = prompt2)
                awaitReady(scoped)
                assertTrue(
                    "the gateway recalled TEAL3 - the turn ran in the entered session",
                    scoped.turn.value.reply.contains("TEAL3"),
                )

                // Turn three, and the whole conversation is the wall: two retired exchanges in
                // spoken order, the third standing live below them.
                wake.fire()
                withTimeout(ACK_TIMEOUT_MS) { voice.openTurns.first { it == 3 } }
                assertEquals(
                    "both finished exchanges, in spoken order",
                    listOf(prompt1, prompt2),
                    scoped.spoken.value.map { it.prompt },
                )
                voice.hear(index = 3, text = prompt3)
                awaitReady(scoped)

                assertEquals(2, scoped.spoken.value.size)
                assertEquals(prompt1, scoped.spoken.value[0].prompt)
                assertEquals(prompt2, scoped.spoken.value[1].prompt)
                assertTrue(scoped.spoken.value[1].reply.contains("TEAL3"))
                assertEquals(prompt3, scoped.turn.value.prompt)
                assertTrue(scoped.turn.value.reply.isNotBlank())
                settleAndShoot("3-three-exchanges-top")
                runBlocking { withContext(Dispatchers.Main) { scroll.scrollTo(scroll.maxValue) } }
                settleAndShoot("4-three-exchanges-bottom")

                // The detour: into B and back. Entry resets the accumulated display to the
                // freshly fetched back-log - the same conversation, every line exactly once.
                sessions.select(b.id)
                sessions.select(a.id)
                val restored = withTimeout(FETCH_TIMEOUT_MS) {
                    sessions.backlog.first { it != null && it.size == 6 }!!
                }
                assertEquals(
                    "all three turns, as user/assistant lines in spoken order",
                    listOf("user", "assistant", "user", "assistant", "user", "assistant"),
                    restored.map { it.role },
                )
                listOf(prompt1, prompt2, prompt3).forEach { prompt ->
                    assertEquals("no line twice: $prompt", 1, restored.count { it.content == prompt })
                }
                assertTrue("entry reset the accumulation", scoped.spoken.value.isEmpty())
                assertEquals("entry cleared the live turn", TurnLog(), scoped.turn.value)
                settleAndShoot("5-restored-once")
            } finally {
                scoped.close()
                scope.cancel()
            }
        }
    }

    /** One spoken turn, driven to completion through the shipping wake path. */
    private suspend fun spokenTurn(loop: VoiceLoop, wake: FireableWake, voice: WakeVoice, turnId: Int, prompt: String) {
        wake.fire()
        withTimeout(ACK_TIMEOUT_MS) { voice.openTurns.first { it == turnId } }
        voice.hear(index = turnId, text = prompt)
        awaitReady(loop)
    }

    /** The turn is over when the loop is READY again - it is LISTENING or beyond when this starts. */
    private suspend fun awaitReady(loop: VoiceLoop) {
        withTimeout(TURN_TIMEOUT_MS) { loop.state.first { it == ConsoleState.READY } }
    }

    private fun settleAndShoot(name: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(SETTLE_MS)
        val shot = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull("screenshot $name came back null - is the screen on?", shot)
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "t44").apply { mkdirs() }
        val file = File(dir, "$name.png")
        FileOutputStream(file).use { shot!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "screenshot: ${file.absolutePath} (${shot.width}x${shot.height})")
    }

    private companion object {
        const val TAG = "T44Accumulate"
        const val TURN_TIMEOUT_MS = 120_000L
        const val ACK_TIMEOUT_MS = 10_000L
        const val FETCH_TIMEOUT_MS = 10_000L
        const val SETTLE_MS = 350L
    }
}
