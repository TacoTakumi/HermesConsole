package com.hermesconsole.app

import android.content.Intent
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.hermesconsole.app.artifacts.ArtifactPane
import com.hermesconsole.app.artifacts.ArtifactRouter
import com.hermesconsole.audio.AudioCapture
import com.hermesconsole.audio.AudioConfig
import com.hermesconsole.audio.AudioTrackOneShotSink
import com.hermesconsole.audio.AudioTrackSinkFactory
import com.hermesconsole.audio.TtsPlayer
import com.hermesconsole.hermesclient.HermesClient
import com.hermesconsole.hermesclient.HermesConfig
import com.hermesconsole.hermesclient.createSession
import com.hermesconsole.hermesclient.sessionMessages
import com.hermesconsole.lcars.LcarsBar
import com.hermesconsole.lcars.LcarsColors
import com.hermesconsole.lcars.LcarsCue
import com.hermesconsole.lcars.LcarsCuePlayer
import com.hermesconsole.lcars.LcarsMetrics
import com.hermesconsole.lcars.LcarsPill
import com.hermesconsole.lcars.LcarsSegment
import com.hermesconsole.lcars.LcarsTheme
import com.hermesconsole.voiceclient.VoiceLinkState
import com.hermesconsole.voiceclient.VoiceManagerClient
import com.hermesconsole.voiceclient.VoiceManagerConfig
import com.hermesconsole.wakeword.WakeWord
import com.hermesconsole.wakeword.WakeWordSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The debug screen (T-17, REQ-24): a typed command dispatched as a full Hermes turn - streamed
 * reply, streamed TTS, the real speaker - with the microphone path disengaged by construction.
 *
 * This is the console's fault isolator. A voice turn crosses four systems (wake word, STT, agent,
 * TTS), and when one misbehaves the first question is always which. A turn dispatched from here
 * has had two of them surgically removed: no AudioRecord exists in the process, so whatever
 * failure reproduces did so with the capture path and STT excluded - and the turn log says so
 * ([TurnLog.captureEngaged]).
 */

/** The two backends the loop talks to, paired for the debug assembly. */
data class DebugBackends(val hermes: HermesConfig, val voice: VoiceManagerConfig)

/**
 * Backend config off the launch intent, at runtime (REQ-28): no credential is compiled in, and
 * none is ever logged. This is the explicit override - a launch carrying both keys uses exactly
 * what the intent says, persisted settings unread (T-23 keeps that so: an instrumented run can
 * always dial a scratch backend without touching the console's real config). Without both keys it
 * is null, and the launch falls back to the settings store.
 *
 *     adb shell am force-stop com.hermesconsole && \
 *     adb shell am start -n com.hermesconsole/com.hermesconsole.app.MainActivity \
 *       --ez debug true --es hermesKey <key> --es vmKey <key> \
 *       [--es hermesHost <host> --es hermesPort <port> --es vmHost <host> --es vmPort <port>]
 *
 * The force-stop is load-bearing: MainActivity is singleTask, so a launch intent aimed at a
 * running console is delivered to the live instance - which read its extras in onCreate and will
 * not read these. Stopping first also means the debug console is the *only* console, rather than
 * one stacked over a live loop that never stopped listening.
 */
fun debugBackends(intent: Intent): DebugBackends? {
    val hermesKey = intent.getStringExtra("hermesKey") ?: return null
    val vmKey = intent.getStringExtra("vmKey") ?: return null
    return DebugBackends(
        hermes = HermesConfig(
            host = intent.getStringExtra("hermesHost") ?: BackendSettings.DEFAULT_HERMES_HOST,
            port = intent.getStringExtra("hermesPort")?.toIntOrNull() ?: BackendSettings.DEFAULT_HERMES_PORT,
            apiKey = hermesKey,
        ),
        voice = VoiceManagerConfig(
            host = intent.getStringExtra("vmHost") ?: BackendSettings.DEFAULT_VM_HOST,
            port = intent.getStringExtra("vmPort")?.toIntOrNull() ?: BackendSettings.DEFAULT_VM_PORT,
            apiKey = vmKey,
        ),
    )
}

/**
 * The mic path, disengaged by construction: no recorder is created, so no AudioRecord read loop
 * can run (REQ-24). Stronger than a muted or unstarted microphone - the assertion the debug turn
 * makes is structural, not behavioral.
 */
private class DisengagedCapture : AudioCapture {
    override val config = AudioConfig()
    override fun frames(): Flow<ByteArray> = emptyFlow()
    override fun close() = Unit
}

/** No wake word either: the only way into a turn from the debug screen is to type one. */
private class NoWakeWord : WakeWordSource {
    override fun detections(frames: Flow<ByteArray>): Flow<WakeWord> = emptyFlow()
    override fun reset() = Unit
    override fun close() = Unit
}

/**
 * The voice loop assembled with its microphone half absent: the same [VoiceLoop], the same
 * clients, the same speaker as the wall console - minus only capture and wake word. What the
 * debug screen reproduces is therefore what the real console would have done, STT excluded.
 */
