package com.hermesconsole.app.artifacts

import androidx.compose.runtime.Composable
import com.hermesconsole.hermesclient.HermesEvent
import com.hermesconsole.hermesclient.HermesMessage
import com.hermesconsole.hermesclient.HermesToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T-24 / REQ-15, REQ-17: artifacts route to panes by declared type, and the registry is a genuine
 * extension seam.
 *
 * The contract under test is the wire's: an artifact is a `tool.started` for the artifact tool,
 * whose args dict carries the payload with its type under `kind` (REQ-37) - so the fixtures here
 * spell both out as literals, pinning the contract rather than echoing the router's constants.
 * The failure REQ-15 guards against is the quiet one: an artifact nobody registered must cost
 * nothing at all - no crash, no blank pane - because the reply it rides beside is still being
 * spoken. And REQ-17 is asserted as a diff shape: the dummy type below is an applet source plus a
 * registration, with the router and registry used exactly as shipped.
 */
class ArtifactRouterTest {

    private fun artifactArgs(kind: String?, vararg fields: Pair<String, String>): JsonObject =
        buildJsonObject {
            if (kind != null) put("kind", kind)
            fields.forEach { (key, value) -> put(key, value) }
        }

    private fun toolStarted(args: JsonObject, tool: String = "render_artifact", runId: String = "run-1") =
        HermesEvent.ToolStarted(runId = runId, tool = tool, preview = "", args = args)

    @Test
    fun `an artifact tool call routes to the applet registered for its declared type`() {
        val router = ArtifactRouter(AppletRegistry.standard())

        router.onEvent(toolStarted(artifactArgs("image", "url" to "http://x/y.png")))

        val pane = router.panes.value.single()
        assertSame(ImageApplet, pane.applet, "the declared type picks the applet")
        assertEquals("image", pane.artifact.type)
        assertEquals("run-1", pane.artifact.runId)
        // The payload survives whole: what `url` means is the applet's business (T-25), not the router's.
        assertEquals("http://x/y.png", (pane.artifact.args["url"] as? JsonPrimitive)?.content)
    }

    @Test
    fun `an unregistered artifact type is skipped - no crash, no blank pane`() {
        val router = ArtifactRouter(AppletRegistry.standard())

        router.onEvent(toolStarted(artifactArgs("hologram")))

        assertTrue(router.panes.value.isEmpty(), "an unroutable artifact must produce no pane at all")
    }

    @Test
    fun `a tool call that is not the artifact tool is never routed`() {
        val router = ArtifactRouter(AppletRegistry.standard())

        // A shell command whose args happen to carry a `kind` - not an artifact, not a pane.
        router.onEvent(toolStarted(artifactArgs("image"), tool = "bash"))

        assertTrue(router.panes.value.isEmpty())
    }

    @Test
    fun `an artifact with no declared type - or one that is not a string - is skipped`() {
        val router = ArtifactRouter(AppletRegistry.standard())

        router.onEvent(toolStarted(artifactArgs(null, "url" to "http://x/y.png")))
        router.onEvent(toolStarted(buildJsonObject { putJsonObject("kind") { put("nested", "image") } }))

        assertTrue(router.panes.value.isEmpty(), "a payload that cannot declare a type cannot be routed")
    }

    @Test
    fun `a run's artifacts stack - and a new run's first artifact replaces them`() {
        val router = ArtifactRouter(AppletRegistry.standard())

        router.onEvent(toolStarted(artifactArgs("image"), runId = "run-1"))
        router.onEvent(toolStarted(artifactArgs("markdown"), runId = "run-1"))
        assertEquals(listOf<ArtifactApplet>(ImageApplet, MarkdownApplet), router.panes.value.map { it.applet })

        // The next turn's first artifact takes the screen; until it arrives the old panes stand,
        // so the console is never showing a blank between turns.
        router.onEvent(toolStarted(artifactArgs("markdown"), runId = "run-2"))
        assertEquals(1, router.panes.value.size, "the last turn's panes are not this turn's")
        assertEquals("run-2", router.panes.value.single().artifact.runId)
    }

