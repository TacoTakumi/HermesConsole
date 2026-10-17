package com.hermesconsole.app

import com.hermesconsole.lcars.LcarsCue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * T-54 / REQ-43: REQ-39's touch-confirm chirp plays on every pressable control on every screen.
 *
 * The wall has no other feedback that a touch landed - no ripple, no haptic - so a pressable that
 * does not chirp is a control the person cannot tell they hit. There is no JVM way to render the
 * Compose tree and tap it (no Robolectric on this classpath), so the audit is a source scan, in the
 * idiom of [ManifestAlwaysOnTest] and scripts/check-module-boundaries.sh: the invariant that
 * guarantees the chirp is structural, and structure is provable on a plain JVM. What only the device
 * can prove - that the chirp is actually audible over the room, echo-cancelled off the mic - stays
 * the on-device tap sweep in T-54's verify.
 *
 * Three clauses, together the whole path from a press to a sound:
 *  1. the kit's own pressables ([LcarsPill], [LcarsSegment]) sound [LcarsCue.CONFIRM] on click;
 *  2. any pressable built outside the kit (a raw `.clickable`) carries the same cue call itself;
 *  3. every composition root that renders a pressable-bearing screen provides a live cue path, so
 *     the kit components under it are not chirping into the silent default of [LocalLcarsCues].
 *
 * Clause 3 is the one that catches the debug screen: its dispatch and approval pills are kit
 * components that confirm through [LocalLcarsCues], but its `setContent` root did not provide one,
 * so every touch on the debug screen fell to the silent default.
 *
 * Scope notes, deliberate (as in the boundary script):
 *  - "Pressable control" is a control that performs a selected action: `.clickable`,
 *    `.combinedClickable`, `.toggleable`, `.selectable`. The shell's one `pointerInput` observer
 *    (ConsoleShell.observePresses) is not one: it watches presses on the Initial pass without
 *    consuming them to reset the idle countdown, takes no action of its own, and so has no touch to
 *    confirm. It is excluded by construction, not overlooked.
 *  - The scanner assumes no raw triple-quoted strings in the scanned regions (there are none); a
 *    future one would need the brace matcher taught about them.
 */
class TouchChirpAuditTest {

    private val kitPressableFiles = listOf(
        "lcars/src/main/kotlin/com/hermesconsole/lcars/Pill.kt",
        "lcars/src/main/kotlin/com/hermesconsole/lcars/Rail.kt",
    )

    /** The pressable modifiers that make a control - the ones a touch must be able to confirm. */
    private val pressableModifiers = listOf(".clickable", ".combinedClickable", ".toggleable", ".selectable")

    /**
     * The interactive screens - the ones that host pressable controls. A `setContent` root that
     * renders any of these must provide the cue path. [DebugScreenUnconfigured] is deliberately
     * absent: it is a static "no keys" placard with nothing to press, so its root needs no provider.
     */
    private val interactiveScreens = listOf("ConsoleShell", "SettingsScreen", "AppDrawerScreen", "DebugScreen")

    // -- clause 1: the kit confirms its own touches -----------------------------------------------

    @Test
    fun `every kit pressable sounds the confirm chirp`() {
        for (path in kitPressableFiles) {
            val source = blankComments(read(path))
            val lambdas = pressableLambdas(source)
            assertTrue(lambdas.isNotEmpty(), "$path declares a kit pressable but the scan found none")
            for (lambda in lambdas) {
                assertTrue(
                    lambda.contains("LcarsCue.CONFIRM"),
                    "a kit pressable in $path does not sound the touch chirp (REQ-39):\n$lambda",
                )
            }
        }
    }

    // -- clause 2: controls built outside the kit carry the cue themselves ------------------------

    @Test
    fun `every non-kit pressable in the app carries the confirm chirp`() {
        for (file in appMainSources()) {
            val source = blankComments(file.readText())
            for (lambda in pressableLambdas(source)) {
                assertTrue(
                    lambda.contains("LcarsCue.CONFIRM"),
                    "a raw pressable in ${file.name} is built outside the kit and does not sound the " +
                        "touch chirp - move it onto a kit component or add cues(LcarsCue.CONFIRM) (REQ-39):\n$lambda",
                )
            }
        }
    }

    // -- clause 3: every interactive screen is composed under a live cue path ----------------------

    @Test
    fun `every interactive composition root provides the cue path`() {
        val source = blankComments(read("app/src/main/kotlin/com/hermesconsole/app/MainActivity.kt"))
        val roots = compositionRoots(source)
        assertTrue(roots.isNotEmpty(), "no setContent roots found in MainActivity")

        val interactiveRoots = roots.filter { root -> interactiveScreens.any { root.mentions(it) } }
        assertTrue(
            interactiveRoots.isNotEmpty(),
            "no interactive composition root found - the scan or the screen names are stale",
        )
        for (root in interactiveRoots) {
            assertTrue(
                root.contains("LocalLcarsCues"),
                "a composition root renders a pressable-bearing screen without providing the cue path, " +
                    "so its kit components chirp into LocalLcarsCues' silent default (REQ-39):\n${root.take(200)}...",
            )
        }
    }