class DebugConsole(backends: DebugBackends, audioManager: AudioManager) : AutoCloseable {

    private val voice = VoiceManagerClient(backends.voice)
    private val hermes = HermesClient(backends.hermes)

    /** The TTS link's health, on screen: a dead socket is the first thing to rule out. */
    val link: StateFlow<VoiceLinkState> = voice.state

    /**
     * The artifact seam (T-24, REQ-15): panes routed off the same event stream the reply is
     * spoken from. The debug console carries the wiring because it is where live turns run
     * today; when the loop is constructed in-app, the same two lines move with it.
     */
    val router = ArtifactRouter()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The approval desk (T-27, REQ-14): a typed turn can stall on a dangerous command exactly as
     * a spoken one can, and a debug rig that let it hang would be reproducing its own bug. Touch
     * only - this console has no microphone to answer with.
     */
    val approvals = ApprovalController(gateway = hermes.asApprovalGateway(), scope = scope)

    private val sinkFactory = AudioTrackSinkFactory(audioManager, AudioConfig())

    /**
     * The kit's earcons on the same route as TTS (REQ-39/REQ-33), here as in the console - one-shot
     * MODE_STATIC clips (T-54 follow-up), not streamed into a track that starves between taps.
     */
    private val cues = LcarsCuePlayer(AudioTrackOneShotSink(audioManager, AudioConfig()))

    /**
     * The touch chirp for this screen's own pressables (REQ-39), on the same echo-cancelled output
     * path as the loop's earcons and TTS. The debug screen is its own composition root - a separate
     * `setContent` from the console path (T-54) - so it must provide this as [LocalLcarsCues]
     * itself: the console root's provider does not reach here, and without it the dispatch and
     * approval pills would chirp into the local's silent default.
     */
    val touchCues: (LcarsCue) -> Unit get() = cues::play

    /**
     * The debug screen's turns are scoped exactly as the wall's are (REQ-01): no session is ever
     * entered here, so the visit's first typed dispatch makes one and every later turn lands in
     * it. What this screen reproduces stays what the real console would have done.
     */
    private val sessions = SessionBacklog(
        fetch = { hermes.sessionMessages(it) },
        scope = scope,
    )

    val loop = VoiceLoop(
        capture = DisengagedCapture(),
        wake = NoWakeWord(),
        voice = voice.asVoiceLink(),
        agent = hermes.asAgent(),
        player = TtsPlayer(sinkFactory),
        sessionId = { sessions.currentOrCreate { hermes.createSession().id } },
        eventTap = { event ->
            router.onEvent(event)
            approvals.onEvent(event)
        },
        cues = cues::play,
    )

    fun start() {
        loop.start()
        voice.connect()
    }

    override fun close() {
        loop.close()
        cues.close()
        voice.shutdown()
        scope.cancel()
    }
}

/** The live debug console, wired to the screen. */
@Composable
fun DebugScreen(console: DebugConsole) {
    val state by console.loop.state.collectAsState()
    val turn by console.loop.turn.collectAsState()
    val link by console.link.collectAsState()
    val panes by console.router.panes.collectAsState()
    DebugScreen(
        state = state,
        turn = turn,
        link = link,
        onDispatch = console.loop::say,
        panes = panes,
        approval = { ApprovalHost(console.approvals) },
    )
}

/**
 * The screen itself, stateless: a command line, a dispatch pill, and the turn as evidence -
 * prompt, reply, and the readouts that say what the pipe did (and that the capture path stayed
 * idle while it did it).
 */
