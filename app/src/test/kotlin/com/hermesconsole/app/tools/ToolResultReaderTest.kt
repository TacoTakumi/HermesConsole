package com.hermesconsole.app.tools

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T-01 / REQ-04..06, REQ-08: the pure reader that turns a `tool.completed` result into a readable
 * display model - never a JSON blob (D-03).
 *
 * The fixtures spell the wire out as literals. A `tool.completed` result is a [JsonElement]: often a
 * *string* that is itself JSON (execute_code double-encodes its result), sometimes a bare string,
 * sometimes a structured object. The reader's contract is that all three come out as human text, that
 * an unrecognised structure never leaks its envelope, and that a runaway body is bounded - and that
 * none of it ever throws on the tap it will ride (a total, guarded read).
 */
class ToolResultReaderTest {

    /** A result exactly as execute_code delivers it: a JSON *string* whose content is itself JSON. */
    private fun doubleEncoded(vararg fields: Pair<String, String>): JsonPrimitive {
        val inner = buildJsonObject { fields.forEach { (k, v) -> put(k, v) } }
        return JsonPrimitive(inner.toString())
    }

    @Test
    fun `a double-encoded execute_code result yields its stdout as the body`() {
        val result = doubleEncoded("output" to "hello\nworld", "return_code" to "0")

        val display = ToolResultReader.read(result, isError = false)

        assertEquals("hello\nworld", display.body, "the reader un-double-encodes and reads the output field")
        assertTrue(display.summary.isNotBlank(), "a readable result gets a non-empty single-line summary")
        assertFalse(display.truncated)
    }

    @Test
    fun `a bare non-JSON string result is used verbatim`() {
        val result = JsonPrimitive("all done, 3 files changed")

        val display = ToolResultReader.read(result, isError = false)

        assertEquals("all done, 3 files changed", display.body)
        assertTrue(display.summary.isNotBlank())
    }

    @Test
    fun `a result under another recognised key renders that value`() {
        val result = buildJsonObject { put("text", "the readable text") }

        val display = ToolResultReader.read(result, isError = false)

        assertEquals("the readable text", display.body, "the field heuristic reads by key set, not tool name")
    }

    @Test
    fun `a structured result with no recognised text key renders its string values, never a JSON envelope`() {
        val result = buildJsonObject {
            put("alpha", "first value")
            put("beta", "second value")
        }

        val display = ToolResultReader.read(result, isError = false)

        assertTrue(display.body.contains("first value"), "the string values are shown")
        assertTrue(display.body.contains("second value"))
        // The one thing it must never be: a serialized JSON envelope on screen (REQ-06, D-03).
        assertFalse(display.body.contains("{"), "no opening brace of a JSON envelope")
        assertFalse(display.body.contains("\"alpha\""), "no quoted JSON keys")
    }

    @Test
    fun `a body over the display cap is bounded and marked truncated`() {
        val result = JsonPrimitive("x".repeat(ToolResultReader.BODY_CAP * 2))

        val display = ToolResultReader.read(result, isError = false)

        assertTrue(display.truncated, "over-cap output sets the truncated flag")
        assertTrue(
            display.body.length <= ToolResultReader.BODY_CAP + 64,
            "the body is bounded near the cap, not unbounded (was ${display.body.length})",
        )
        assertTrue(display.body.length > 0)
    }

    @Test
    fun `a body under the cap renders in full with no marker`() {
        val result = JsonPrimitive("short and sweet")

        val display = ToolResultReader.read(result, isError = false)

        assertEquals("short and sweet", display.body)
        assertFalse(display.truncated)
    }

    @Test
    fun `an error result reads its error text`() {
        val result = buildJsonObject { put("error", "boom: it failed") }

        val display = ToolResultReader.read(result, isError = true)

        assertEquals("boom: it failed", display.body)
        assertTrue(display.summary.isNotBlank())
    }

    @Test
    fun `malformed and empty results never throw`() {
        assertDoesNotThrow {
            ToolResultReader.read(null, isError = false)
            ToolResultReader.read(JsonNull, isError = false)
            ToolResultReader.read(buildJsonObject { }, isError = false)
            ToolResultReader.read(JsonPrimitive("{ not: valid json"), isError = false)
        }
    }
}
