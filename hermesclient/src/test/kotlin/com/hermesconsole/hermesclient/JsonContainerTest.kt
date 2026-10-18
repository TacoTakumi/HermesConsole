package com.hermesconsole.hermesclient

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Text that is itself a JSON object or array parses; anything else is not JSON to a reader. */
class JsonContainerTest {

    @Test
    fun `an object or an array parses, leading whitespace and all`() {
        val obj = parseJsonContainer("""  {"output": "hi", "return_code": 0}""")
        assertTrue(obj is JsonObject)
        assertEquals("hi", (obj as JsonObject)["output"]!!.jsonPrimitive.content)
        assertTrue(parseJsonContainer("[1, 2]") is JsonArray)
    }

    @Test
    fun `a scalar, plain text, a broken container and blank text are not containers`() {
        assertNull(parseJsonContainer("42"))
        assertNull(parseJsonContainer("\"quoted\""))
        assertNull(parseJsonContainer("total 12 files"))
        assertNull(parseJsonContainer("""{"output": "API_KEY=*** "return_code": 0}"""))
        assertNull(parseJsonContainer("   "))
    }
}
