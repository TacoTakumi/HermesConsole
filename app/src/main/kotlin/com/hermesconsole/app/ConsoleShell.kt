package com.hermesconsole.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.ScrollState
import com.hermesconsole.app.artifacts.StreamingMarkdown
import com.hermesconsole.app.tools.ToolLine
import com.hermesconsole.app.tools.ToolLineRow
import com.hermesconsole.hermesclient.HermesMessage
import com.hermesconsole.lcars.LcarsBar
import com.hermesconsole.lcars.LcarsColors
import com.hermesconsole.lcars.LcarsCorner
import com.hermesconsole.lcars.LcarsElbow
import com.hermesconsole.lcars.LcarsMetrics
import com.hermesconsole.lcars.LcarsPill
import com.hermesconsole.lcars.LcarsRail
import com.hermesconsole.lcars.LcarsSegment
import com.hermesconsole.lcars.LcarsTheme
import kotlinx.coroutines.launch

/**
 * Does the attract state own the wall, or is there a conversation standing on it (T-15, REQ-07)?
 *
 * "The ready screen is shown" is the acceptance of half this project's requirements, and it is not
 * one owner's decision: the store contributes [backlog] and the voice loop contributes [state],
 * [turn] and [spoken], and the wall is only handed back when all four agree. That is exactly the
 * shape that let the M-02 wall run fail with 150 green tests behind it - the store emptied, nothing
 * else did, and the condition lived as an inline expression inside a composable where no JVM test
 * could reach it. So it lives out here now, called verbatim by the shell, and a visit ending is
 * something a test can assert rather than something a person has to watch happen.
 *
 * An entered session holds the wall even with nothing on it yet ([backlog] non-null, and empty): a
 * fresh conversation opens to its own empty pane rather than back into the attract loop (T-43), and
 * so does an accumulated one (T-44) - a wake retiring the finished turn must never let the attract
 * state flash through the beat where the new turn is still blank.
 */
internal fun attractHolds(
    state: ConsoleState,
    turn: TurnLog,
    backlog: List<HermesMessage>?,
    spoken: List<SpokenTurn>,
): Boolean =
    state == ConsoleState.READY && turn.prompt.isBlank() && turn.reply.isBlank() &&
        backlog == null && spoken.isEmpty()

/**
 * Whether the jump-to-latest control is on the wall (T-56, REQ-38; gated by T-15, REQ-17),
 * decided off-screen where a JVM test can hold it: the content overflows its viewport, the
 * reader is up off the end - and the wall is a conversation, because the control is the
 * conversation's affordance and the System Access screen is never "behind" on anything.
 */
internal fun jumpControlShows(overflowing: Boolean, atBottom: Boolean, attract: Boolean): Boolean =
    overflowing && !atBottom && !attract

/**
 * The rail, decided off-screen (T-02, REQ-04): which segments it carries, what they are labeled,
 * and which of them are live - held out here so a JVM test can read the rail the wall renders.
 *
 * No segment is labeled comms. In its slot stands system access, the way home: during a
 * conversation ([attract] false) its tap is [onSystemAccess] - the composition root's endVisit,
 * the same departure the sessions segment drives - and the System Access screen then shows
 * because the attract state holds. On the System Access screen itself the segment is inert
 * chrome: there is no visit to end, and a no-op is no onClick at all.
 *
 * The sessions segment splits on the same state (T-09, REQ-02): from home its tap is
 * [onOpenSessions] - the list now lives on its own full-screen pane, and home has no visit to
 * end - while mid-conversation it stays the departure T-21 made it ([onSessions]); presenting
 * the list after that departure is T-10's step, not this one's.
 */
