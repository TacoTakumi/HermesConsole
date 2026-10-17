package com.hermesconsole.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking

/**
 * Where a connected test gets its backend dial (T-41, REQ-28): the console's own persisted
 * settings store, with instrumentation arguments as explicit per-field overrides.
 *
 * adbd logs the full `am instrument` command line it is asked to run, so a key passed as an
 * instrumentation argument is written into the OS log by the OS itself before the test even
 * starts - the one leak REQ-28's "no credential is logged" cannot survive. A key read from the
 * store never crosses a command line: set both keys once on the settings screen (or seed them
 * with [SeedSettingsTool]) and every test that needs a backend finds them there. An explicit
 * argument still wins, field by field, so one run can dial a scratch backend - that path logs
 * the key, and is for keys that are scratch too.
 */
object TestBackends {

    /** The store overlaid with any explicit arguments - the settings a connected test dials. */
    fun resolve(): BackendSettings {
        val args = InstrumentationRegistry.getArguments()
        val stored = runBlocking {
            SettingsStore(
                InstrumentationRegistry.getInstrumentation().targetContext.consoleSettings,
            ).read()
        }
        return BackendSettings(
            hermesHost = args.getString("hermesHost") ?: stored.hermesHost,
            hermesPort = args.getString("hermesPort")?.toIntOrNull() ?: stored.hermesPort,
            hermesKey = args.getString("hermesKey") ?: stored.hermesKey,
            vmHost = args.getString("vmHost") ?: stored.vmHost,
            vmPort = args.getString("vmPort")?.toIntOrNull() ?: stored.vmPort,
            vmKey = args.getString("vmKey") ?: stored.vmKey,
            followUpWindowSeconds = stored.followUpWindowSeconds,
        )
    }

    /** How a test asks for what it needs: on-glass setup first, the logged override second. */
    const val NEEDS_VM = "Needs the VoiceManager key: set it on the settings screen " +
        "(or pass vmKey= to dial a scratch backend - adbd logs that key)"
    const val NEEDS_HERMES = "Needs the Hermes key: set it on the settings screen " +
        "(or pass hermesKey= to dial a scratch backend - adbd logs that key)"
    const val NEEDS_BOTH = "Needs both backend keys: set them on the settings screen " +
        "(or pass vmKey=/hermesKey= to dial a scratch backend - adbd logs those keys)"
}
