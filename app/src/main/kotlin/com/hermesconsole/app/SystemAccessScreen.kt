package com.hermesconsole.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hermesconsole.lcars.LcarsCap
import com.hermesconsole.lcars.LcarsColors
import com.hermesconsole.lcars.LcarsMetrics
import com.hermesconsole.lcars.LcarsPill
import com.hermesconsole.lcars.LcarsTheme
import com.hermesconsole.voiceclient.VoiceManagerInfo
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The System Access home screen (T-01, REQ-01; laid out by T-14, REQ-15): what the wall shows
 * outside a session, now that the attract state carries the console's identity instead of a
 * session list. SYSTEM ACCESS, the COM-LINKS lamps, the condition banner across the frame, and
 * one named block per readout REQ-01 lists - profile, model, engines, stardate - in two-column
 * rows, so the whole screen reads at a glance with nothing left to scroll on the wall. The
 * console's name is the shell title's to show, once - never repeated inside the screen.
 *
 * Stateless like the shell around it: the two healths are handed in, nothing is fetched here. The
 * stored-sessions list does not appear on this screen by construction - it has no session
 * parameter to render - the list lives behind the rail's sessions segment from M-02 on.
 */
@Composable
fun SystemAccessScreen(
    hermes: BackendHealth,
    voice: BackendHealth,
    /** VoiceManager's engines and models off the health poll (T-05, REQ-09). Null = unreachable. */
    vmInfo: VoiceManagerInfo? = null,
    /** The gateway's advertised profile off the same poll (T-06, REQ-07). Null = unreachable. */
    profile: String? = null,
    /** The last effective model a completed run reported (T-07, REQ-08). Null = no run yet. */
    model: String? = null,
    /** The clock the stardate block reads. Injectable so a test can fix the day; the wall's is real. */
    clock: () -> LocalDateTime = LocalDateTime::now,
) {
    // Re-read on a cadence comfortably inside the minute REQ-10 allows: the readout's finest
    // hand is the minute, so a tick every ten seconds lands each rollover well within spec
    // without recomposing the screen every second.
    val now by produceState(initialValue = clock(), clock) {
        while (true) {
            delay(CLOCK_TICK_MILLIS)
            value = clock()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap * 3)) {
        BasicText("SYSTEM ACCESS", style = LcarsTheme.typography.title.copy(color = LcarsColors.Orange))

        Spacer(Modifier.height(LcarsMetrics.Gap * 3))

        BasicText("COM-LINKS", style = LcarsTheme.typography.header.copy(color = LcarsColors.IceBlue))
        // One line, whole verdict (REQ-19): the two lamps and then the condition pill (T-03,
        // REQ-06) - the lamps' verdicts condensed into the one word a person reads first - on
        // the same row, the condition at its own bounded width like every pill on the wall.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap * 6),
        ) {
            comLinkLamps(hermes, voice).forEach { HealthLamp(it) }
            condition(hermes, voice).let { c ->
                LcarsPill(text = c.label, color = c.color, cap = LcarsCap.Both, minWidth = 420.dp)
            }
        }

        Spacer(Modifier.height(LcarsMetrics.Gap * 3))

        // The roster in reading order, two blocks to a row (REQ-15): the landscape frame is the
        // screen's to use, and four stacked blocks were a list with a scroll where a glance
        // should do. The roster function stays the single source - this only seats it.
        systemAccessBlocks(now, vmInfo, profile, model).chunked(2).forEach { rowBlocks ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap * 6)) {
                rowBlocks.forEach { block ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap)) {
                        BasicText(block.header, style = LcarsTheme.typography.header.copy(color = LcarsColors.Lilac))
                        BasicText(block.readout, style = LcarsTheme.typography.readout.copy(color = LcarsColors.Gray))
                    }
                }
            }
        }
    }
}

/** One com-link lamp, as data: the backend's name and the verdict its pill wears. */
data class ComLinkLampModel(
    val name: String,
    val label: String,
    val color: Color,
)

/**
 * The COM-LINKS section, decided off-screen (REQ-05): two lamps, hermes then voicemanager, each
 * wearing its own health's colour and verdict - the same mapping the old links section used, kept
 * where a JVM test can hold it.
 */
fun comLinkLamps(hermes: BackendHealth, voice: BackendHealth): List<ComLinkLampModel> =
    listOf(
        ComLinkLampModel("hermes", hermes.lampLabel, hermes.lampColor),
        ComLinkLampModel("voicemanager", voice.lampLabel, voice.lampColor),
    )

/** One home-screen readout block, as data: the header it renders under and what it reads. */
data class SystemAccessBlockModel(
    val header: String,
    val readout: String,
)

/**
 * The home screen's readout roster (REQ-01), in reading order. This list is the plan's scope
 * guard made structural: a task filling one block in cannot silently drop another without
 * breaking a JVM test. Until each block's own task lands, its readout is the one honest
 * placeholder - never a hardcoded value that could pass for a reading. CONDITION is not a
 * roster slot: it graduated to the live banner (T-03), mapped by [condition] above the blocks.
 * STARDATE reads the clock handed in (T-04) - a clock always exists, so it has no no-data state.
 */
