package com.hermesconsole.app

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Test
import java.io.File

/**
 * T-23 / REQ-28: the backends' addresses and keys are runtime configuration - persisted, read
 * back, and never able to reach a log.
 *
 * The store is exercised over a real Preferences DataStore file on the plain JVM, because the
 * requirement is about surviving process death: what a *second* store, opened cold over the same
 * file, reads back is what the console will dial after a restart or a reboot. An in-memory fake
 * would pass while persisting nothing.
 *
 * The key discipline is pinned at the type: [BackendSettings] holds two credentials, and a data
 * class's generated toString would hand them to the first log statement that interpolates the
 * settings. So the string form is tested to carry no key material at all - the leak is fenced out
 * where the value lives, not at each of the call sites it might escape through.
 */
class AppConfigTest {

    @TempDir
    lateinit var dir: File

    /**
     * A store over the given scope, so a test can kill it - DataStore refuses two live instances
     * on one file, which is exactly the process-death boundary the reopen test walks across.
     */
    private fun storeIn(scope: CoroutineScope): SettingsStore =
        SettingsStore(
            PreferenceDataStoreFactory.create(scope = scope) {
                File(dir, "console_settings.preferences_pb")
            },
        )

    private val everything = BackendSettings(
        hermesHost = "10.0.0.5",
        hermesPort = 9001,
        hermesKey = "hermes-key-under-test",
        vmHost = "10.0.0.6",
        vmPort = 9002,
        vmKey = "vm-key-under-test",
        followUpWindowSeconds = 25,
        sessionTimeoutSeconds = 90,
        screensaverTimeoutSeconds = 150,
        // Not the default, so a round trip that drops it fails.
        recordableAudio = true,
        bargeIn = true,
    )

    // -- persistence --------------------------------------------------------------------------

    @Test
    fun `a fresh store has empty keys`() = runBlocking {
        val settings = storeIn(scope()).read()

        // No credential is compiled in (REQ-28): before anyone has typed a key, there is no key.
        assertEquals("", settings.hermesKey)
        assertEquals("", settings.vmKey)

        // The hosts prefill with the fleet's addresses - where, not who - so first-boot setup is
        // typing two keys, not four addresses copied off a wiki page.
        assertEquals(BackendSettings.DEFAULT_HERMES_HOST, settings.hermesHost)
        assertEquals(BackendSettings.DEFAULT_HERMES_PORT, settings.hermesPort)
        assertEquals(BackendSettings.DEFAULT_VM_HOST, settings.vmHost)
        assertEquals(BackendSettings.DEFAULT_VM_PORT, settings.vmPort)

        // The follow-up window opens at REQ-40's default until somebody turns the knob.
        assertEquals(BackendSettings.DEFAULT_FOLLOW_UP_WINDOW_SECONDS, settings.followUpWindowSeconds)
        assertEquals(10, BackendSettings.DEFAULT_FOLLOW_UP_WINDOW_SECONDS)

        // And so do the two idle intervals (REQ-17). An absent key is not only a fresh install: it
        // is every console upgraded from a build that had no such setting, and it must read as the
        // shipped interval rather than as zero - which would hand the conversation back instantly.
        assertEquals(300, settings.sessionTimeoutSeconds, "five minutes before the conversation is handed back")
        assertEquals(420, settings.screensaverTimeoutSeconds, "seven before the wall goes dark")
    }

    @Test
    fun `the stored idle intervals are the same numbers the clocks run on`() {
        // The defaults exist twice - here in seconds for the screen to seed from, and in
        // IdleController in millis for a console whose store has not been read yet - and the two
        // must not drift. Changing one without the other ships a console that behaves differently
        // before and after somebody opens the settings screen.
        assertEquals(
            IdleController.DEFAULT_SESSION_TIMEOUT_MILLIS,
            BackendSettings.DEFAULT_SESSION_TIMEOUT_SECONDS * 1_000L,
            "the session default in seconds must be the clock's own interval",
        )
        assertEquals(
            IdleController.DEFAULT_SCREENSAVER_TIMEOUT_MILLIS,
            BackendSettings.DEFAULT_SCREENSAVER_TIMEOUT_SECONDS * 1_000L,
            "and so must the screensaver's",
        )
    }

