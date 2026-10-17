package com.hermesconsole.voiceclient

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * T-05 / REQ-09: what VoiceManager says it is running, off GET /info.
 *
 * The payload fixture is the server's own shape (voicemanager/transport/http.py): engines as
 * strings, models under a nested object, and a tail of fields this client has no use for -
 * which the parser must ignore rather than die on, exactly as the socket protocol does.
 */
class InfoTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.shutdown()
    }

    private fun config() = VoiceManagerConfig(
        host = server.hostName,
        port = server.port,
        apiKey = "unused-by-info",
    )

    @Test
    fun `the info fetch reads engines and models out of the server's own payload shape`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "stt_engine": "whisper",
                  "tts_engine": "openvoice",
                  "models": {"stt": "large-v3", "tts": "openvoice-v2"},
                  "device": "cuda",
                  "tts_sample_rate": 24000,
                  "available_voices": ["default"],
                  "sessions": []
                }
                """.trimIndent(),
            ),
        )

        val info = fetchVoiceManagerInfo(config())

        assertEquals("whisper", info.sttEngine)
        assertEquals("openvoice", info.ttsEngine)
        assertEquals("large-v3", info.models.stt)
        assertEquals("openvoice-v2", info.models.tts)
        assertEquals("/info", server.takeRequest().path, "the fetch asks the server's own /info")
    }

    @Test
    fun `a model the server has not loaded comes back null, not invented`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"stt_engine": "qwen", "tts_engine": "vibevoice", "models": {"stt": null, "tts": null}}""",
            ),
        )

        val info = fetchVoiceManagerInfo(config())

        assertEquals("qwen", info.sttEngine)
        assertNull(info.models.stt)
        assertNull(info.models.tts)
    }

    @Test
    fun `a server that answers badly is a failed fetch, not a fake reading`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val thrown = runCatching { fetchVoiceManagerInfo(config()) }.exceptionOrNull()

        assertTrue(thrown is IOException, "a bad answer must throw, got: $thrown")
        assertTrue("500" in (thrown?.message ?: ""), "the failure names the status it saw")
    }
}
