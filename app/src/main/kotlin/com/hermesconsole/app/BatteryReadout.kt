package com.hermesconsole.app

import androidx.compose.ui.graphics.Color
import com.hermesconsole.lcars.LcarsColors

/**
 * A battery reading as the frame needs it (T-01): the raw platform numbers, nothing derived.
 * [level] and [scale] are ACTION_BATTERY_CHANGED's EXTRA_LEVEL / EXTRA_SCALE (percent =
 * level*100/scale); [charging] is whether the tablet is plugged and taking charge. A null
 * BatteryState is "no reading yet / unavailable" - the honest placeholder, never a fabricated
 * percent (REQ-08).
 */
data class BatteryState(
    val level: Int,
    val scale: Int,
    val charging: Boolean,
)

/** The marker that rides the label while charging (REQ-04): a plus for power coming in. */
const val CHARGE_MARKER = "+"

/**
 * The whole-percent charge (REQ-03): level over scale, rounded to the nearest point and clamped
 * to 0..100 so a platform that reports a level above its scale still reads as full, not 103%.
 * A non-positive scale is malformed; guarded to 1 so the map never divides by zero (the source
 * hands nulls for genuinely absent readings - see [batteryLabel]).
 */
fun batteryPercent(state: BatteryState): Int =
    Math.round(state.level * 100f / state.scale.coerceAtLeast(1)).coerceIn(0, 100)

/**
 * What the segment reads (REQ-03, REQ-04, REQ-08): "BAT NN%" for a live reading, with the charge
 * marker appended only while charging so discharging and charging differ at the same percent; and
 * "BAT --" for no reading, carrying no digit and no marker - a placeholder that cannot be mistaken
 * for a value.
 */
fun batteryLabel(state: BatteryState?): String =
    if (state == null) "BAT --"
    else "BAT ${batteryPercent(state)}%" + if (state.charging) " $CHARGE_MARKER" else ""

/**
 * The segment's colour (REQ-05, REQ-08): the console's condition register by level - nominal gold
 * at 30% and up (the same GoldenTanoi CONDITION GREEN wears), YELLOW below 30, RED below 20 - and
 * inert Gray for no reading, so an absent battery never cries an alarm colour.
 */
fun batteryColor(state: BatteryState?): Color = when {
    state == null -> LcarsColors.Gray
    batteryPercent(state) >= 30 -> LcarsColors.GoldenTanoi
    batteryPercent(state) >= 20 -> LcarsColors.Sunflower
    else -> LcarsColors.Tomato
}