    @Test
    fun `every value round-trips through a save`() = runBlocking {
        val store = storeIn(scope())
        store.save(everything)
        assertEquals(everything, store.read())
    }

    @Test
    fun `a store reopened over the same file reads what the last one wrote`() = runBlocking {
        // Write with one store, then take its scope down completely - this is the app process
        // dying. cancelAndJoin, not cancel: DataStore frees its claim on the file when the scope's
        // job *completes*, and the reopen below would race a plain cancel to it.
        val firstLife = scope()
        storeIn(firstLife).save(everything)
        firstLife.coroutineContext[Job]!!.apply {
            cancel()
            join()
        }

        // The next process. Same file, new store: what it reads is what survives a reboot,
        // because the only thing carrying the settings across is the file itself.
        val reopened = storeIn(scope()).read()
        assertEquals(everything, reopened)

        // Named as well as compared, because the equality above passes for a field the store
        // silently drops on both sides of a restart if the default happens to be what was saved.
        assertEquals(90, reopened.sessionTimeoutSeconds, "a set session timeout survives the process")
        assertEquals(150, reopened.screensaverTimeoutSeconds, "and so does the screensaver's")
    }

    @Test
    fun `a store without the voice volume reads full volume`() = runBlocking {
        // Every console upgraded from a build without the setting must keep speaking at its old level.
        assertEquals(100, storeIn(scope()).read().voiceVolumePercent)
    }

    @Test
    fun `a store without the recordable setting plays on the assistant usage`() = runBlocking {
        // Every console upgraded from a build without the setting keeps its tested playback path.
        assertEquals(false, storeIn(scope()).read().recordableAudio)
    }

    @Test
    fun `a store without the barge-in setting keeps it off`() = runBlocking {
        // A fresh install, or a console upgraded from a build without the setting, must not start
        // cutting its own replies off when the echo canceller lets its speech back in.
        assertEquals(false, storeIn(scope()).read().bargeIn)
    }

    @Test
    fun `a saved barge-in reads back on`() = runBlocking {
        val store = storeIn(scope())
        store.save(everything.copy(bargeIn = true))
        assertEquals(true, store.read().bargeIn)
    }

    @Test
    fun `a written voice volume reads back`() = runBlocking {
        val store = storeIn(scope())
        store.saveVoiceVolume(40)
        assertEquals(40, store.read().voiceVolumePercent)
    }

    @Test
    fun `a settings save leaves the voice volume alone`() = runBlocking {
        // The settings screen does not show the voice volume, so what it saves carries the default.
        // A SAVE there must not turn the console back up to full.
        val store = storeIn(scope())
        store.saveVoiceVolume(40)
        store.save(everything)
        assertEquals(40, store.read().voiceVolumePercent)
    }

    // -- key discipline (REQ-28) ----------------------------------------------------------------

    @Test
    fun `no key material survives into the settings string form`() {
        val printed = everything.toString()

        // The generated toString of a data class would include both keys verbatim; the override
        // must not. Tested on the substring, not the field: a "redaction" that ships the key with
        // a prefix would still leak it.
        assertFalse(printed.contains(everything.hermesKey), "hermes key leaked: $printed")
        assertFalse(printed.contains(everything.vmKey), "vm key leaked: $printed")

        // The addresses may show - they are where, not who - so a logged settings object is still
        // worth something when someone is diagnosing a console dialling the wrong host.
        assertEquals(true, printed.contains(everything.hermesHost))
        assertEquals(true, printed.contains(everything.vmHost))
    }

    // -- from settings to configs ---------------------------------------------------------------

