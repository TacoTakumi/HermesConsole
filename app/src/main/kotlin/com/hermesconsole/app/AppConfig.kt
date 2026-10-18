package com.hermesconsole.app

import android.content.Context
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hermesconsole.hermesclient.HermesConfig
import com.hermesconsole.voiceclient.VoiceManagerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The console's runtime configuration (T-23, REQ-28): where the two backends live and how we
 * authenticate to them - plus the follow-up window, the console's one behavioural knob (T-46,
 * REQ-40). All set from the settings screen, all persisted - and the two values that are
 * credentials handled so they cannot reach a log.
 *
 * This is a settings *draft*, not a dialling plan: hosts may be blank and keys empty while someone
 * is still setting the console up, and the screen edits and persists it in that state without
 * complaint. [toBackends] is the gate - only a complete draft becomes the configs the voice loop
 * dials, because [HermesConfig] and [VoiceManagerConfig] (rightly) refuse to exist half-formed.
 */
data class BackendSettings(
    val hermesHost: String = DEFAULT_HERMES_HOST,
    val hermesPort: Int = DEFAULT_HERMES_PORT,
    /** Empty until a human types one (REQ-28): no credential has a compiled-in default. */
    val hermesKey: String = "",
    val vmHost: String = DEFAULT_VM_HOST,
    val vmPort: Int = DEFAULT_VM_PORT,
    /** Empty until a human types one (REQ-28). */
    val vmKey: String = "",
    /**
     * The follow-up window in seconds (REQ-40): how long a finished reply keeps the console
     * listening for the next utterance before it drops back behind the wake word.
     */
    val followUpWindowSeconds: Int = DEFAULT_FOLLOW_UP_WINDOW_SECONDS,
    /**
     * How long a conversation stands open with nobody speaking to it, in seconds (REQ-16/REQ-17),
     * before the console leaves it and hands the wall back to the ready screen.
     */
    val sessionTimeoutSeconds: Int = DEFAULT_SESSION_TIMEOUT_SECONDS,
    /**
     * How long the wall stays lit with nobody in front of it, in seconds (REQ-22/REQ-17). Meant to
     * be the longer of the two: the gap between them is the window in which the return above is
     * visible from across the room rather than happening behind a screen already dark.
     */
    val screensaverTimeoutSeconds: Int = DEFAULT_SCREENSAVER_TIMEOUT_SECONDS,
) {

    /**
     * The two configs the loop dials, or null while any piece is missing. Checked here rather than
     * left to the configs' own `require`s so an unconfigured console comes up saying so instead of
     * throwing on launch.
     */
    fun toBackends(): DebugBackends? {
        if (hermesHost.isBlank() || hermesKey.isBlank() || vmHost.isBlank() || vmKey.isBlank()) return null
        if (hermesPort !in VALID_PORTS || vmPort !in VALID_PORTS) return null
        // The voice client turns an unusable token into AuthFailed itself; HermesConfig throws.
        if (!isHeaderSafe(hermesKey)) return null
        return DebugBackends(
            hermes = HermesConfig(host = hermesHost, port = hermesPort, apiKey = hermesKey),
            voice = VoiceManagerConfig(
                host = vmHost,
                port = vmPort,
                apiKey = vmKey,
                // The client's own default cap is 10 s, which is also REQ-23's whole budget for
                // the voice lamp: a long outage parks the backoff at the cap, and a restart then
                // lands at the worst phase of it - measured at 9.8 s on the wall, a pass with no
                // margin. Capping the console's dial at 5 s keeps redial traffic trivial and the
                // restore comfortably inside the promise. REQ-10 names the behaviour, not the cap.
                maxReconnectDelayMs = 5_000,
            ),
        )
    }

    /**
     * Not the data class's generated form, which would print both keys verbatim into whatever log
     * line first interpolates the settings (REQ-28). The addresses show - they are where, not who,
     * and a console dialling the wrong host is worth being able to see - but a key only ever reads
     * as set or unset.
     */
    override fun toString(): String =
        "BackendSettings(hermes=$hermesHost:$hermesPort key=${hermesKey.presence()}, " +
            "vm=$vmHost:$vmPort key=${vmKey.presence()}, followUp=${followUpWindowSeconds}s, " +
            "session=${sessionTimeoutSeconds}s, screensaver=${screensaverTimeoutSeconds}s)"

    private fun String.presence() = if (isBlank()) "unset" else "set"

    companion object {
        /**
         * Placeholders: loopback on the tablet is never a backend, so first-boot setup sets both
         * hosts on the settings screen alongside the two keys. Hosts and ports stay runtime
         * values - the screen can point the console anywhere - and an address is not a
         * credential (REQ-28).
         * The debug intent path defaults to the same constants, so there is exactly one copy.
         */
        const val DEFAULT_HERMES_HOST = "127.0.0.1"
        const val DEFAULT_HERMES_PORT = 8642
        const val DEFAULT_VM_HOST = "127.0.0.1"
        const val DEFAULT_VM_PORT = 8000

        /** REQ-40's default: ten seconds of open floor after each reply. */
        const val DEFAULT_FOLLOW_UP_WINDOW_SECONDS = 10

        /**
         * REQ-16's defaults, in the unit the screen edits: five minutes to the conversation being
         * handed back, seven to the wall going dark. The same two numbers as
         * [IdleController.DEFAULT_SESSION_TIMEOUT_MILLIS] and its screensaver twin, which are what
         * a console runs on before its store has been read; AppConfigTest pins the pair together so
         * a console cannot behave one way at launch and another once the settings screen is opened.
         */
        const val DEFAULT_SESSION_TIMEOUT_SECONDS = 300
        const val DEFAULT_SCREENSAVER_TIMEOUT_SECONDS = 420

        private val VALID_PORTS = 1..65_535
    }
}