internal fun railSegments(
    attract: Boolean,
    onSystemAccess: (() -> Unit)?,
    onSessions: (() -> Unit)?,
    onConfigure: (() -> Unit)?,
    onApps: (() -> Unit)?,
    onOpenSessions: (() -> Unit)? = null,
): List<LcarsSegment> =
    listOf(
        LcarsSegment("system access", LcarsColors.Lilac, weight = 1.4f, onClick = if (attract) null else onSystemAccess),
        // From home, the list (T-09); mid-conversation, the departure (T-21, REQ-18): the tap
        // ends the visit at the root, and the main screen shows because the attract state then
        // holds - never a browse summoned over a conversation still on the wall.
        LcarsSegment("sessions", LcarsColors.IceBlue, onClick = if (attract) onOpenSessions else onSessions),
        LcarsSegment(null, LcarsColors.Bluey, weight = 0.5f),
        LcarsSegment("config", LcarsColors.Butterscotch, onClick = onConfigure),
        // The console's own launcher (T-53, REQ-42): it is HOME, so the drawer off this segment
        // is the tablet's only touch path to its other apps.
        LcarsSegment("apps", LcarsColors.AfricanViolet, onClick = onApps),
        LcarsSegment(null, LcarsColors.AlmondCreme, weight = 0.4f),
    )

/**
 * The mid-conversation sessions tap (T-10, REQ-03): the departure T-21 made it, and then the
 * list. Order is the contract - the visit ends first (loop back to READY, TTS stopped, the one
 * full stop out loud), so the sessions screen always presents over the attract state and never
 * over somebody's live conversation.
 */
internal fun sessionsDeparture(endVisit: () -> Unit, present: () -> Unit): () -> Unit = {
    endVisit()
    present()
}

/**
 * The top bar's run of blocks (T-02, REQ-01), decided off-screen so a JVM test can read the carved
 * battery segment the wall renders. The blank Bluey filler is split - a shortened filler plus the
 * battery segment - so the segment stands beside the voice-loop block without a labeled control
 * giving up its place, and the two weights still sum to the filler's old 0.5. The segment is chrome:
 * it wears the battery's condition colour and reads its label off [battery] (the T-01 mapping), and
 * shows the honest placeholder when [battery] is null. It has no content or screen input, so it
 * reads identically on every screen the shell wraps (REQ-02).
 */
internal fun topBarSegments(battery: BatteryState?): List<LcarsSegment> =
    listOf(
        LcarsSegment(null, LcarsColors.Bluey, weight = 0.3f),
        LcarsSegment(batteryLabel(battery), batteryColor(battery), weight = 0.2f),
        LcarsSegment("voice loop", LcarsColors.AlmondCreme, weight = 0.35f),
    )

/**
 * One pane of turn content, stacked vertically in the shell's content column (REQ-38).
 *
 * This is the socket the artifact router plugs into (T-24): the shell knows how to stack and title
 * a pane, and nothing about what is inside one - so a new artifact type is a new pane producer,
 * with zero lines changed here.
 */
data class ConsolePane(
    val title: String,
    val content: @Composable () -> Unit,
)

/**
 * The console shell (T-39): the landscape 1920x1200 frame everything else renders inside.
 *
 * LCARS chrome around one scrolling content column. The chrome is load-bearing in exactly one way:
 * the voice-loop state is part of the frame, not of the content (REQ-35) - the [StateIndicator]
 * pill and the elbows that echo its colour sit outside the scroll region, so no amount of content
 * can push what the console is doing off the screen.
 *
 * Everything is laid out relative to the box it is given - weights and fills, no fixed width
 * anywhere but the kit's own rail metric - so the layout carries no portrait assumption to
 * violate (REQ-38); landscape is enforced where it belongs, in the manifest.
 */
