package com.hermesconsole.app

import com.hermesconsole.lcars.LcarsColors
import com.hermesconsole.voiceclient.VoiceManagerInfo
import com.hermesconsole.voiceclient.VoiceManagerModels
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T-01 / REQ-01, REQ-05: the attract state's home screen, decided off-screen.
 *
 * The claims are structural, in the [sessionRows] tradition: what the wall renders is exactly what
 * these models hold. The lamp list is the COM-LINKS section - two lamps, hermes then voicemanager,
 * each wearing its own health's verdict - and the block roster is REQ-01's named readouts, every
 * one present so a later task filling one in cannot silently drop another (the plan's scope guard,
 * held here rather than at a wall run).
 */
class SystemAccessScreenTest {

    @Test
    fun `two lamps, hermes then voicemanager, each tracking its own backend`() {
        val lamps = comLinkLamps(hermes = BackendHealth.OK, voice = BackendHealth.FAILED)

        assertEquals(listOf("hermes", "voicemanager"), lamps.map { it.name })
        assertEquals("online", lamps[0].label, "an OK backend reads online")
        assertEquals(LcarsColors.GoldenTanoi, lamps[0].color)
        assertEquals("failed", lamps[1].label, "a FAILED backend says so")
        assertEquals(LcarsColors.Tomato, lamps[1].color)
    }

    @Test
    fun `an undetermined backend reads checking, not broken`() {
        val lamps = comLinkLamps(hermes = BackendHealth.UNKNOWN, voice = BackendHealth.UNKNOWN)

        lamps.forEach { lamp ->
            assertEquals("checking", lamp.label)
            assertEquals(LcarsColors.Gray, lamp.color)
        }
    }

    @Test
    fun `every readout block REQ-01 names is on the roster, in reading order`() {
        // CONDITION is not missing: it graduated to the live banner (T-03), whose own mapping
        // tests below stand guard over it the way this roster stands over the readout slots.
        val headers = systemAccessBlocks(NOON).map { it.header }

        assertEquals(
            listOf("PROFILE", "MODEL", "VOICE ENGINES", "STARDATE"),
            headers,
            "the roster is the scope guard: a block dropped here is a block dropped from the wall",
        )
    }

    @Test
    fun `an unfilled block admits it has no data rather than faking a readout`() {
        // Until T-05..T-07 wire the live sources, every slot still waiting shows the one honest
        // placeholder - never a hardcoded value that could pass for a reading. STARDATE is off
        // this list since T-04: a clock always exists, so it has no no-data state to show.
        systemAccessBlocks(NOON).filterNot { it.header == "STARDATE" }.forEach { block ->
            assertEquals("NO DATA", block.readout, "${block.header} fakes a readout")
        }
    }

    // -- T-03 / REQ-06: the condition banner ----------------------------------------------------

    @Test
    fun `all nine health pairs map to their condition - the table is total`() {
        // REQ-06 verbatim: green = both OK; yellow = exactly one FAILED; red = both FAILED;
        // standby = no FAILED with any UNKNOWN. Nine pairs, spelled out rather than derived, so
        // a regression in the mapping cannot hide behind the same shortcut it broke.
        val table = mapOf(
            (BackendHealth.OK to BackendHealth.OK) to Condition.GREEN,
            (BackendHealth.OK to BackendHealth.FAILED) to Condition.YELLOW,
            (BackendHealth.FAILED to BackendHealth.OK) to Condition.YELLOW,
            (BackendHealth.FAILED to BackendHealth.UNKNOWN) to Condition.YELLOW,
            (BackendHealth.UNKNOWN to BackendHealth.FAILED) to Condition.YELLOW,
            (BackendHealth.FAILED to BackendHealth.FAILED) to Condition.RED,
            (BackendHealth.OK to BackendHealth.UNKNOWN) to Condition.STANDBY,
            (BackendHealth.UNKNOWN to BackendHealth.OK) to Condition.STANDBY,
            (BackendHealth.UNKNOWN to BackendHealth.UNKNOWN) to Condition.STANDBY,
        )
        assertEquals(9, table.size, "every combination of two three-state healths is covered")

        table.forEach { (pair, want) ->
            assertEquals(want, condition(pair.first, pair.second), "condition(${pair.first}, ${pair.second})")
        }
    }

    @Test
    fun `the banner says the condition in the words the requirement uses`() {
        assertEquals("CONDITION GREEN", Condition.GREEN.label)
        assertEquals("YELLOW ALERT", Condition.YELLOW.label)
        assertEquals("RED ALERT", Condition.RED.label)
        assertEquals("STANDBY", Condition.STANDBY.label)
    }