    @Test
    fun `a MEDIA url becomes an image artifact, a non-url target is skipped`() {
        val router = ArtifactRouter(AppletRegistry.standard())

        router.onMedia("run-1", "/home/you/.hermes/cache/a.png")
        assertTrue(router.panes.value.isEmpty(), "the tablet cannot fetch a gateway path")

        router.onMedia("run-1", "https://example.com/a.jpg")
        val pane = router.panes.value.single()
        assertSame(ImageApplet, pane.applet)
        assertEquals("https://example.com/a.jpg", (pane.artifact.args["url"] as? JsonPrimitive)?.content)
    }

    @Test
    fun `restore puts back the last turn that showed artifacts, calls and MEDIA alike`() {
        val router = ArtifactRouter(AppletRegistry.standard())
        router.onEvent(toolStarted(artifactArgs("markdown"), runId = "run-old"))

        router.restore(
            listOf(
                HermesMessage("user", "doc"),
                HermesMessage("assistant", "Up.", listOf(HermesToolCall("render_artifact", artifactArgs("markdown")))),
                HermesMessage("user", "picture"),
                HermesMessage(
                    "assistant",
                    "Here.\nMEDIA:https://x/y.png",
                    listOf(HermesToolCall("web_search"), HermesToolCall("render_artifact", artifactArgs("image", "url" to "https://x/a.png"))),
                ),
                HermesMessage("user", "thanks"),
                HermesMessage("assistant", "You are welcome."),
            ),
        )

        val panes = router.panes.value
        assertEquals(listOf<ArtifactApplet>(ImageApplet, ImageApplet), panes.map { it.applet }, "the call, then the MEDIA image")
        assertEquals(
            listOf("https://x/a.png", "https://x/y.png"),
            panes.map { (it.artifact.args["url"] as JsonPrimitive).content },
        )
        assertTrue(panes.none { it.artifact.runId == "run-old" }, "the session left behind is not restored")
    }

    @Test
    fun `restore leaves the wall blank for a session that never showed an artifact`() {
        val router = ArtifactRouter(AppletRegistry.standard())
        router.onEvent(toolStarted(artifactArgs("markdown")))

        router.restore(listOf(HermesMessage("user", "hi"), HermesMessage("assistant", "Hello.")))

        assertTrue(router.panes.value.isEmpty())
    }

    @Test
    fun `clear drops every pane`() {
        val router = ArtifactRouter(AppletRegistry.standard())
        router.onEvent(toolStarted(artifactArgs("markdown"), runId = "run-1"))

        router.clear()

        assertTrue(router.panes.value.isEmpty())
    }

    /**
     * REQ-17, as a diff shape: the whole of a third artifact type is (a) its own applet source -
     * the object below - and (b) one registration. The router, the registry class and the shipped
     * applets are used exactly as they stand, which is the test's proof that adding a type changes
     * zero lines in any of them.
     */
    @Test
    fun `a third artifact type is one registration plus its own source - zero lines in shell or router`() {
        // (a) The applet's own source.
        val starChart = object : ArtifactApplet {
            override val type = "starchart"
            override val title = "star chart"

            @Composable
            override fun Render(artifact: Artifact) = Unit // its pane, its business
        }
        // (b) The registration.
        val router = ArtifactRouter(AppletRegistry(AppletRegistry.shipped + starChart))

        router.onEvent(toolStarted(artifactArgs("starchart", "sector" to "001")))

        val pane = router.panes.value.single()
        assertSame(starChart, pane.applet, "the new type routes end to end to its own renderer")
        assertEquals("star chart", pane.title)
        assertEquals("001", (pane.artifact.args["sector"] as? JsonPrimitive)?.content)

        // And the shipped types still route beside it: an extension, not a replacement.
        router.onEvent(toolStarted(artifactArgs("image")))
        assertEquals(2, router.panes.value.size)
    }
}
