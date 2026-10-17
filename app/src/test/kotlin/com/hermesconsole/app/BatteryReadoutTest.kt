package com.hermesconsole.app

import android.os.BatteryManager
import com.hermesconsole.lcars.LcarsColors
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The battery readout's mapping (T-01, REQ-03/04/05/08), pinned where a JVM test can hold it -
 * the frame chrome only renders what these functions return. A charge state is level+scale+charging
 * off the platform broadcast; the derived readout is the percent text, the condition colour, and
 * whether a charge marker rides along. Null charge is "no reading" - the honest placeholder, never
 * a fabricated percent.
 */
class BatteryReadoutTest {

    private fun charge(percent: Int, charging: Boolean = false) =
        BatteryState(level = percent, scale = 100, charging = charging)

    @Test
    fun `percent reads as a BAT label off level and scale`() {
        // REQ-03: the exact strings the requirement fixes, discharging so no marker rides along.
        assertEquals("BAT 87%", batteryLabel(charge(87)))
        assertEquals("BAT 100%", batteryLabel(charge(100)))
        assertEquals("BAT 5%", batteryLabel(charge(5)))
    }

    @Test
    fun `percent rounds and clamps`() {
        // Rounds to the nearest whole percent (666/1000 = 66.6 -> 67), and never reads past 100
        // even if the platform hands back a level above scale.
        assertEquals("BAT 67%", batteryLabel(BatteryState(level = 666, scale = 1000, charging = false)))
        assertEquals("BAT 100%", batteryLabel(BatteryState(level = 105, scale = 100, charging = false)))
    }

    @Test
    fun `colour is the condition register by level`() {
        // REQ-05: >=30 nominal, 20-29 yellow, <20 red, with both boundaries pinned.
        assertEquals(LcarsColors.GoldenTanoi, batteryColor(charge(100)))
        assertEquals(LcarsColors.GoldenTanoi, batteryColor(charge(30)))
        assertEquals(LcarsColors.Sunflower, batteryColor(charge(29)))
        assertEquals(LcarsColors.Sunflower, batteryColor(charge(20)))
        assertEquals(LcarsColors.Tomato, batteryColor(charge(19)))
        assertEquals(LcarsColors.Tomato, batteryColor(charge(0)))
    }

    @Test
    fun `charging is distinguishable from discharging at the same percent`() {
        // REQ-04: the marker rides the label only while charging, so the two renders differ.
        val charging = batteryLabel(charge(87, charging = true))
        val discharging = batteryLabel(charge(87, charging = false))
        assertNotEquals(charging, discharging)
        assertTrue(charging.contains(CHARGE_MARKER), "charging label carries no marker: $charging")
        assertFalse(discharging.contains(CHARGE_MARKER), "discharging label carries a marker: $discharging")
    }

    @Test
    fun `no reading shows an honest placeholder, never a fabricated percent`() {
        // REQ-08: null charge -> "BAT --" in inert Gray, with no digits and no marker.
        assertEquals("BAT --", batteryLabel(null))
        assertEquals(LcarsColors.Gray, batteryColor(null))
        assertFalse(batteryLabel(null).any { it.isDigit() }, "placeholder shows a digit")
        assertFalse(batteryLabel(null).contains(CHARGE_MARKER), "placeholder shows a charge marker")
    }

    // -- T-03 / REQ-06, REQ-07: the platform extras -> state -> live segment ---------------------

    @Test
    fun `platform extras map to a battery state - charging is power coming in`() {
        // REQ-06: status CHARGING or FULL, or anything plugged, reads as charging; on battery it
        // does not - the marker means "on the wire", its absence the silent discharge.
        assertTrue(batteryStateFrom(50, 100, BatteryManager.BATTERY_STATUS_CHARGING, 0).charging)
        assertTrue(batteryStateFrom(100, 100, BatteryManager.BATTERY_STATUS_FULL, 0).charging)
        assertTrue(
            batteryStateFrom(80, 100, BatteryManager.BATTERY_STATUS_UNKNOWN, BatteryManager.BATTERY_PLUGGED_USB).charging,
            "plugged in but status unknown still reads as on the wire",
        )
        assertFalse(batteryStateFrom(80, 100, BatteryManager.BATTERY_STATUS_DISCHARGING, 0).charging)
        assertEquals(50, batteryPercent(batteryStateFrom(50, 100, BatteryManager.BATTERY_STATUS_DISCHARGING, 0)))
    }

    @Test
    fun `the rendered segment follows the source as its state changes`() {
        // REQ-07: the top-bar segment reads whatever the source's StateFlow currently holds, so a
        // change in charge is on the wall the next recomposition. Null is the honest placeholder.
        val source = MutableStateFlow<BatteryState?>(null)
        fun segment() = topBarSegments(source.value).single { it.label?.startsWith("BAT") == true }

        assertEquals("BAT --", segment().label)
        assertEquals(LcarsColors.Gray, segment().color)

        source.value = batteryStateFrom(15, 100, BatteryManager.BATTERY_STATUS_DISCHARGING, 0)
        assertEquals("BAT 15%", segment().label)
        assertEquals(LcarsColors.Tomato, segment().color)

        source.value = batteryStateFrom(90, 100, BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_PLUGGED_AC)
        assertEquals("BAT 90% $CHARGE_MARKER", segment().label)
        assertEquals(LcarsColors.GoldenTanoi, segment().color)
    }
}
