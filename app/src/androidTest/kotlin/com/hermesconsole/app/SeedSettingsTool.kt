package com.hermesconsole.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not a test: a tool. Seeds the console's persisted settings (T-23's store) from instrumentation
 * arguments, so an on-device verify can configure the wall console without typing keys on glass.
 * The store is the same file the settings screen writes, every launch reads, and every connected
 * test dials through [TestBackends] (REQ-28: runtime config, nothing compiled in). Skips itself
 * unless both keys are supplied.
 *
 * Know what this costs: adbd logs the `am instrument` command line, so a key seeded this way
 * crosses the OS log once (T-41). The settings screen is the leak-free path; use this tool when
 * typing on glass is not worth it and the log exposure is acceptable.
 *
 *     scripts/connected-test.sh SeedSettingsTool -e vmKey ... -e hermesKey ... \
 *         -e hermesHost ... -e vmHost ...
 *
 * (Through the script, never `gradlew connectedDebugAndroidTest`: gradle's connected run
 * uninstalls the console when it finishes, wiping the very store this just seeded.)
 */
@RunWith(AndroidJUnit4::class)
class SeedSettingsTool {

    @Test
    fun seed_the_console_settings_store() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val hermesKey = args.getString("hermesKey")
        val vmKey = args.getString("vmKey")
        assumeTrue("no keys supplied; nothing to seed", hermesKey != null && vmKey != null)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SettingsStore(context.consoleSettings).save(
            BackendSettings(
                hermesHost = args.getString("hermesHost") ?: BackendSettings.DEFAULT_HERMES_HOST,
                hermesPort = args.getString("hermesPort")?.toIntOrNull() ?: BackendSettings.DEFAULT_HERMES_PORT,
                hermesKey = hermesKey!!,
                vmHost = args.getString("vmHost") ?: BackendSettings.DEFAULT_VM_HOST,
                vmPort = args.getString("vmPort")?.toIntOrNull() ?: BackendSettings.DEFAULT_VM_PORT,
                vmKey = vmKey!!,
            ),
        )
    }
}