/** The settings screen edits ports as text; this is the one place that text becomes a port. */
internal fun parsePort(text: String): Int? = text.toIntOrNull()?.takeIf { it in 1..65_535 }

/** OkHttp's header value rule: a key outside it makes every request throw before it is sent. */
internal fun isHeaderSafe(key: String): Boolean = key.all { it == '\t' || it in ' '..'~' }

/**
 * The follow-up window as the settings screen edits it: whole seconds, at least one, and capped
 * at two minutes - the window is an open microphone streaming the room off-device (REQ-01), and
 * a knob that could park it open indefinitely would quietly repeal the wake gate.
 */
internal fun parseFollowUpWindow(text: String): Int? = text.toIntOrNull()?.takeIf { it in 1..120 }

/**
 * An idle interval as the settings screen edits it (REQ-17): whole seconds, at least one, and
 * capped at a day.
 *
 * The floor is what the requirement is actually about - a zero parses perfectly well and is a
 * console that hands the conversation back the instant anybody stops talking, or a wall that never
 * lights at all. The ceiling is only there because these are Int seconds and anything past a day is
 * a typo rather than an intent; unlike the follow-up window's two-minute cap there is no privacy
 * argument in it, since neither of these clocks opens a microphone.
 */
internal fun parseIdleTimeout(text: String): Int? = text.toIntOrNull()?.takeIf { it in 1..86_400 }

/**
 * The settings screen's OPEN SYSTEM SETTINGS control (T-52, REQ-41). The console is the tablet's
 * HOME launcher, so there is no system launcher to borrow: without this pill an integrator standing
 * at the wall has no touch path to device configuration - Wi-Fi, date, display - short of adb or a
 * launcher swap. [SYSTEM_SETTINGS_ACTION] is Android's documented action for the top-level Settings
 * app; the label and the action live here as data the screen renders and a JVM test can pin, so the
 * pill on the wall and the intent it launches cannot drift from what the test proves. Firing it does
 * not disturb the voice loop: the loop rides [MicForegroundService], and MainActivity's singleTask
 * HOME activity is returned to - never torn down and rebuilt - when HOME or back leaves Settings.
 */
val SYSTEM_SETTINGS_ACTION: String = Settings.ACTION_SETTINGS
const val SYSTEM_SETTINGS_LABEL = "open system settings"