@Composable
fun DebugScreen(
    state: ConsoleState,
    turn: TurnLog,
    link: VoiceLinkState,
    onDispatch: (String) -> Unit,
    panes: List<ArtifactPane> = emptyList(),
    /** The approval pane's slot (T-27), pinned under the command line as the shell pins its own. */
    approval: (@Composable () -> Unit)? = null,
) {
    LcarsTheme {
        var command by remember { mutableStateOf("") }
        val dispatch = {
            val text = command.trim()
            if (text.isNotEmpty()) {
                onDispatch(text)
                command = ""
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LcarsColors.Space)
                .padding(LcarsMetrics.Gap * 2)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap * 2),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(LcarsMetrics.BarHeight),
                horizontalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap),
            ) {
                LcarsBar(
                    segments = listOf(
                        LcarsSegment("debug", LcarsColors.Butterscotch, weight = 0.25f),
                        LcarsSegment(null, LcarsColors.Bluey, weight = 0.45f),
                        LcarsSegment("stt excluded", LcarsColors.IceBlue, weight = 0.3f),
                    ),
                    modifier = Modifier.weight(1f),
                    capStart = true,
                    capEnd = false,
                )
                StateIndicator(state)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = command,
                    onValueChange = { command = it },
                    modifier = Modifier
                        .weight(1f)
                        .height(LcarsMetrics.BarHeight)
                        .border(2.dp, LcarsColors.Gray, RoundedCornerShape(LcarsMetrics.InnerRadius))
                        .padding(horizontal = LcarsMetrics.LabelPadding),
                    textStyle = LcarsTheme.typography.body.copy(color = LcarsColors.OnSpace),
                    cursorBrush = SolidColor(LcarsColors.Orange),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { dispatch() }),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                            if (command.isEmpty()) {
                                BasicText(
                                    text = "TYPE A COMMAND",
                                    style = LcarsTheme.typography.body.copy(color = LcarsColors.Gray),
                                )
                            }
                            inner()
                        }
                    },
                )
                LcarsPill(
                    text = "dispatch",
                    color = LcarsColors.Orange,
                    enabled = state == ConsoleState.READY && link == VoiceLinkState.Connected,
                    onClick = dispatch,
                )
            }

            approval?.invoke()

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap * 2),
            ) {
                ReadoutLine(
                    label = "tts link",
                    value = link.label(),
                    valueColor = if (link == VoiceLinkState.Connected) LcarsColors.GoldenTanoi else LcarsColors.Tomato,
                )
                // THE LINE THE SCREEN EXISTS FOR (REQ-24): idle means no capture audio flowed
                // through the loop for this turn - the failure being reproduced excluded STT.
                ReadoutLine(
                    label = "capture path",
                    value = if (turn.captureEngaged) "engaged" else "idle",
                    valueColor = if (turn.captureEngaged) LcarsColors.Tomato else LcarsColors.GoldenTanoi,
                )
                turn.runId?.let { ReadoutLine("run", it) }
                turn.firstAudioLatencyMillis?.let { ReadoutLine("first audio", "$it ms") }
                if (turn.audioBytes > 0) {
                    ReadoutLine("audio played", "${turn.audioBytes} B")
                    ReadoutLine("spoke before run completed", "${turn.spokeBeforeRunCompleted}")
                }
                if (turn.prompt.isNotBlank()) {
                    BasicText("QUERY", style = LcarsTheme.typography.header.copy(color = LcarsColors.IceBlue))
                    BasicText(turn.prompt, style = LcarsTheme.typography.body.copy(color = LcarsColors.AlmondCreme))
                }
                if (turn.reply.isNotBlank()) {
                    BasicText("RESPONSE", style = LcarsTheme.typography.header.copy(color = LcarsColors.Orange))
                    BasicText(turn.reply, style = LcarsTheme.typography.body.copy(color = LcarsColors.OnSpace))
                }
                // Routed artifacts (T-24): each pane draws itself; this host knows only a title
                // and a Content - a type it has never heard of costs it zero lines (REQ-17).
                panes.forEach { pane ->
                    BasicText(
                        text = pane.title.uppercase(),
                        style = LcarsTheme.typography.header.copy(color = LcarsColors.GoldenTanoi),
                    )
                    pane.Content()
                }
            }
        }
    }
}

/**
 * A debug launch with no keys anywhere - none on the intent, none persisted: say exactly what is
 * missing instead of dialling nowhere. The keys are runtime config because nothing may be compiled
 * in (REQ-28) - and they are named here, not echoed back.
 */
@Composable
fun DebugScreenUnconfigured() {
    LcarsTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LcarsColors.Space)
                .padding(LcarsMetrics.Gap * 2),
            verticalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap * 3),
        ) {
            LcarsBar(
                segments = listOf(
                    LcarsSegment("debug", LcarsColors.Butterscotch, weight = 0.25f),
                    LcarsSegment(null, LcarsColors.Bluey, weight = 0.75f),
                ),
                modifier = Modifier.fillMaxWidth(),
                capStart = true,
            )
            BasicText(
                text = "NO BACKEND CONFIG",
                style = LcarsTheme.typography.header.copy(color = LcarsColors.Tomato),
            )
            BasicText(
                text = "Keys are runtime config, never compiled in (REQ-28). Set them on the\n" +
                    "settings screen (config, on the shell's rail), or relaunch with:\n\n" +
                    "adb shell am force-stop com.hermesconsole\n" +
                    "adb shell am start -n com.hermesconsole/com.hermesconsole.app.MainActivity \\\n" +
                    "  --ez debug true --es hermesKey <key> --es vmKey <key>",
                style = LcarsTheme.typography.readout.copy(color = LcarsColors.AlmondCreme),
            )
        }
    }
}

@Composable
private fun ReadoutLine(label: String, value: String, valueColor: Color = LcarsColors.AlmondCreme) {
    Row(horizontalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap * 2)) {
        BasicText(
            text = label.uppercase(),
            style = LcarsTheme.typography.readout.copy(color = LcarsColors.IceBlue),
        )
        BasicText(
            text = value.uppercase(),
            style = LcarsTheme.typography.readout.copy(color = valueColor),
        )
    }
}

private fun VoiceLinkState.label(): String = when (this) {
    VoiceLinkState.Idle -> "idle"
    VoiceLinkState.Connecting -> "connecting"
    VoiceLinkState.Connected -> "connected"
    is VoiceLinkState.Dropped -> "dropped"
    VoiceLinkState.AuthFailed -> "auth failed"
}