@Composable
fun ConsoleShell(
    state: ConsoleState,
    turn: TurnLog,
    modifier: Modifier = Modifier,
    panes: List<ConsolePane> = emptyList(),
    /**
     * The entered session's stored spoken history (T-43): rendered above the live turn, in the
     * same voice. Null while no session has been entered - the attract state owns the wall -
     * and empty for a session with nothing to restore, whose pane stands open on purpose.
     */
    backlog: List<HermesMessage>? = null,
    /**
     * The live session's finished exchanges (T-44): rendered between the back-log and the live
     * turn, so a wake mid-session stacks a new turn under the conversation instead of wiping it.
     */
    spoken: List<SpokenTurn> = emptyList(),
    /**
     * The current session's tool lines, grouped by the run that produced them (T-10): the shell
     * weaves each run's lines back into that run's reply at the offset each fired. Empty renders the
     * conversation as pure prose.
     */
    toolLinesByRun: Map<String, List<ToolLine>> = emptyMap(),
    /** Supplied by tests that need to drive the scroll; everyone else gets their own. */
    contentScroll: ScrollState? = null,
    /** Opens the settings screen (T-23). Null leaves the rail's config segment inert chrome. */
    onConfigure: (() -> Unit)? = null,
    /** Opens the app drawer (T-53, REQ-42). Null leaves the rail's apps segment inert chrome. */
    onApps: (() -> Unit)? = null,
    /**
     * The rail's system access segment (T-02, REQ-04), standing where comms was: the way home.
     * During a conversation the tap ends the visit - the composition root wires this to the same
     * endVisit the idle return fires - and the System Access screen shows because the attract
     * state then holds. On the System Access screen the shell renders the segment inert: there
     * is no visit to end. Null leaves it inert chrome everywhere, as on an unconfigured shell.
     */
    onSystemAccess: (() -> Unit)? = null,
    /**
     * The rail's sessions segment (T-21, REQ-18): a departure, not a display toggle. Leaving for
     * the main screen ends the visit - the composition root wires this to the same endVisit the
     * idle return fires, so the store empties, the wall comes back, and the room hears the one
     * full stop. The main screen then shows because the attract state genuinely holds, not
     * because a browse was summoned over somebody's conversation; continuing the left session
     * means selecting it again from the list. Null leaves the segment inert chrome.
     */
    onSessions: (() -> Unit)? = null,
    /**
     * The sessions segment's other half (T-09, REQ-02): from the attract state the tap opens the
     * sessions screen - the composition root presents its full-screen pane, as it does the app
     * drawer - since home has no visit to end. Null leaves the segment inert on home, exactly
     * as an unconfigured console with no list to offer should.
     */
    onOpenSessions: (() -> Unit)? = null,
    /**
     * The ready (attract) state's content (T-22, REQ-23): sessions and backend health. Shown in
     * the content column while the attract state holds the wall - which, since T-21, is the only
     * time it shows: the sessions segment departs to it rather than summoning it over a turn.
     * Entering a session needs no callback back into the shell - the tap moves the store, and
     * the wall follows the store. Null leaves the attract state saying STANDING BY, exactly as
     * an unconfigured console should.
     */
    ready: (@Composable () -> Unit)? = null,
    /**
     * The approval pane's slot (T-27, REQ-14): rendered pinned at the top of the content area,
     * beside the rail (REQ-09 - the rail runs unbroken) and outside the scroll region - a
     * stalled run's question must never be pushed off screen by the reply it is stalling. The
     * slot renders nothing while nothing pends; null is a shell with no approval path wired.
     */
    approval: (@Composable () -> Unit)? = null,
    /**
     * The battery reading the top-bar segment renders (T-02, REQ-01): level/scale/charging off the
     * platform source, hoisted in the composition. Null is "no reading yet / unavailable" - the
     * honest placeholder. The shell holds no battery source of its own (REQ-06); it renders what it
     * is given, exactly as it does the two backend healths.
     */
    battery: BatteryState? = null,
    /** Screensaver up (T-21, REQ-22)? The chrome is not rendered dimmed - it is not rendered. */
    idle: Boolean = false,
    /**
     * Told about every press on the console, observed before the children so nothing a segment
     * consumes is missed: on the live shell each one buys another idle timeout, and from the dark
     * the same press is the restore.
     */
    onInteraction: (() -> Unit)? = null,
) {
    LcarsTheme {
        Box(modifier = modifier.fillMaxSize().observePresses(onInteraction)) {
            if (idle) {
                IdleScreen()
            } else {
                LiveShell(
                    state, turn, panes, backlog, spoken, toolLinesByRun, contentScroll,
                    onConfigure, onApps, onSystemAccess, onSessions, onOpenSessions, ready, approval, battery,
                )
            }
        }
    }
}

