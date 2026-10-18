package com.hermesconsole.app.devicetools

import com.hermesconsole.hermesclient.DeviceTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The volume tools: get_volume reads the media stream and the console's own voice level in percent,
 * and set_volume sets one of them. Media lands on the nearest of the stream's steps and the reply
 * reports that step, so the model never claims a level the tablet does not have. Voice has a floor,
 * because a console at 0 answers in silence and nobody can hear it say how to turn it back up.
 */
class VolumeToolsTest {

    private class FakeMedia(override var index: Int, override val max: Int = 15) : MediaVolume

    private val media = FakeMedia(index = 9)
    private var voice = 80

    private val tools = volumeTools(
        media = media,
        voicePercent = { voice },
        setVoicePercent = { voice = it },
    )

    private fun tool(name: String): DeviceTool = tools.single { it.name == name }

    private fun run(name: String, vararg args: Pair<String, JsonElement>): JsonObject = runBlocking {
        Json.parseToJsonElement(tool(name).run(JsonObject(args.toMap()))).jsonObject
    }

    private fun set(vararg args: Pair<String, JsonElement>) = run("set_volume", *args)

    private fun refused(vararg args: Pair<String, JsonElement>) {
        val mediaBefore = media.index
        val voiceBefore = voice
        assertThrows<IllegalArgumentException> { set(*args) }
        assertEquals(mediaBefore, media.index, "a refused call leaves media alone")
        assertEquals(voiceBefore, voice, "and voice")
    }

    private fun JsonObject.int(key: String) = this[key]!!.jsonPrimitive.int

    private val mediaTarget = "target" to JsonPrimitive("media")
    private val voiceTarget = "target" to JsonPrimitive("voice")
    private fun percent(value: Int) = "percent" to JsonPrimitive(value)
    private fun direction(value: String) = "direction" to JsonPrimitive(value)

    // -- schema and refusals --------------------------------------------------------------------

    @Test
    fun `the console offers get_volume with no arguments and set_volume with a target enum`() {
        assertEquals(listOf("get_volume", "set_volume"), tools.map { it.name })
        assertTrue("required" !in tool("get_volume").parameters)

        val params = tool("set_volume").parameters
        val properties = params["properties"]!!.jsonObject
        val targets = properties["target"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("media", "voice"), targets)
        val directions = properties["direction"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("up", "down"), directions)
        assertEquals(listOf("target"), params["required"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `set_volume refuses both or neither of percent and direction`() {
        refused(mediaTarget, percent(50), direction("up"))
        refused(mediaTarget)
        refused(voiceTarget, percent(50), direction("down"))
        refused(voiceTarget)
    }

    @Test
    fun `set_volume refuses an unknown target, direction or out of range percent`() {
        refused("target" to JsonPrimitive("ringer"), percent(50))
        refused(percent(50))
        refused(mediaTarget, direction("sideways"))
        refused(mediaTarget, percent(101))
        refused(voiceTarget, percent(-1))
    }

    @Test
    fun `a null percent counts as left out`() {
        // Some models send every optional field, with null for the ones they do not use.
        media.index = 9
        val result = set(mediaTarget, "percent" to JsonNull, direction("down"))
        assertEquals(8, media.index)
        assertEquals(53, result.int("percent"))
    }

    @Test
    fun `a null direction counts as left out`() {
        val result = set(voiceTarget, percent(40), "direction" to JsonNull)
        assertEquals(40, voice)
        assertEquals(40, result.int("percent"))
    }

    @Test
    fun `both null is still neither`() {
        refused(mediaTarget, "percent" to JsonNull, "direction" to JsonNull)
    }

    // -- get_volume -----------------------------------------------------------------------------

    @Test
    fun `get_volume reports media from the stream step and voice from the console level`() {
        val result = run("get_volume")
        assertEquals(60, result.int("media"))
        assertEquals(80, result.int("voice"))
    }

    // -- media ----------------------------------------------------------------------------------

    @Test
    fun `media percent sets the nearest step and reports that step`() {
        val result = set(mediaTarget, percent(50))
        assertEquals(8, media.index)
        assertEquals(53, result.int("percent"))
        assertEquals("media", result["target"]!!.jsonPrimitive.content)
    }

    @Test
    fun `media down moves ten points from the current step`() {
        media.index = 9
        val result = set(mediaTarget, direction("down"))
        assertEquals(8, media.index)
        assertEquals(53, result.int("percent"))
    }

    @Test
    fun `media can go to zero, which is mute`() {
        val result = set(mediaTarget, percent(0))
        assertEquals(0, media.index)
        assertEquals(0, result.int("percent"))
        assertNull(result["note"])
    }

    @Test
    fun `media up at the top stays at 100`() {
        media.index = 15
        val result = set(mediaTarget, direction("up"))
        assertEquals(15, media.index)
        assertEquals(100, result.int("percent"))
    }

    // -- voice ----------------------------------------------------------------------------------

    @Test
    fun `voice percent sets the console level`() {
        val result = set(voiceTarget, percent(40))
        assertEquals(40, voice)
        assertEquals(40, result.int("percent"))
        assertNull(result["note"])
    }

    @Test
    fun `voice below the floor sets 10 and says so`() {
        val result = set(voiceTarget, percent(0))
        assertEquals(10, voice)
        assertEquals(10, result.int("percent"))
        assertTrue("10" in result["note"]!!.jsonPrimitive.content)
    }

    @Test
    fun `voice up from 95 stops at 100`() {
        voice = 95
        val result = set(voiceTarget, direction("up"))
        assertEquals(100, voice)
        assertEquals(100, result.int("percent"))
    }
}