    @Test
    fun `each condition wears its own colour - a banner read from across the room`() {
        val colours = Condition.entries.map { it.color }
        assertEquals(colours.size, colours.toSet().size, "two conditions share a colour: $colours")
    }

    // -- T-04 / REQ-10: the stardate and clock block --------------------------------------------

    @Test
    fun `the same instant always gives the same stardate, and different dates differ`() {
        assertEquals(stardate(NOON), stardate(NOON), "a deterministic function of the date, nothing else")
        assertTrue(
            stardate(NOON) != stardate(NOON.plusDays(1)),
            "a day passing must move the stardate",
        )
    }

    @Test
    fun `the published fixture holds - Aug 14 2020 is stardate 54227 point something`() {
        // The thelcars.com convention the open question fixed: (year - 1966) * 1000 + day-of-year,
        // then one decimal of tenths of the day. 2020 - 1966 = 54; Aug 14 is day 227 of leap 2020.
        assertEquals("54227.5", stardate(NOON), "noon is five tenths through the day")
        assertEquals("54227.0", stardate(NOON.toLocalDate().atStartOfDay()))
        assertEquals("54227.9", stardate(NOON.toLocalDate().atTime(23, 59)))
    }

    @Test
    fun `the block reads stardate, date and time off the one fixed clock`() {
        val readout = stardateReadout(NOON.toLocalDate().atTime(9, 30))

        assertEquals("54227.3  ·  AUG 14 2020  09:30", readout)
    }

    @Test
    fun `the stardate block carries the clock's readout, not a placeholder`() {
        val block = systemAccessBlocks(NOON).single { it.header == "STARDATE" }

        assertEquals(stardateReadout(NOON), block.readout)
    }

    // -- T-06 / REQ-07: the profile block -------------------------------------------------------

    @Test
    fun `the profile block reads the advertised name, and the placeholder when the gateway is gone`() {
        assertEquals(
            "TACTICAL",
            systemAccessBlocks(NOON, profile = "tactical").single { it.header == "PROFILE" }.readout,
            "the readout is the gateway's own advertised name",
        )
        assertEquals(
            "NO DATA",
            systemAccessBlocks(NOON, profile = null).single { it.header == "PROFILE" }.readout,
            "an unreachable gateway is the one honest placeholder",
        )
    }

    // -- T-07 / REQ-08: the model block ---------------------------------------------------------

    @Test
    fun `the model block reads the last reported model, and the placeholder before any run has`() {
        assertEquals(
            "CLAUDE-SONNET-5",
            systemAccessBlocks(NOON, model = "claude-sonnet-5").single { it.header == "MODEL" }.readout,
            "the readout is the run's own reported model",
        )
        assertEquals(
            "NO DATA",
            systemAccessBlocks(NOON, model = null).single { it.header == "MODEL" }.readout,
            "no run has reported one: the one honest placeholder",
        )
    }

    // -- T-05 / REQ-09: the voice engines block -------------------------------------------------

    @Test
    fun `the engines block reads both engines and both models off the one info payload`() {
        val info = VoiceManagerInfo(
            sttEngine = "whisper",
            ttsEngine = "openvoice",
            models = VoiceManagerModels(stt = "large-v3", tts = "openvoice-v2"),
        )

        assertEquals(
            "STT  WHISPER  ·  LARGE-V3\nTTS  OPENVOICE  ·  OPENVOICE-V2",
            voiceEnginesReadout(info),
        )
    }

    @Test
    fun `an unreachable voicemanager is the one honest placeholder, not stale engines`() {
        assertEquals("NO DATA", voiceEnginesReadout(null))
    }

    @Test
    fun `a model the server has not loaded reads NO DATA in its slot, never an invention`() {
        val info = VoiceManagerInfo(sttEngine = "qwen", ttsEngine = "vibevoice", models = VoiceManagerModels())

        assertEquals("STT  QWEN  ·  NO DATA\nTTS  VIBEVOICE  ·  NO DATA", voiceEnginesReadout(info))
    }

    @Test
    fun `the voice engines block carries the info readout`() {
        val info = VoiceManagerInfo(sttEngine = "whisper", ttsEngine = "openvoice")

        val block = systemAccessBlocks(NOON, vmInfo = info).single { it.header == "VOICE ENGINES" }

        assertEquals(voiceEnginesReadout(info), block.readout)
    }

    private companion object {
        /** Noon, Aug 14 2020: the published stardate fixture's day. */
        val NOON: java.time.LocalDateTime = java.time.LocalDate.of(2020, 8, 14).atTime(12, 0)
    }
}
