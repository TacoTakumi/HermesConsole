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
 * T-12 / REQ-14: console-created sessions carry a title, and a collision costs a retry, never
 * the creation.
 *
 * The gateway enforces title uniqueness and rejects the whole create on a duplicate (400,
 * `invalid_title`, "Title already in use by session ..."). Two consoles - or one console twice
 * inside a minute - will collide on a date/time title, so the create disambiguates with a
 * number and goes again; a collision space somehow exhausted falls back to creating untitled
 * (REQ-14's permitted fallback), because a session must exist at the end of this call.
 */
class TitledCreateTest {

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

    private fun created(id: String, title: String?) = MockResponse().setResponseCode(201).setBody(
        """{"object":"hermes.session","session":{"id":"$id","title":${title?.let { "\"$it\"" } ?: "null"},"message_count":0}}""",
    )

    private fun duplicateTitle() = MockResponse().setResponseCode(400).setBody(
        """{"error":{"message":"Title already in use by session api_1","type":"invalid_request_error","code":"invalid_title"}}""",
    )

    private fun sentTitle(): String? =
        Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["title"]?.jsonPrimitive?.content

    @Test
    fun `the create carries the title, and the stored title comes back with the session`() {
        server.enqueue(created("api_7", "Aug 1 2026 09:30"))

        val session = runBlocking { client.createSessionDisambiguated("Aug 1 2026 09:30") }

        assertEquals("Aug 1 2026 09:30", sentTitle())
        assertEquals("Aug 1 2026 09:30", session.title, "the stored title matches what was sent")
    }

    @Test
    fun `a duplicate rejection retries numbered, and creation succeeds with no error surfacing`() {
        server.enqueue(duplicateTitle())
        server.enqueue(created("api_8", "Aug 1 2026 09:30 (2)"))

        val session = runBlocking { client.createSessionDisambiguated("Aug 1 2026 09:30") }

        assertEquals("Aug 1 2026 09:30", sentTitle(), "the first try is the bare title")
        assertEquals("Aug 1 2026 09:30 (2)", sentTitle(), "the retry disambiguates with a number")
        assertEquals("api_8", session.id)
        assertEquals("Aug 1 2026 09:30 (2)", session.title)
    }

    @Test
    fun `a 400 that is not a duplicate throws - only the collision earns a retry`() {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"error":{"message":"Invalid title","type":"invalid_request_error","code":"invalid_title"}}""",
            ),
        )

        assertThrows(HermesException.Http::class.java) {
            runBlocking { client.createSessionDisambiguated("Aug 1 2026 09:30") }
        }
        assertEquals(1, server.requestCount, "no blind retry against a gateway saying no for its own reasons")
    }

    @Test
    fun `an exhausted collision space falls back to creating untitled - a session must exist`() {
        repeat(5) { server.enqueue(duplicateTitle()) }
        server.enqueue(created("api_9", null))

        val session = runBlocking { client.createSessionDisambiguated("Aug 1 2026 09:30", attempts = 5) }

        repeat(5) { server.takeRequest() }
        assertNull(sentTitle(), "the last resort creates untitled rather than failing the visit")
        assertEquals("api_9", session.id)
    }
}
