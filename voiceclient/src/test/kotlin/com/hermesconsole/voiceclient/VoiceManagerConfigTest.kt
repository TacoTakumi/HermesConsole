package com.hermesconsole.voiceclient

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A config ends up in log lines and exception messages; its toString must never carry the token. */
class VoiceManagerConfigTest {

    private val token = "vmp_made-up-token-123"
    private val config = VoiceManagerConfig(host = "voice.local", port = 8000, apiKey = token)

    @Test
    fun `toString shows host and port and masks the token`() {
        val text = config.toString()
        assertTrue(text.contains("voice.local"), text)
        assertTrue(text.contains("8000"), text)
        assertTrue(text.contains("***"), text)
        assertFalse(text.contains(token), text)
    }

    @Test
    fun `equality and copy still see the token`() {
        assertEquals(config, VoiceManagerConfig(host = "voice.local", port = 8000, apiKey = token))
        assertNotEquals(config, config.copy(apiKey = "vmp_other-made-up-token"))
        assertEquals("vmp_other-made-up-token", config.copy(apiKey = "vmp_other-made-up-token").apiKey)
    }
}