fun systemAccessBlocks(
    now: LocalDateTime,
    vmInfo: VoiceManagerInfo? = null,
    /** The gateway's advertised profile name (T-06, REQ-07). Null = unreachable, the placeholder. */
    profile: String? = null,
    /** The last effective model a completed run reported (T-07, REQ-08). Null = no run has. */
    model: String? = null,
): List<SystemAccessBlockModel> =
    listOf(
        SystemAccessBlockModel("PROFILE", profile?.uppercase(Locale.US) ?: NO_DATA),
        SystemAccessBlockModel("MODEL", model?.uppercase(Locale.US) ?: NO_DATA),
        SystemAccessBlockModel("VOICE ENGINES", voiceEnginesReadout(vmInfo)),
        SystemAccessBlockModel("STARDATE", stardateReadout(now)),
    )

/**
 * The VOICE ENGINES block's two lines (T-05, REQ-09): each role's engine and the model loaded
 * behind it, straight off VoiceManager's /info. Null info is an unreachable server - the one
 * honest placeholder, whole - and a null field inside a live payload is NO DATA in its own slot,
 * because an engine the server names with a model it has not loaded is exactly that.
 */
fun voiceEnginesReadout(info: VoiceManagerInfo?): String =
    if (info == null) NO_DATA
    else listOf(
        engineLine("STT", info.sttEngine, info.models.stt),
        engineLine("TTS", info.ttsEngine, info.models.tts),
    ).joinToString("\n")

private fun engineLine(role: String, engine: String?, model: String?): String =
    "$role  ${engine?.uppercase(Locale.US) ?: NO_DATA}  ·  ${model?.uppercase(Locale.US) ?: NO_DATA}"

/**
 * The stardate (T-04, REQ-10), by the thelcars.com convention the open question fixed:
 * (year - 1966) * 1000 + day-of-year, then one decimal of tenths of the day - noon is .5,
 * the last stretch before midnight .9. Deterministic in the local date-time and nothing else.
 */
fun stardate(now: LocalDateTime): String {
    val whole = (now.year - 1966) * 1000 + now.dayOfYear
    val tenths = now.toLocalTime().toSecondOfDay() * 10 / SECONDS_PER_DAY
    return "$whole.$tenths"
}

/**
 * The STARDATE block's one line: stardate, date, time, all off the same clock so they can never
 * disagree. The date is fixed-locale - the wall reads the same in any system language.
 */
fun stardateReadout(now: LocalDateTime): String {
    val date = now.format(DateTimeFormatter.ofPattern("MMM d yyyy", Locale.US)).uppercase(Locale.US)
    val time = now.format(DateTimeFormatter.ofPattern("HH:mm"))
    return "${stardate(now)}  ·  $date  $time"
}

private const val SECONDS_PER_DAY = 86_400

/** How often the screen re-reads its clock: well inside REQ-10's once a minute. */
private const val CLOCK_TICK_MILLIS = 10_000L

/**
 * The four conditions the banner can call (REQ-06), each wearing its own colour. GREEN wears the
 * lamp's own online gold - nominal reads as nominal - and YELLOW the palette's true yellow; the
 * LCARS palette has no green to wear.
 */
enum class Condition(val label: String, val color: Color) {
    GREEN("CONDITION GREEN", LcarsColors.GoldenTanoi),
    YELLOW("YELLOW ALERT", LcarsColors.Sunflower),
    RED("RED ALERT", LcarsColors.Tomato),
    STANDBY("STANDBY", LcarsColors.Gray),
}

/**
 * The condition mapping (T-03, REQ-06), verbatim from the requirement: both OK is GREEN, exactly
 * one FAILED is YELLOW, both FAILED is RED, and no FAILED with any UNKNOWN is STANDBY - so a
 * console still finding out is standing by, never falsely green and never crying alert.
 */
fun condition(hermes: BackendHealth, voice: BackendHealth): Condition {
    val failed = listOf(hermes, voice).count { it == BackendHealth.FAILED }
    return when {
        failed == 2 -> Condition.RED
        failed == 1 -> Condition.YELLOW
        hermes == BackendHealth.OK && voice == BackendHealth.OK -> Condition.GREEN
        else -> Condition.STANDBY
    }
}

/** What an unwired or unreachable readout admits to, instead of faking a value. */
private const val NO_DATA = "NO DATA"

/** One backend's lamp: the name, and a verdict a person reads off the colour from across a room. */
@Composable
private fun HealthLamp(lamp: ComLinkLampModel) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap * 2)) {
        LcarsPill(
            text = lamp.label,
            color = lamp.color,
            cap = LcarsCap.Both,
            minWidth = 150.dp,
        )
        BasicText(lamp.name.uppercase(), style = LcarsTheme.typography.readout.copy(color = LcarsColors.AlmondCreme))
    }
}

private val BackendHealth.lampColor: Color
    get() = when (this) {
        BackendHealth.OK -> LcarsColors.GoldenTanoi
        BackendHealth.FAILED -> LcarsColors.Tomato
        BackendHealth.UNKNOWN -> LcarsColors.Gray
    }

private val BackendHealth.lampLabel: String
    get() = when (this) {
        BackendHealth.OK -> "online"
        BackendHealth.FAILED -> "failed"
        BackendHealth.UNKNOWN -> "checking"
    }
