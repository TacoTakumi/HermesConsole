package com.hermesconsole.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * T-28 / REQ-25: the console is always on - HOME launcher, a microphone-typed foreground
 * service, and a VoiceInteractionService that lets it hold the device assistant role.
 *
 * The always-on posture is not code, it is declaration: the OS reads the manifest and decides
 * whether the console comes up after a reboot, whether its process is an anchored foreground
 * service, and whether it may even appear in the assistant picker. The manifest is checked-in
 * XML, so those declarations are provable on a plain JVM - no tablet, no dumpsys. What only the
 * device can prove (the Activity actually on screen after `adb reboot`, the FGS actually running,
 * the role actually held) stays a device step.
 *
 * Each assertion below maps to a way the appliance silently degrades into an ordinary app:
 * without HOME the launcher wins the boot; without the microphone service type Android 14+
 * refuses (or later kills) the mic anchor; without BIND_VOICE_INTERACTION the system never
 * offers the console as assistant at all.
 */
class ManifestAlwaysOnTest {

    private val manifest: Element by lazy {
        // Gradle runs JVM unit tests with the module directory as the working dir; the repo
        // root is tolerated so the test also runs from an IDE configured that way.
        val file = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
            .map(::File).firstOrNull(File::exists)
            ?: error("AndroidManifest.xml not found from ${File(".").absolutePath}")
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(file).documentElement
    }

    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).map(childNodes::item).filterIsInstance<Element>()
            .filter { it.tagName == tag }

    private fun Element.android(attr: String): String = getAttributeNS(ANDROID_NS, attr)

    private fun services(): List<Element> =
        manifest.children("application").single().children("service")

    // -- HOME launcher -------------------------------------------------------------------------

    @Test
    fun `MainActivity offers itself as HOME, and keeps the plain launcher entry`() {
        val activity = manifest.children("application").single().children("activity")
            .single { it.android("name") == ".MainActivity" }
        val filters = activity.children("intent-filter").map { filter ->
            filter.children("action").map { it.android("name") } to
                filter.children("category").map { it.android("name") }
        }

        val home = filters.filter { (_, categories) -> "android.intent.category.HOME" in categories }
        assertEquals(1, home.size, "exactly one HOME intent-filter on MainActivity")
        val (actions, categories) = home.single()
        assertTrue("android.intent.action.MAIN" in actions, "HOME filter carries MAIN")
        assertTrue(
            "android.intent.category.DEFAULT" in categories,
            "HOME resolution requires DEFAULT; without it the filter never matches",
        )
        assertTrue(
            filters.any { (_, cats) -> "android.intent.category.LAUNCHER" in cats },
            "the ordinary LAUNCHER entry must survive so non-HOME launches still work",
        )
    }

    @Test
    fun `MainActivity is singleTask - a launch intent can never stack a second console`() {
        val activity = manifest.children("application").single().children("activity")
            .single { it.android("name") == ".MainActivity" }
        assertEquals(
            "singleTask", activity.android("launchMode"),
            "the default launchMode mints a fresh instance per launch intent, stacking a second " +
                "console over the HOME one - two idle timers today, and two voice loops (both on " +
                "the microphone) the day the live loop lands in the activity",
        )
    }

    // -- microphone foreground service ----------------------------------------------------------

    @Test
    fun `a foreground service of type microphone anchors the process`() {
        val mic = services().filter { it.android("foregroundServiceType") == "microphone" }
        assertEquals(1, mic.size, "exactly one microphone-typed service")
        assertEquals(
            "false", mic.single().android("exported"),
            "the anchor is the console's own; nothing outside the app starts it",
        )
    }

    @Test
    fun `the manifest asks for every permission the mic anchor needs on Android 14+`() {
        val permissions = manifest.children("uses-permission").map { it.android("name") }
        for (needed in listOf(
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_MICROPHONE",
            "android.permission.POST_NOTIFICATIONS",
        )) assertTrue(needed in permissions, "missing $needed")
    }

    // -- screen-off survival (T-29) --------------------------------------------------------------

    @Test
    fun `the manifest asks for WAKE_LOCK so the anchor can hold the CPU through a dark screen`() {
        val permissions = manifest.children("uses-permission").map { it.android("name") }
        assertTrue(
            "android.permission.WAKE_LOCK" in permissions,
            "without WAKE_LOCK the mic anchor cannot hold a partial wake lock, and once the " +
                "display sleeps a dozing SM-X210 suspends the CPU under the wake-word engine - " +
                "the console goes deaf exactly when the room goes dark (REQ-26)",
        )
    }

    // -- hands-off reachability (T-48) -----------------------------------------------------------

    @Test
    fun `the manifest asks for WRITE_SECURE_SETTINGS so the anchor can re-assert wireless debugging`() {
        val permissions = manifest.children("uses-permission").map { it.android("name") }
        assertTrue(
            "android.permission.WRITE_SECURE_SETTINGS" in permissions,
            "without WRITE_SECURE_SETTINGS declared, `pm grant` refuses the grant and the anchor " +
                "cannot write adb_wifi_enabled at boot - wireless debugging stays reset after " +
                "every reboot and the wall-mounted tablet is unreachable without a hand on the " +
                "screen (REQ-25/REQ-27)",
        )
    }

    // -- assistant role --------------------------------------------------------------------------

    @Test
    fun `a VoiceInteractionService is guarded by BIND_VOICE_INTERACTION and declares its metadata`() {
        val vis = services().filter { service ->
            service.children("intent-filter").any { filter ->
                filter.children("action")
                    .any { it.android("name") == "android.service.voice.VoiceInteractionService" }
            }
        }
        assertEquals(1, vis.size, "exactly one VoiceInteractionService")
        assertEquals(
            "android.permission.BIND_VOICE_INTERACTION", vis.single().android("permission"),
            "only the system may bind the assistant service",
        )
        assertNotNull(
            vis.single().children("meta-data")
                .singleOrNull { it.android("name") == "android.voice_interaction" },
            "the android.voice_interaction meta-data is what makes the service a valid assistant",
        )
    }

    @Test
    fun `the session service the metadata points at exists and carries the same guard`() {
        val sessionServices = services().filter {
            it.android("name").endsWith("VoiceInteractionSessionService")
        }
        assertEquals(1, sessionServices.size, "exactly one VoiceInteractionSessionService")
        assertEquals(
            "android.permission.BIND_VOICE_INTERACTION",
            sessionServices.single().android("permission"),
            "the session service is system-bound too",
        )
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
