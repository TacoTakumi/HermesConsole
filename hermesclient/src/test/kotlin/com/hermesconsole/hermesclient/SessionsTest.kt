package com.hermesconsole.hermesclient

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * T-22 / REQ-23: the session surface the ready state stands on.
 *
 * The gateway's /api/sessions REST is metadata and history-for-display only - every conversational
 * turn goes through /v1/runs and carries nothing but itself (REQ-11). Context is the gateway's to
 * assemble: the patched /v1/runs (hermes-patch 0002, T-42) reads the session's stored conversation
 * from its own db, so [dispatchInSession] sends the utterance and the session id and no history.
 * These tests pin that the carry stays gone.
 */
class SessionsTest {

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
    fun `the session list is fetched with the bearer key and parsed to the fields the ready state shows`() {
        server.enqueue(
            MockResponse().setBody(
                """
                {"object":"list","data":[
                  {"id":"api_1","title":"galley refit","preview":"last thing said","message_count":12,
                   "last_active":1784170311.13,"input_tokens":900,"has_system_prompt":false},
                  {"id":"run_abc","title":null,"message_count":2,"last_active":null}
                ],"limit":20,"offset":0,"has_more":false}
                """.trimIndent(),
            ),
        )

        val sessions = runBlocking { client.listSessions(limit = 20) }

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/sessions?limit=20", request.path)
        assertEquals("Bearer $TEST_KEY", request.getHeader("Authorization"))

        assertEquals(2, sessions.size)
        assertEquals(HermesSession("api_1", "galley refit", "last thing said", 12, 1784170311.13), sessions[0])
        assertEquals("run_abc", sessions[1].id)
        assertNull(sessions[1].title)
        assertEquals(2, sessions[1].messageCount)
    }

    @Test
    fun `creating a session posts the title and returns the session the gateway made`() {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"object":"hermes.session","session":{"id":"api_1752_ab","title":"console","message_count":0}}""",
            ),
        )

        val session = runBlocking { client.createSession(title = "console") }

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/sessions", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("console", body.getValue("title").jsonPrimitive.content)

        assertEquals("api_1752_ab", session.id)
        assertEquals("console", session.title)
    }

    @Test
    fun `session history keeps only the spoken turns - tool rows and blank rows are not display material`() {
        server.enqueue(
            MockResponse().setBody(
                """
                {"object":"list","session_id":"api_1","data":[
                  {"role":"session_meta","content":null,"tool_call_id":null},
                  {"role":"user","content":"Remember TANGO51.","timestamp":1},
                  {"role":"assistant","content":"","tool_calls":"[...]"},
                  {"role":"assistant","content":null,"tool_calls":"[...]"},
                  {"role":"tool","content":"{\"success\":true}","tool_name":"memory"},
                  {"role":"assistant","content":"OK."}
                ]}
                """.trimIndent(),
            ),
        )

        val history = runBlocking { client.sessionMessages("api_1") }

        assertEquals("/api/sessions/api_1/messages", server.takeRequest().path)
        assertEquals(
            listOf(
                HermesMessage("user", "Remember TANGO51."),
                HermesMessage("assistant", "OK."),
            ),
            history,
        )
    }

    @Test
    fun `each assistant line carries its turn's tool calls - direct and through the tool_call bridge`() {
        server.enqueue(
            MockResponse().setBody(
                """
                {"object":"list","data":[
                  {"role":"user","content":"Show a doc."},
                  {"role":"assistant","content":null,"tool_calls":[{"type":"function","function":{"name":"tool_describe","arguments":"{\"names\":[\"render_artifact\"]}"}}]},
                  {"role":"tool","content":"{}"},
                  {"role":"assistant","content":null,"tool_calls":[{"type":"function","function":{"name":"tool_call","arguments":"{\"calls\":[{\"name\":\"render_artifact\",\"arguments\":{\"kind\":\"markdown\",\"markdown\":\"# Hi\"}}]}"}}]},
                  {"role":"tool","content":"{}"},
                  {"role":"assistant","content":"The doc is up."},
                  {"role":"user","content":"Now a picture."},
                  {"role":"assistant","content":null,"tool_calls":[{"type":"function","function":{"name":"render_artifact","arguments":"{\"kind\":\"image\",\"url\":\"https://x/y.png\"}"}}]},
                  {"role":"assistant","content":"Here it is."}
                ]}
                """.trimIndent(),
            ),
        )

        val history = runBlocking { client.sessionMessages("api_1") }

        assertEquals(listOf("user", "assistant", "user", "assistant"), history.map { it.role })
        assertEquals(
            listOf("tool_describe", "render_artifact"),
            history[1].toolCalls.map { it.name },
            "the bridge call is listed as the tool it called",
        )
        assertEquals("# Hi", (history[1].toolCalls[1].args["markdown"] as JsonPrimitive).content)
        assertEquals(listOf("render_artifact"), history[3].toolCalls.map { it.name }, "a user line starts a new turn")
        assertEquals("https://x/y.png", (history[3].toolCalls[0].args["url"] as JsonPrimitive).content)
    }

    @Test
    fun `dispatching in a session sends only the utterance and the session id - context is the gateway's`() {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"run_9","status":"queued"}"""))

        val runId = runBlocking { client.dispatchInSession("What was the codeword?", "api_1") }

        assertEquals("run_9", runId)
        assertEquals(1, server.requestCount, "a session dispatch is one request - no history fetch before it")
        val run = server.takeRequest()
        assertEquals("POST", run.method)
        assertEquals("/v1/runs", run.path)
        val body = Json.parseToJsonElement(run.body.readUtf8()).jsonObject
        assertEquals("api_1", body.getValue("session_id").jsonPrimitive.content)
        assertEquals("What was the codeword?", body.getValue("input").jsonPrimitive.content)
        assertNull(body["conversation_history"], "the carry is gone - the patched gateway reads its own db")
    }

    @Test
    fun `the probe asks for the model list and settles reachability`() {
        server.enqueue(MockResponse().setBody("""{"object":"list","data":[{"id":"hermes"}]}"""))

        runBlocking { client.probe() }

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/models", request.path)
        assertEquals("Bearer $TEST_KEY", request.getHeader("Authorization"))
    }

    @Test
    fun `a refused probe throws - a gateway answering errors is not a healthy gateway`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        val failure = assertThrows(HermesException.Http::class.java) {
            runBlocking { client.probe() }
        }
        assertEquals(500, failure.code)
    }

    @Test
    fun `a rejected key is an auth failure on every session call, and the key is not in the message`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))

        val failure = assertThrows(HermesException.Auth::class.java) {
            runBlocking { client.listSessions() }
        }
        assertEquals(401, failure.code)
        assertTrue(TEST_KEY !in (failure.message ?: ""))
    }
}
