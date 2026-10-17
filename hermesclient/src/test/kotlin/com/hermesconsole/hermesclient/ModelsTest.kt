package com.hermesconsole.hermesclient

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * T-06 / REQ-07: the gateway's advertised identity, off GET /v1/models.
 *
 * The gateway advertises its active profile as the primary model id - first in the list, with
 * any configured route aliases behind it (api_server.py `_handle_models`). The profile readout
 * wants exactly that primary name: never an alias, and never a guess when the gateway is gone.
 */
class ModelsTest {

    private lateinit var server: MockWebServer
    private lateinit var client: HermesClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = HermesClient(server.hermesConfig(), OkHttpClient())
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `the advertised profile is the primary model id, fetched with the bearer key`() {
        server.enqueue(
            MockResponse().setBody(
                """
                {"object":"list","data":[
                  {"id":"tactical","object":"model","created":1755100000,"owned_by":"hermes","permission":[],"root":"tactical","parent":null},
                  {"id":"fast","object":"model","created":1755100000,"owned_by":"hermes","permission":[],"root":"claude-haiku-4-5","parent":"tactical"}
                ]}
                """.trimIndent(),
            ),
        )

        val profile = runBlocking { client.advertisedProfile() }

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/models", request.path)
        assertEquals("Bearer $TEST_KEY", request.getHeader("Authorization"))
        assertEquals("tactical", profile, "the profile is the primary - the aliases behind it are not it")
    }

    @Test
    fun `a gateway advertising nothing yields null, not an invented name`() {
        server.enqueue(MockResponse().setBody("""{"object":"list","data":[]}"""))

        assertNull(runBlocking { client.advertisedProfile() })
    }

    @Test
    fun `a gateway answering errors is a failed fetch - the caller owns the placeholder`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        val failure = assertThrows(HermesException.Http::class.java) {
            runBlocking { client.advertisedProfile() }
        }
        assertEquals(500, failure.code)
    }

    // -- the configured model, off GET /api/model/options (2026-08-14) ------------------------

    @Test
    fun `the configured model is the gateway's current selection, fetched with the bearer key`() {
        // The real payload is the whole provider catalog; only the top-level selection matters.
        server.enqueue(
            MockResponse().setBody(
                """
                {"providers":[{"slug":"custom","name":"Custom","is_current":true,"models":[]}],
                 "model":"Qwen3.6-27B-MTP-Q8","provider":"custom:llamacpp"}
                """.trimIndent(),
            ),
        )

        val model = runBlocking { client.configuredModel() }

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/model/options", request.path)
        assertEquals("Bearer $TEST_KEY", request.getHeader("Authorization"))
        assertEquals("Qwen3.6-27B-MTP-Q8", model)
    }

    @Test
    fun `a gateway with nothing configured yields null, not an invented model`() {
        server.enqueue(MockResponse().setBody("""{"providers":[],"model":"","provider":""}"""))

        assertNull(runBlocking { client.configuredModel() })
    }

    @Test
    fun `a gateway answering errors on the options fetch is a failed fetch, like the profile's`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        val failure = assertThrows(HermesException.Http::class.java) {
            runBlocking { client.configuredModel() }
        }
        assertEquals(500, failure.code)
    }
}