/** The shell as it was before it learned to sleep: the chrome, the rail and the content column. */
@Composable
private fun LiveShell(
    state: ConsoleState,
    turn: TurnLog,
    panes: List<ConsolePane>,
    backlog: List<HermesMessage>?,
    spoken: List<SpokenTurn>,
    toolLinesByRun: Map<String, List<ToolLine>>,
    contentScroll: ScrollState?,
    onConfigure: (() -> Unit)?,
    onApps: (() -> Unit)?,
    onSystemAccess: (() -> Unit)?,
    onSessions: (() -> Unit)?,
    onOpenSessions: (() -> Unit)?,
    ready: (@Composable () -> Unit)?,
    approval: (@Composable () -> Unit)?,
    battery: BatteryState?,
) {
    val scroll = contentScroll ?: rememberScrollState()
    val scope = rememberCoroutineScope()

    // Whose wall it is (REQ-07), decided out where a test can drive it: see [attractHolds].
    val attract = attractHolds(state, turn, backlog, spoken)
    // The follow effect and the jump gate outlive recompositions; this is the attract value
    // they read live.
    val attractNow by rememberUpdatedState(attract)

    // The jump-to-latest control shows exactly while a conversation's content overflows and the
    // reader has scrolled up off the end (REQ-38, REQ-17): see [jumpControlShows]. BottomSlop
    // swallows the sub-pixel rounding at the extreme, so a fully-scrolled column still reads as
    // at-bottom and the control stays hidden.
    val showJump by remember {
        derivedStateOf {
            jumpControlShows(
                overflowing = scroll.maxValue > BottomSlop,
                atBottom = scroll.value >= scroll.maxValue - BottomSlop,
                attract = attractNow,
            )
        }
    }

    // Follow the output (REQ-38): each time the content grows past the viewport while the reader is
    // already at the end, ride the new bottom down so the newest line stays in view. maxValue is the
    // signal - a stream delta or a fresh pane bumps it, not the scroll position - compared against
    // the *previous* max because value has not moved yet when the growth lands. A reader who has
    // scrolled up is not at the old bottom, so their place is left exactly where they put it.
    //
    // Never on the main screen (T-26, REQ-20): following the stream is the conversation's
    // behaviour. The ready content loads async - the session list landing grows maxValue - and
    // an entry column has prevMax 0, so the at-the-bottom guard is vacuously true and the wall
    // run watched the follow drag the main screen to its bottom on entry. prevMax still tracks
    // through the attract state, so a conversation re-entered measures growth from a live base.
    LaunchedEffect(scroll) {
        var prevMax = scroll.maxValue
        snapshotFlow { scroll.maxValue }.collect { newMax ->
            if (!attractNow && newMax > prevMax && scroll.value >= prevMax - BottomSlop) scroll.scrollTo(newMax)
            prevMax = newMax
        }
    }

    // The main screen enters from the top (T-22, REQ-20): the follow parked the column at the
    // departed conversation's bottom, and what a person arrives for - the link status, the newest
    // sessions - is the top of the ready content. Reset on the transition into attract, and only
    // there: the conversation's own follow rides maxValue growth and never passes through here,
    // so a reader mid-stream is not yanked anywhere by this.
    LaunchedEffect(attract) { if (attract) scroll.scrollTo(0) }
    Column(
        modifier = Modifier.fillMaxSize().background(LcarsColors.Space).padding(LcarsMetrics.Gap * 2),
        verticalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap),
    ) {
        TopFrame(state, battery)
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap),
        ) {
            LcarsRail(
                segments = railSegments(attract, onSystemAccess, onSessions, onConfigure, onApps, onOpenSessions),
                modifier = Modifier.fillMaxHeight(),
                capBottom = false,
            )
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap),
            ) {
                // The approval slot (T-27, REQ-14; placed by T-06, REQ-09): in the content area
                // so the rail runs unbroken, and above the scrolling column so no amount of
                // reply can push a stalled run's question off the screen.
                approval?.invoke()
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll)
                            .padding(start = LcarsMetrics.Gap * 5, top = LcarsMetrics.Gap * 3, end = LcarsMetrics.Gap * 3),
                        verticalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap * 6),
                    ) {
                        if (ready != null && attract) {
                            ready()
                        } else {
                            ConversationPane(backlog.orEmpty(), spoken, turn, toolLinesByRun)
                            panes.forEach { Pane(it) }
                        }
                        Spacer(Modifier.height(LcarsMetrics.Gap * 2))
                    }
                    // The touch path back to the newest output once the reader has scrolled up to
                    // read back (REQ-38). It confirms itself like every control (REQ-43) for free:
                    // an LcarsPill sounds the touch chirp through the shell's cue path by
                    // construction.
                    if (showJump) {
                        LcarsPill(
                            text = "jump to latest",
                            color = LcarsColors.IceBlue,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = LcarsMetrics.Gap * 3, bottom = LcarsMetrics.Gap * 3),
                            onClick = { scope.launch { scroll.animateScrollTo(scroll.maxValue) } },
                        )
                    }
                }
            }
        }
        BottomFrame(state)
    }
}

