package com.hermesconsole.hermesclient

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * T-11 / REQ-11: a turn starts with POST /v1/runs, Bearer-authenticated, and comes back with the
 * run_id everything else in the turn hangs off - the SSE stream (T-11), the stop (T-12), the
 * approval (T-12). A dispatch that returns no run_id is a dead turn, so it fails loudly here rather
 * than streaming nothing later.
 */
class RunDispatchTest {

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
    fun `dispatch posts the turn to v1 runs with bearer auth and returns the run id`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"run_id":"run_abc123","status":"started"}"""),
        )

        val runId = client.dispatch("what is the pressure in the plasma conduit?", sessionId = "sess-1")

        assertEquals("run_abc123", runId)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/runs", request.path)
        assertEquals("Bearer $TEST_KEY", request.getHeader("Authorization"))
        assertEquals("application/json", request.getHeader("Content-Type")?.substringBefore(';'))

        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("what is the pressure in the plasma conduit?", body["input"]?.jsonPrimitive?.content)
        assertEquals("sess-1", body["session_id"]?.jsonPrimitive?.content)
        // Absent options stay off the wire rather than going out as explicit nulls - the gateway
        // reads `instructions` for presence, not for null-ness.
        assertNull(body["instructions"])
    }

    @Test
    fun `a rejected key is a distinct terminal failure, and never echoes the key`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Unauthorized"}}"""))

        val failure = assertThrows(HermesException.Auth::class.java) {
            runBlocking { client.dispatch("hello") }
        }
        assertEquals(401, failure.code)
        // REQ-28: a credential is never logged, and an exception message is a log waiting to happen.
        assertEquals(false, failure.message.orEmpty().contains(TEST_KEY))
    }

    @Test
    fun `a dispatch that comes back without a run id fails rather than starting a dead turn`() {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"status":"started"}"""))

        assertThrows(HermesException.Protocol::class.java) {
            runBlocking { client.dispatch("hello") }
        }
    }

    @Test
    fun `a server error surfaces its status`() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("busy"))

        val failure = assertThrows(HermesException.Http::class.java) {
            runBlocking { client.dispatch("hello") }
        }
        assertEquals(503, failure.code)
    }
}
