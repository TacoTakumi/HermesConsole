package com.hermesconsole.hermesclient

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

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
    fun `a key that cannot go in a header is refused without quoting it`() {
        for (bad in listOf("made-up\nkey", "made-up-\u00e9")) {
            val error = assertThrows<IllegalArgumentException> {
                HermesConfig(host = "gateway.local", port = 8643, apiKey = bad)
            }
            assertFalse(error.message.orEmpty().contains(bad), error.message)
        }
    }

    @Test
    fun `printable ASCII and tab are a usable key`() {
        HermesConfig(host = "gateway.local", port = 8643, apiKey = "made-up ~key!")
        HermesConfig(host = "gateway.local", port = 8643, apiKey = "made-up\tkey")
    }

    @Test
    fun `equality and copy still see the key`() {
        assertEquals(config, HermesConfig(host = "gateway.local", port = 8643, apiKey = key))
        assertNotEquals(config, config.copy(apiKey = "other-made-up-key"))
        assertEquals("other-made-up-key", config.copy(apiKey = "other-made-up-key").apiKey)
    }

    @Test
    fun `a name, an IPv4 and an IPv6 address are dialable hosts`() {
        for (host in listOf("gateway.local", "10.0.0.5", "::1", "[fe80::1]")) {
            assertTrue(isDialableHost(host), host)
        }
    }

    @Test
    fun `a host with a space or no host at all is not dialable`() {
        // Past this check, OkHttp would throw from HttpUrl.Builder.host() the moment a client dials.
        assertFalse(isDialableHost("my host"))
        assertFalse(isDialableHost(""))
    }
}
