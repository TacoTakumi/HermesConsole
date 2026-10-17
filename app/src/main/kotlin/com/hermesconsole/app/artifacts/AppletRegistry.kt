package com.hermesconsole.app.artifacts

import androidx.compose.runtime.Composable

/**
 * One artifact type's renderer (D-12).
 *
 * An applet owns everything about its type: what the payload's fields mean, and what its pane looks
 * like. It is handed the whole [Artifact] rather than pre-parsed fields so that a payload's shape
 * stays the applet's own business - the registry and the router never learn it, which is what makes
 * a new type a pure addition (REQ-17).
 */
interface ArtifactApplet {

    /** The declared type this applet renders - the value of the artifact tool call's `kind` argument. */
    val type: String

    /** What the pane is titled in the shell's content column. */
    val title: String get() = type

    /**
     * The pane's content. A malformed payload is the applet's to survive - render a fallback,
     * never throw: no artifact is worth interrupting the spoken reply for (REQ-15).
     */
    @Composable
    fun Render(artifact: Artifact)
}

/**
 * The applet registry: declared type -> renderer (REQ-15, D-12).
 *
 * This is the console's one extension seam for artifacts, and REQ-17 is its design constraint: a
 * new artifact type is a new [ArtifactApplet] source file plus one line in [shipped], and nothing
 * else - zero lines in the shell, zero in [ArtifactRouter]. The registry is immutable on purpose:
 * what the console can render is decided at construction, never mutated mid-run.
 */
class AppletRegistry(applets: List<ArtifactApplet>) {

    // Last registration wins, quietly: two applets claiming one type is a programming error, but
    // not one worth crashing a wall console over.
    private val byType: Map<String, ArtifactApplet> = applets.associateBy { it.type }

    /** The applet registered for [type], or null - the caller decides what a miss means. */
    fun appletFor(type: String): ArtifactApplet? = byType[type]

    companion object {

        /**
         * Every applet the console ships - THE registration list (REQ-17). Adding an artifact type
         * is one line here plus the applet's own file.
         */
        val shipped: List<ArtifactApplet> = listOf(
            ImageApplet,
            MarkdownApplet,
        )

        /** The registry as the console runs it: v1 renders image and markdown, and nothing else (REQ-16). */
        fun standard(): AppletRegistry = AppletRegistry(shipped)
    }
}
