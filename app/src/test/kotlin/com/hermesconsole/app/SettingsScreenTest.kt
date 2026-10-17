package com.hermesconsole.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * T-18 / REQ-17: what the settings screen would save, decided where a test can drive it.
 *
 * The screen's one rule is that a draft may be half-configured but never half-parsed: a host can be
 * blank while somebody is still setting the console up ([BackendSettings.toBackends] is where
 * completeness is judged), but a port or an interval that is not a number has no honest integer to
 * persist, and SAVE must refuse rather than quietly write a default. That rule used to live inside
 * the composable as a pair of booleans and a run of elvis-returns in the pill's onClick - two
 * places that had to agree, and neither reachable from the JVM. [SettingsDraft] is both of them
 * written once: the pill is enabled exactly when [SettingsDraft.toSettings] answers non-null, and
 * what it saves is that same answer.
 */
class SettingsScreenTest {

    private val stored = BackendSettings(
        hermesHost = "10.0.0.5",
        hermesPort = 9001,
        hermesKey = "hermes-key",
        vmHost = "10.0.0.6",
        vmPort = 9002,
        vmKey = "vm-key",
        followUpWindowSeconds = 25,
        sessionTimeoutSeconds = 90,
        screensaverTimeoutSeconds = 150,
    )

    /** The screen as it opens: every stored value already in its field. */
    private val opened = SettingsDraft(stored)

    @Test
    fun `the screen opens on what is stored, both intervals included`() {
        // REQ-17's "seeded from what is stored": an interval field that opened blank or on the
        // compiled default would quietly reset a configured console the next time anyone pressed
        // SAVE for an unrelated reason.
        assertEquals("90", opened.sessionTimeout)
        assertEquals("150", opened.screensaverTimeout)
        assertEquals("25", opened.followUpWindow)
        assertEquals(stored, opened.toSettings(), "opening and saving unchanged must be a no-op")
    }

    @Test
    fun `edited intervals are saved as whole seconds`() {
        val edited = opened.copy(sessionTimeout = "240", screensaverTimeout = "600").toSettings()

        assertNotNull(edited)
        assertEquals(240, edited!!.sessionTimeoutSeconds)
        assertEquals(600, edited.screensaverTimeoutSeconds)
    }

    @Test
    fun `a session timeout that is not a positive number blocks the save`() {
        // Refused the way the follow-up window's is (REQ-17), and for the same reason: there is no
        // honest integer to persist. Zero is the one that matters - it parses, and a zero interval
        // is a console that hands the conversation back the instant anybody stops talking.
        assertNull(opened.copy(sessionTimeout = "").toSettings())
        assertNull(opened.copy(sessionTimeout = "soon").toSettings())
        assertNull(opened.copy(sessionTimeout = "0").toSettings())
        assertNull(opened.copy(sessionTimeout = "-30").toSettings())
    }

    @Test
    fun `a screensaver timeout that is not a positive number blocks the save`() {
        assertNull(opened.copy(screensaverTimeout = "").toSettings())
        assertNull(opened.copy(screensaverTimeout = "never").toSettings())
        assertNull(opened.copy(screensaverTimeout = "0").toSettings())
        assertNull(opened.copy(screensaverTimeout = "-1").toSettings())
    }

    @Test
    fun `the rules the two new fields joined still hold`() {
        // The intervals were added beside three existing refusals, not in place of them.
        assertNull(opened.copy(hermesPort = "70000").toSettings(), "a port outside the range")
        assertNull(opened.copy(vmPort = "web").toSettings(), "a port that is not a number")
        assertNull(opened.copy(followUpWindow = "0").toSettings(), "a follow-up window of nothing")
    }

    @Test
    fun `a half-configured console still saves - completeness is judged elsewhere`() {
        // The screen persists drafts: somebody setting a console up types the hosts on Monday and
        // finds the keys on Tuesday, and must not lose Monday's work to a validation rule that
        // belongs to toBackends.
        val draft = opened.copy(hermesKey = "", vmHost = "").toSettings()

        assertNotNull(draft)
        assertEquals("", draft!!.hermesKey)
        assertNull(draft.toBackends(), "and it is still an unconfigured console, which is toBackends' word")
    }

    @Test
    fun `surrounding whitespace never reaches the store`() {
        val saved = opened.copy(hermesHost = "  10.0.0.9  ", sessionTimeout = " 240 ").toSettings()

        assertNotNull(saved)
        assertEquals("10.0.0.9", saved!!.hermesHost)
        assertEquals(240, saved.sessionTimeoutSeconds)
    }
}