    @Test
    fun `complete settings become the two configs the loop dials`() {
        val backends = everything.toBackends()

        assertNotNull(backends)
        assertEquals("10.0.0.5", backends!!.hermes.host)
        assertEquals(9001, backends.hermes.port)
        assertEquals("hermes-key-under-test", backends.hermes.apiKey)
        assertEquals("10.0.0.6", backends.voice.host)
        assertEquals(9002, backends.voice.port)
        assertEquals("vm-key-under-test", backends.voice.apiKey)
    }

    @Test
    fun `settings missing a key or a host dial nothing`() {
        // A console configured halfway must come up unconfigured, not dial nowhere: HermesConfig
        // itself refuses a blank key, so the check has to happen before construction.
        assertNull(everything.copy(hermesKey = "").toBackends())
        assertNull(everything.copy(vmKey = "").toBackends())
        assertNull(everything.copy(hermesHost = " ").toBackends())
        assertNull(everything.copy(vmHost = "").toBackends())
        assertNull(everything.copy(hermesPort = 0).toBackends())
        assertNull(everything.copy(vmPort = 70_000).toBackends())
    }

    @Test
    fun `a stored Hermes key that cannot go in a header dials nothing`() {
        // HermesConfig refuses such a key too, so the check has to happen before construction.
        assertNull(everything.copy(hermesKey = "made-up\nkey").toBackends())
        assertNull(everything.copy(hermesKey = "made-up-\u00e9").toBackends())
        assertNotNull(everything.copy(hermesKey = "made-up ~key!").toBackends())
    }

    @Test
    fun `port text parses only as a real tcp port`() {
        // The settings screen edits ports as text; this is the one place the text becomes a port.
        assertEquals(8642, parsePort("8642"))
        assertEquals(1, parsePort("1"))
        assertEquals(65_535, parsePort("65535"))
        assertNull(parsePort(""))
        assertNull(parsePort("web"))
        assertNull(parsePort("0"))
        assertNull(parsePort("-1"))
        assertNull(parsePort("65536"))
    }

    @Test
    fun `the follow-up window parses as whole seconds, bounded`() {
        // At least a second, at most two minutes: the window is an open microphone streaming the
        // room off-device, and the knob must not be able to park it open indefinitely (REQ-01).
        assertEquals(10, parseFollowUpWindow("10"))
        assertEquals(1, parseFollowUpWindow("1"))
        assertEquals(120, parseFollowUpWindow("120"))
        assertNull(parseFollowUpWindow(""))
        assertNull(parseFollowUpWindow("forever"))
        assertNull(parseFollowUpWindow("0"))
        assertNull(parseFollowUpWindow("-5"))
        assertNull(parseFollowUpWindow("121"))
    }

    @Test
    fun `an idle interval parses as whole positive seconds, capped at a day`() {
        // REQ-17: refused the way the follow-up window's is. Zero is the one that matters - it
        // parses perfectly well and is a console that hands the conversation back the instant
        // anybody stops talking, or a wall that never lights at all.
        assertEquals(300, parseIdleTimeout("300"))
        assertEquals(1, parseIdleTimeout("1"))
        assertEquals(86_400, parseIdleTimeout("86400"))
        assertNull(parseIdleTimeout(""))
        assertNull(parseIdleTimeout("soon"))
        assertNull(parseIdleTimeout("0"))
        assertNull(parseIdleTimeout("-30"))
        assertNull(parseIdleTimeout("86401"))
    }

    @Test
    fun `the open system settings control fires the platform settings action`() {
        // T-52 / REQ-41: the settings screen's OPEN SYSTEM SETTINGS pill must land in the tablet's
        // top-level Settings app. ACTION_SETTINGS is Android's documented action for exactly that;
        // pin its wire value here so a wrong constant - an app-details screen, a Wi-Fi sub-page -
        // cannot ship silently, and confirm the pill carries a label for the integrator to tap.
        assertEquals("android.settings.SETTINGS", SYSTEM_SETTINGS_ACTION)
        assertTrue(SYSTEM_SETTINGS_LABEL.isNotBlank())
    }

    private fun scope() = CoroutineScope(Dispatchers.IO + Job())
}