/** The frame's head: the elbow, the top bar, the state pill at its end, and the title on space. */
@Composable
private fun TopFrame(state: ConsoleState, battery: BatteryState?) {
    Row(
        modifier = Modifier.fillMaxWidth().height(ElbowHeadHeight),
        horizontalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap),
    ) {
        // The elbow wears the state's colour: the state is readable off the chrome itself from
        // across a room, before the pill's label can be.
        LcarsElbow(
            modifier = Modifier.size(ElbowWidth, ElbowHeadHeight),
            corner = LcarsCorner.TopStart,
            color = state.lcarsColor,
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth().height(LcarsMetrics.BarHeight),
                horizontalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap),
            ) {
                LcarsBar(
                    segments = topBarSegments(battery),
                    modifier = Modifier.weight(1f),
                    capEnd = false,
                )
                StateIndicator(state)
            }
            Row(modifier = Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.Bottom) {
                Spacer(Modifier.weight(1f))
                BasicText(
                    text = "HERMES CONSOLE",
                    style = LcarsTheme.typography.title.copy(color = LcarsColors.OnSpace),
                )
            }
        }
    }
}

/** The frame's foot: the bottom elbow closing the rail, and the bar running out to the edge. */
@Composable
private fun BottomFrame(state: ConsoleState) {
    Row(
        modifier = Modifier.fillMaxWidth().height(ElbowFootHeight),
        horizontalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap),
    ) {
        LcarsElbow(
            modifier = Modifier.size(ElbowWidth, ElbowFootHeight),
            corner = LcarsCorner.BottomStart,
            color = state.lcarsColor,
        )
        Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomStart) {
            LcarsBar(
                segments = listOf(
                    LcarsSegment(null, LcarsColors.Mars, weight = 0.18f),
                    LcarsSegment(null, LcarsColors.Bluey, weight = 0.5f),
                    LcarsSegment("lcars 47174", LcarsColors.GoldenTanoi, weight = 0.32f),
                ),
                modifier = Modifier.fillMaxWidth(),
                capEnd = true,
            )
        }
    }
}

/**
 * The conversation, as prose: the entered session's restored back-log (T-43), the exchanges
 * finished since entry (T-44), then the live turn - what was asked, and the reply accumulating
 * under it as the deltas land. All three speak in the same voice ([SpokenLine]), so a restored
 * exchange, a finished one and the one happening now read as one conversation. An unengaged
 * console says so instead of showing an empty pane - READY has to look deliberate, not broken
 * (REQ-35).
 */
@Composable
private fun ConversationPane(
    backlog: List<HermesMessage>,
    spoken: List<SpokenTurn>,
    turn: TurnLog,
    toolLinesByRun: Map<String, List<ToolLine>>,
) {
    val liveTools = turn.runId?.let { toolLinesByRun[it] }.orEmpty()
    if (backlog.isEmpty() && spoken.isEmpty() && turn.prompt.isBlank() && turn.reply.isBlank() &&
        liveTools.isEmpty()
    ) {
        BasicText(
            text = "STANDING BY",
            style = LcarsTheme.typography.readout.copy(color = LcarsColors.Gray),
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap * 2)) {
        // The restored back-log is prose only - session history carries no runIds and no tools.
        backlog.forEach { line -> SpokenLine(role = line.role, text = line.content) }
        // Each finished turn: the query, then its reply with the turn's tool lines woven back in.
        spoken.forEach { spoken ->
            if (spoken.prompt.isNotBlank()) SpokenLine(role = "user", text = spoken.prompt)
            val tools = spoken.runId?.let { toolLinesByRun[it] }.orEmpty()
            if (spoken.reply.isNotBlank() || tools.isNotEmpty()) AssistantReply(spoken.reply, tools)
        }
        // The live turn, its tools landing in place as they fire.
        if (turn.prompt.isNotBlank()) SpokenLine(role = "user", text = turn.prompt)
        if (turn.reply.isNotBlank() || liveTools.isNotEmpty()) AssistantReply(turn.reply, liveTools)
    }
}