/**
 * The settings, persisted (REQ-28): a thin typed face over one Preferences DataStore file, which
 * is what carries the values across an app restart and a device reboot - the file is the only
 * copy, and disk is the only place a key ever lives at rest on this device.
 *
 * Takes the [DataStore] rather than a [Context] so the round trip is provable on the plain JVM:
 * the tests open a real store over a real file, kill it, and read the file back cold.
 */
class SettingsStore(private val store: DataStore<Preferences>) {

    /** The settings as they change - the screen renders this, and a save shows up here. */
    val settings: Flow<BackendSettings> = store.data.map { prefs ->
        BackendSettings(
            hermesHost = prefs[HERMES_HOST] ?: BackendSettings.DEFAULT_HERMES_HOST,
            hermesPort = prefs[HERMES_PORT] ?: BackendSettings.DEFAULT_HERMES_PORT,
            hermesKey = prefs[HERMES_KEY] ?: "",
            vmHost = prefs[VM_HOST] ?: BackendSettings.DEFAULT_VM_HOST,
            vmPort = prefs[VM_PORT] ?: BackendSettings.DEFAULT_VM_PORT,
            vmKey = prefs[VM_KEY] ?: "",
            followUpWindowSeconds = prefs[FOLLOW_UP_WINDOW_S]
                ?: BackendSettings.DEFAULT_FOLLOW_UP_WINDOW_SECONDS,
            sessionTimeoutSeconds = prefs[SESSION_TIMEOUT_S]
                ?: BackendSettings.DEFAULT_SESSION_TIMEOUT_SECONDS,
            screensaverTimeoutSeconds = prefs[SCREENSAVER_TIMEOUT_S]
                ?: BackendSettings.DEFAULT_SCREENSAVER_TIMEOUT_SECONDS,
        )
    }

    /** The settings as they stand - what a launch reads once to decide what to dial. */
    suspend fun read(): BackendSettings = settings.first()

    suspend fun save(settings: BackendSettings) {
        store.edit { prefs ->
            prefs[HERMES_HOST] = settings.hermesHost
            prefs[HERMES_PORT] = settings.hermesPort
            prefs[HERMES_KEY] = settings.hermesKey
            prefs[VM_HOST] = settings.vmHost
            prefs[VM_PORT] = settings.vmPort
            prefs[VM_KEY] = settings.vmKey
            prefs[FOLLOW_UP_WINDOW_S] = settings.followUpWindowSeconds
            prefs[SESSION_TIMEOUT_S] = settings.sessionTimeoutSeconds
            prefs[SCREENSAVER_TIMEOUT_S] = settings.screensaverTimeoutSeconds
        }
    }

    private companion object {
        val HERMES_HOST = stringPreferencesKey("hermes_host")
        val HERMES_PORT = intPreferencesKey("hermes_port")
        val HERMES_KEY = stringPreferencesKey("hermes_api_key")
        val VM_HOST = stringPreferencesKey("vm_host")
        val VM_PORT = intPreferencesKey("vm_port")
        val VM_KEY = stringPreferencesKey("vm_api_key")
        val FOLLOW_UP_WINDOW_S = intPreferencesKey("follow_up_window_s")
        val SESSION_TIMEOUT_S = intPreferencesKey("session_timeout_s")
        val SCREENSAVER_TIMEOUT_S = intPreferencesKey("screensaver_timeout_s")
    }
}

/**
 * The one settings file, app-wide: the delegate hands every caller the same instance, which is
 * DataStore's own rule (two stores on one file throw). Lives in the app's private storage, so the
 * keys at rest are behind the same sandbox as everything else the app owns.
 */
val Context.consoleSettings: DataStore<Preferences> by preferencesDataStore(name = "console_settings")

/**
 * The barge-in journal's file (T-59), deliberately not the settings one.
 *
 * Two reasons to keep them apart. The settings file holds both credentials, and this one is meant
 * to be read off the device by a human chasing a phantom - separate files means that read never
 * goes near key material (REQ-28). And a diagnostic that filled up or corrupted has no business
 * taking the console's configuration with it.
 */
val Context.bargeInRecords: DataStore<Preferences> by preferencesDataStore(name = "barge_ins")