    // -- clause 4: no new sound joins REQ-39's vocabulary -----------------------------------------

    @Test
    fun `the touch chirp reuses REQ-39's vocabulary, adding no new sound`() {
        // The audit's job is coverage, not a new cue: every touch reuses CONFIRM. If a screen ever
        // needs a new voice, this trips first and forces the vocabulary decision into the open -
        // which is exactly what SESSION_ENDED went through, and after it APPROVAL_REQUIRED: each
        // is here because a requirement asked for it in writing (wall-session-scoping REQ-09;
        // approval-pane-fix REQ-10, the expiring question that must never arrive silent), never
        // because a screen quietly grew a sound. No touch may reach for either, and the list
        // stays closed against the next one.
        assertEquals(
            setOf("LISTENING_OPEN", "LISTENING_CLOSED", "SESSION_ENDED", "BARGE_IN", "ERROR", "CONFIRM", "APPROVAL_REQUIRED"),
            LcarsCue.entries.map { it.name }.toSet(),
            "the cue vocabulary changed; it is REQ-39's five voices plus the two requirement-written cues",
        )
    }

    // -- the scan -------------------------------------------------------------------------------

    /** A word-boundary mention of [name] - so `DebugScreen(` matches but `DebugScreenUnconfigured` does not. */
    private fun String.mentions(name: String): Boolean = Regex("\\b${Regex.escape(name)}\\b").containsMatchIn(this)

    /** Every pressable modifier's trailing lambda, brace-balanced, in source order. */
    private fun pressableLambdas(source: String): List<String> = buildList {
        for (modifier in pressableModifiers) {
            var from = 0
            while (true) {
                val at = source.indexOf(modifier, from)
                if (at < 0) break
                val open = source.indexOf('{', at)
                // A pressable modifier is only cue-bearing in its trailing-lambda form; the '{'
                // must be near the call, not the next block three functions down.
                if (open in 0..(at + modifier.length + 8)) add(balancedFrom(source, open))
                from = at + modifier.length
            }
        }
    }

    /** Every `setContent { ... }` root's brace-balanced body, in source order. */
    private fun compositionRoots(source: String): List<String> = buildList {
        var from = 0
        while (true) {
            val at = source.indexOf("setContent", from)
            if (at < 0) break
            val open = source.indexOf('{', at)
            if (open in 0..(at + 40)) {
                val block = balancedFrom(source, open)
                add(block)
                from = open + block.length
            } else {
                from = at + "setContent".length
            }
        }
    }

    /** Every `.kt` under the app module's main source set. */
    private fun appMainSources(): List<File> =
        repoRoot().resolve("app/src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()

    private fun read(repoRelative: String): String = repoRoot().resolve(repoRelative).readText()

    /**
     * The repo root, found by walking up for settings.gradle.kts. Gradle runs `:app:test` with the
     * module dir as the working dir; an IDE may use the repo root. Both resolve here.
     */
    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        error("repo root (settings.gradle.kts) not found from ${File(".").absolutePath}")
    }

    /**
     * The substring from the brace at [open] to its match, inclusive - braces inside double-quoted
     * strings do not count. Comments are already blanked by [blankComments], so only strings and
     * braces remain to track.
     */
    private fun balancedFrom(s: String, open: Int): String {
        require(s[open] == '{') { "expected '{' at $open" }
        var depth = 0
        var inStr = false
        var i = open
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                when (c) {
                    '\\' -> i++ // skip the escaped char
                    '"' -> inStr = false
                }
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> if (--depth == 0) return s.substring(open, i + 1)
                }
            }
            i++
        }
        error("unbalanced brace from $open")
    }

    /**
     * The source with `//` and block comments blanked to spaces of equal length (newlines kept), so
     * a `.clickable` or a brace mentioned in a comment does not fool the scan. String literals are
     * left intact - the brace matcher needs them to know which braces are code.
     */
    private fun blankComments(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        var inStr = false
        while (i < s.length) {
            val c = s[i]
            val n = if (i + 1 < s.length) s[i + 1] else ' '
            when {
                inStr -> {
                    out.append(c)
                    when (c) {
                        '\\' -> if (i + 1 < s.length) { out.append(n); i++ }
                        '"' -> inStr = false
                    }
                    i++
                }
                c == '"' -> { out.append(c); inStr = true; i++ }
                c == '/' && n == '/' -> while (i < s.length && s[i] != '\n') { out.append(' '); i++ }
                c == '/' && n == '*' -> {
                    out.append("  "); i += 2
                    while (i < s.length && !(s[i] == '*' && i + 1 < s.length && s[i + 1] == '/')) {
                        out.append(if (s[i] == '\n') '\n' else ' '); i++
                    }
                    if (i < s.length) { out.append("  "); i += 2 }
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }
}
