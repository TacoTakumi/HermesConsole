package com.hermesconsole.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hermesconsole.hermesclient.HermesSession
import com.hermesconsole.lcars.LcarsBar
import com.hermesconsole.lcars.LcarsCap
import com.hermesconsole.lcars.LcarsColors
import com.hermesconsole.lcars.LcarsMetrics
import com.hermesconsole.lcars.LcarsPill
import com.hermesconsole.lcars.LcarsSegment
import com.hermesconsole.lcars.LcarsTheme
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The sessions screen (T-09, REQ-02): the stored-sessions list on its own full-screen pane, on
 * the app drawer's pattern - opened by the rail's sessions segment, dismissed by CLOSE or the
 * device back, straight back to the shell and the System Access screen holding it.
 *
 * T-01 retired the ReadyScreen that used to render these rows in the attract state; this screen
 * is where the list lives now. Same row model, same moves: pick a conversation up, or start a
 * fresh one - every tap [onSelect]s or [onCreate]s and the caller dismisses the pane, because
 * entering a session is leaving this screen.
 */
@Composable
fun SessionsScreen(
    sessions: List<HermesSession>,
    currentSessionId: String?,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    onClose: () -> Unit,
) {
    // Device back dismisses the pane in place, exactly as CLOSE does - the console is HOME (the
    // task root), so the system's default back would recreate the whole activity just to shut a
    // list (the drawer learned this first, REQ-42).
    BackHandler { onClose() }
    LcarsTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LcarsColors.Space)
                .padding(LcarsMetrics.Gap * 2),
            verticalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap * 2),
        ) {
            LcarsBar(
                segments = listOf(
                    LcarsSegment("sessions", LcarsColors.IceBlue, weight = 0.25f),
                    LcarsSegment(null, LcarsColors.Bluey, weight = 0.5f),
                    LcarsSegment(
                        // The stored count, live off the same poll the lamps ride: proof the
                        // list is fresh, in the drawer's own idiom.
                        if (sessions.isEmpty()) "no stored sessions" else "${sessions.size} stored",
                        LcarsColors.Lilac,
                        weight = 0.25f,
                    ),
                ),
                modifier = Modifier.fillMaxWidth(),
                capStart = true,
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap * 2),
            ) {
                sessionRows(sessions, currentSessionId).forEach { row ->
                    SessionRow(
                        label = row.label,
                        color = when {
                            row.sessionId == null -> LcarsColors.Orange
                            row.current -> LcarsColors.IceBlue
                            else -> LcarsColors.Butterscotch
                        },
                        detail = row.detail,
                        onClick = { row.sessionId?.let(onSelect) ?: onCreate() },
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                LcarsPill(text = "close", color = LcarsColors.Bluey, onClick = onClose)
            }
        }
    }
}

/**
 * One row of the sessions screen's list, as data: what the pill says, what the readout says,
 * and where a tap goes. [sessionId] null is the create row - the one row that opens a fresh
 * conversation instead of entering a stored one.
 */
data class SessionRowModel(
    val label: String,
    val detail: String,
    val current: Boolean,
    val sessionId: String?,
)

/**
 * A row's display name (T-11, REQ-11), one rule for every row anywhere: the session's title when
 * one is set, otherwise a date/time label off when it was last alive - the same fixed-locale
 * shape console-created titles carry (T-12), so a named and an unnamed session read as one list.
 * A session never spoken in has no time to show and says so in words. A raw session id never
 * renders: `api_9f3` is an address, not a name.
 */
fun sessionDisplayName(session: HermesSession, zone: ZoneId = ZoneId.systemDefault()): String =
    session.title?.takeIf { it.isNotBlank() }
        ?: session.lastActive?.let { epoch ->
            Instant.ofEpochSecond(epoch.toLong()).atZone(zone).format(SESSION_LABEL_FORMAT)
        }
        ?: "untitled session"

/**
 * The session list, decided off-screen (T-04, REQ-02): one create row, then one row per stored
 * session - and nothing else. The unscoped row is gone with the mode it offered; every row here
 * leads to a scoped conversation, which is what lets the loop's provider be total (REQ-03).
 */
fun sessionRows(
    sessions: List<HermesSession>,
    currentSessionId: String?,
    zone: ZoneId = ZoneId.systemDefault(),
): List<SessionRowModel> =
    listOf(
        SessionRowModel(
            label = "new session",
            detail = "open a fresh conversation",
            current = false,
            sessionId = null,
        ),
    ) + sessions.map { session ->
        val current = session.id == currentSessionId
        SessionRowModel(
            label = sessionDisplayName(session, zone),
            detail = listOfNotNull(
                if (current) "speaking here" else null,
                "${session.messageCount} lines",
                session.preview?.takeIf { it.isNotBlank() },
            ).joinToString("  ·  "),
            current = current,
            sessionId = session.id,
        )
    }

/**
 * A console-made session's title (T-12, REQ-14): the moment of creation, in the very shape
 * [sessionDisplayName] labels untitled rows with - so a session titled at birth and one named
 * by its last activity read as one list, and any client browsing the sessions API sees when a
 * wall conversation happened without knowing the console's naming rule.
 */
fun sessionCreationTitle(now: LocalDateTime): String = now.format(SESSION_LABEL_FORMAT)

/** The date/time label's fixed-locale shape, shared by row labels and creation titles (T-11/T-12). */
private val SESSION_LABEL_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d yyyy HH:mm", Locale.US)

/** One session row: the tappable pill that is the switch, and the readout that says what it is. */
@Composable
private fun SessionRow(label: String, color: Color, detail: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap * 3),
    ) {
        LcarsPill(
            text = label,
            color = color,
            cap = LcarsCap.Both,
            minWidth = 320.dp,
            modifier = Modifier.width(320.dp),
            onClick = onClick,
        )
        BasicText(
            text = detail.uppercase(),
            style = LcarsTheme.typography.readout.copy(color = LcarsColors.OnSpace),
        )
    }
}
