package com.hermesconsole.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The platform battery extras, mapped to a [BatteryState] (T-03, REQ-06). Charging is power coming
 * in - the status is charging or full, or something is plugged in - so the marker means "on the
 * wire" and its absence is the silent discharge the indicator exists to show. Pure and Android-free
 * at runtime (only int constants), so a JVM test holds it.
 */
fun batteryStateFrom(level: Int, scale: Int, status: Int, plugged: Int): BatteryState =
    BatteryState(
        level = level,
        scale = scale,
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            plugged != 0,
    )

/**
 * The tablet's charge, live (T-03, REQ-06/07). ACTION_BATTERY_CHANGED is a sticky broadcast:
 * registering a receiver both hands back the current reading at once and delivers every change
 * after, so the source has a value from the first frame and follows the battery without polling.
 * The device glue is thin - each intent's extras go through [batteryStateFrom] - and the result is
 * published on a StateFlow the composition collects; the stateless shell only ever sees the value.
 * A malformed intent (no level, or a non-positive scale) is no reading: the flow holds null and the
 * segment shows its honest placeholder (REQ-08).
 */
class BatterySource(private val context: Context) {

    private val _state = MutableStateFlow<BatteryState?>(null)
    val state: StateFlow<BatteryState?> = _state.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            _state.value = intent?.toBatteryState()
        }
    }

    /** Register for the sticky broadcast; its returned intent seeds the first reading at once. */
    fun start() {
        val sticky = context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        _state.value = sticky?.toBatteryState()
    }

    fun stop() = context.unregisterReceiver(receiver)
}

private fun Intent.toBatteryState(): BatteryState? {
    val level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (level < 0 || scale <= 0) return null
    return batteryStateFrom(
        level = level,
        scale = scale,
        status = getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN),
        plugged = getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
    )
}