/**
 * The assistant's turn (T-10): the RESPONSE heading, then the reply with its tool lines woven in at
 * the point each fired. With no tools it is exactly the streaming markdown reply as before; with
 * tools, the reply is split at each line's [ToolLine.replyOffset] and the compact `> <tool>` row
 * ([ToolLineRow]) sits between the segments - so a tool reads where it ran, not bumped to the bottom.
 */
@Composable
private fun AssistantReply(reply: String, tools: List<ToolLine>) {
    BasicText("RESPONSE", style = LcarsTheme.typography.header.copy(color = LcarsColors.Orange))
    if (tools.isEmpty()) {
        StreamingMarkdown(reply)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap * 2)) {
        var pos = 0
        tools.sortedBy { it.replyOffset }.forEach { line ->
            val cut = line.replyOffset.coerceIn(pos, reply.length)
            val segment = reply.substring(pos, cut)
            if (segment.isNotBlank()) StreamingMarkdown(segment)
            ToolLineRow(line)
            pos = cut
        }
        val tail = reply.substring(pos.coerceAtMost(reply.length))
        if (tail.isNotBlank()) StreamingMarkdown(tail)
    }
}

/**
 * One spoken line, restored or live: a QUERY is the person's, everything else is the console's.
 *
 * A query is a transcript and renders as the words it was; a response is the model writing and
 * renders as markdown (REQ-16). The live reply goes through the same [StreamingMarkdown] as every
 * finished one - for the deltas it is the incremental path REQ-18 demands, blocks sealing as they
 * complete; for a restored line it is simply a parse-once renderer.
 */
@Composable
private fun SpokenLine(role: String, text: String) {
    if (role == "user") {
        BasicText("QUERY", style = LcarsTheme.typography.header.copy(color = LcarsColors.IceBlue))
        BasicText(text, style = LcarsTheme.typography.body.copy(color = LcarsColors.AlmondCreme))
    } else {
        BasicText("RESPONSE", style = LcarsTheme.typography.header.copy(color = LcarsColors.Orange))
        StreamingMarkdown(text)
    }
}

/**
 * One stacked pane: a block in the content column, headed by its title. Full width, never wider
 * (REQ-38). A blank title draws no heading at all - a pane that reads as part of the session output
 * rather than a titled section (the tool-activity strip hosts this way).
 */
@Composable
private fun Pane(pane: ConsolePane) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap * 2),
    ) {
        if (pane.title.isNotBlank()) {
            BasicText(pane.title, style = LcarsTheme.typography.header.copy(color = LcarsColors.Lilac))
        }
        pane.content()
    }
}

/**
 * See every press without taking any (T-21): observed on the Initial pass, ahead of the children,
 * and never consumed - a tap on the config segment still opens settings, it just also resets the
 * idle countdown on its way past. Watching Press rather than gestures is what makes restoration
 * instant: the screen is back on the finger's way down, not on its way up.
 */
private fun Modifier.observePresses(onPress: (() -> Unit)?): Modifier =
    if (onPress == null) this
    else pointerInput(onPress) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press) onPress()
            }
        }
    }

/** The at-bottom tolerance in px: a fully-scrolled column can settle a pixel or two shy of maxValue. */
private const val BottomSlop = 4

/** The head elbow's box: one bar plus room to turn; its width is what the rail hangs from. */
private val ElbowWidth = LcarsMetrics.RailWidth + 96.dp
private val ElbowHeadHeight = LcarsMetrics.BarHeight + 72.dp
private val ElbowFootHeight = LcarsMetrics.BarHeight + 24.dp
