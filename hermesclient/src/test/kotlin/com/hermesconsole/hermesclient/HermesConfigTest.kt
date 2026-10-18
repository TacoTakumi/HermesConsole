package com.hermesconsole.hermesclient

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A config ends up in log lines and exception messages; its toString must never carry the key. */
class HermesConfigTest {

    private val key = "made-up-gateway-key-123"
    private val config = HermesConfig(host = "gateway.local", port = 8643, apiKey = key)

    @Test
    fun `toString shows host and port and masks the key`() {
        val text = config.toString()
        assertTrue(text.contains("gateway.local"), text)
        assertTrue(text.contains("8643"), text)
        assertTrue(text.contains("***"), text)
        assertFalse(text.contains(key), text)
    }

    @Test
    fun `equality and copy still see the key`() {
        assertEquals(config, HermesConfig(host = "gateway.local", port = 8643, apiKey = key))
        assertNotEquals(config, config.copy(apiKey = "other-made-up-key"))
        assertEquals("other-made-up-key", config.copy(apiKey = "other-made-up-key").apiKey)
    }
}
