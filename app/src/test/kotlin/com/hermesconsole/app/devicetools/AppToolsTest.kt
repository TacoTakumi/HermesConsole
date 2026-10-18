package com.hermesconsole.app.devicetools

import com.hermesconsole.app.DrawerApp
import com.hermesconsole.hermesclient.DeviceTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The two app tools the console runs for the model: list_apps shows exactly the drawer's list, and
 * launch_app launches only on one clear match. Ambiguity and misses go back to the model as text,
 * with nothing launched, so it can ask or try again.
 */
class AppToolsTest {

    private val self = "com.hermesconsole"

    private fun app(label: String, pkg: String) = DrawerApp(label = label, packageName = pkg, activityName = "$pkg.Main")

    /** The raw launcher query, unordered, with the console itself and a label-less ghost in it. */
    private val resolved = listOf(
        app("YouTube Music", "com.google.android.apps.youtube.music"),
        app("Calculator", "com.sec.android.app.popupcalculator"),
        app("Hermes Console", self),
        app("", "com.example.ghost"),
        app("YouTube", "com.google.android.youtube"),
        app("Camera", "com.sec.android.app.camera"),
    )

    private val launched = mutableListOf<DrawerApp>()

    private val tools = appTools(resolved = { resolved }, selfPackage = self, launch = { launched += it })

    private fun tool(name: String): DeviceTool = tools.single { it.name == name }

    private fun run(name: String, args: Map<String, String> = emptyMap()): JsonObject = runBlocking {
        val text = tool(name).run(JsonObject(args.mapValues { JsonPrimitive(it.value) }))
        Json.parseToJsonElement(text).jsonObject
    }

    private fun labels(obj: JsonObject, key: String) = obj[key]!!.jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun `the console offers exactly list_apps and launch_app, launch_app requiring a name`() {
        assertEquals(listOf("list_apps", "launch_app"), tools.map { it.name })
        val required = tool("launch_app").parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("name"), required)
    }

    @Test
    fun `list_apps returns the drawer's list with label and package, in drawer order`() {
        val apps = run("list_apps")["apps"]!!.jsonArray.map { it.jsonObject }

        assertEquals(
            listOf("Calculator", "Camera", "YouTube", "YouTube Music"),
            apps.map { it["label"]!!.jsonPrimitive.content },
        )
        assertEquals("com.google.android.youtube", apps[2]["package"]!!.jsonPrimitive.content)
        assertTrue(launched.isEmpty())
    }

    @Test
    fun `an exact label wins over a longer label that contains it`() {
        val result = run("launch_app", mapOf("name" to "youtube"))

        assertEquals("YouTube", result["launched"]!!.jsonPrimitive.content)
        assertEquals(listOf("com.google.android.youtube"), launched.map { it.packageName })
    }

    @Test
    fun `one contained match launches, whatever the case`() {
        val result = run("launch_app", mapOf("name" to "CALC"))

        assertEquals("Calculator", result["launched"]!!.jsonPrimitive.content)
        assertEquals(listOf("Calculator"), launched.map { it.label })
    }

    @Test
    fun `several matches return the candidates and launch nothing`() {
        val result = run("launch_app", mapOf("name" to "tube"))

        assertTrue("launched" !in result)
        assertEquals(listOf("YouTube", "YouTube Music"), labels(result, "candidates"))
        assertTrue(launched.isEmpty())
    }

    @Test
    fun `no match returns the full list and launches nothing`() {
        val result = run("launch_app", mapOf("name" to "chess"))

        assertTrue("launched" !in result)
        assertEquals(listOf("Calculator", "Camera", "YouTube", "YouTube Music"), labels(result, "apps"))
        assertTrue(launched.isEmpty())
    }
}
