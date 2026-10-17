package com.hermesconsole.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T-53 / REQ-42: the console is the tablet's HOME launcher, so it carries its own app drawer. The
 * PackageManager query and the icon load are Android's; what the drawer *does* with the raw
 * resolved list - drop the console itself, drop anything with no label to tap, and order the rest -
 * is the pure piece, and it is pinned here so the wall's list cannot silently start showing the
 * console in its own drawer, or come out unsorted, or list an untappable ghost.
 */
class AppDrawerTest {

    private val self = "com.hermesconsole"

    private fun app(label: String, pkg: String, activity: String = "$pkg.Main") =
        DrawerApp(label = label, packageName = pkg, activityName = activity)

    // -- exclusion of self ----------------------------------------------------------------------

    @Test
    fun `the console never appears in its own drawer`() {
        // The console is HOME, so it resolves MAIN/LAUNCHER like any app - and launching it from
        // its own drawer is a no-op that only confuses. Excluded by package, so every one of its
        // launcher activities goes, not just the one that happens to match a name.
        val resolved = listOf(
            app("Hermes Console", self),
            app("Hermes Console", self, activity = "$self.MainActivity"),
            app("Calculator", "com.android.calculator2"),
        )

        val shown = launchableApps(resolved, self)

        assertFalse(shown.any { it.packageName == self }, "the console leaked into its own drawer: $shown")
        assertEquals(listOf("Calculator"), shown.map { it.label })
    }

    // -- query filtering ------------------------------------------------------------------------

    @Test
    fun `an entry with no label to show is dropped`() {
        // A launcher activity with a blank label has nothing to render and nothing to read before
        // tapping - it is a ghost, not an app, so it never reaches the wall.
        val resolved = listOf(
            app("Maps", "com.google.maps"),
            app("   ", "com.mystery.blank"),
            app("", "com.mystery.empty"),
        )

        val shown = launchableApps(resolved, self)

        assertEquals(listOf("Maps"), shown.map { it.label })
    }

    @Test
    fun `two launcher activities of one app are both listed`() {
        // The drawer lists activities, not packages (REQ-42: "every activity resolving
        // MAIN/CATEGORY_LAUNCHER") - a suite that publishes two launcher entries shows both, each
        // launchable on its own component.
        val resolved = listOf(
            app("Studio", "com.suite.app", activity = "com.suite.app.Studio"),
            app("Docs", "com.suite.app", activity = "com.suite.app.Docs"),
        )

        val shown = launchableApps(resolved, self)

        assertEquals(2, shown.size)
        assertEquals(setOf("com.suite.app.Studio", "com.suite.app.Docs"), shown.map { it.activityName }.toSet())
    }

    // -- sort -----------------------------------------------------------------------------------

    @Test
    fun `apps are ordered by label, case-insensitively`() {
        // The wall reads like a phone book: alphabetical, and case is not order - "aardvark" sits
        // above "Zephyr", not below it because a capital Z sorts before a lowercase a in ASCII.
        val resolved = listOf(
            app("Zephyr", "com.z"),
            app("aardvark", "com.a"),
            app("Maps", "com.m"),
        )

        val shown = launchableApps(resolved, self)

        assertEquals(listOf("aardvark", "Maps", "Zephyr"), shown.map { it.label })
    }

    @Test
    fun `an empty query is an empty drawer, not a crash`() {
        assertTrue(launchableApps(emptyList(), self).isEmpty())
    }
}
