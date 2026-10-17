package com.hermesconsole.hermesclient

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * T-11 / REQ-11: the SSE stream of a run, read as typed events.
 *
 * Every pane in the console hangs off this stream - the spoken reply off `message.delta`, the
 * artifacts off `tool.started`/`tool.completed` args and result (REQ-37), the approval pane off
 * `approval.request`, the turn's end off `run.completed`/`failed`/`cancelled`. So the parse is
 * asserted field by field against the payloads the patched gateway actually emits, in the order it
 * emits them.
 */
class EventStreamTest {

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

    private fun events(runId: String = RUN) = runBlocking {
        withTimeout(5_000) { client.events(runId).toList() }
    }

    @Test
    fun `the stream is read as typed events, in wire order`() {
        server.enqueue(
            sseResponse(
                event("""{"event":"message.delta","run_id":"$RUN","timestamp":1,"delta":"Warp "}"""),
                event("""{"event":"message.delta","run_id":"$RUN","timestamp":2,"delta":"core "}"""),
                event(
                    """
                    {"event":"tool.started","run_id":"$RUN","timestamp":3,"tool":"render_artifact",
                    "preview":"render_artifact(kind=image)",
                    "args":{"kind":"image","url":"http://x/y.png"}}
                    """,
                ),
                event(
                    """
                    {"event":"tool.completed","run_id":"$RUN","timestamp":4,"tool":"render_artifact",
                    "duration":1.25,"error":false,"result":"ok"}
                    """,
                ),
                event("""{"event":"reasoning.available","run_id":"$RUN","timestamp":5,"text":"checking the conduit"}"""),
                event(
                    """
                    {"event":"run.completed","run_id":"$RUN","timestamp":6,"output":"Warp core nominal.",
                    "usage":{"input_tokens":11,"output_tokens":7,"total_tokens":18}}
                    """,
                ),
            ),
        )

        val events = events()

        assertEquals(6, events.size)
        assertEquals(HermesEvent.MessageDelta(RUN, "Warp "), events[0])
        assertEquals(HermesEvent.MessageDelta(RUN, "core "), events[1])

        val started = events[2] as HermesEvent.ToolStarted
        assertEquals("render_artifact", started.tool)
        assertEquals("render_artifact(kind=image)", started.preview)
        // The artifact contract: args survive as parsable JSON, not as a preview string (REQ-37).
        assertEquals("image", started.args["kind"]?.jsonPrimitive?.content)
        assertEquals("http://x/y.png", started.args["url"]?.jsonPrimitive?.content)

        val completed = events[3] as HermesEvent.ToolCompleted
        assertEquals("render_artifact", completed.tool)
        assertEquals(1.25, completed.durationSeconds)
        assertFalse(completed.isError)
        assertEquals("ok", completed.result?.jsonPrimitive?.content)

        assertEquals(HermesEvent.ReasoningAvailable(RUN, "checking the conduit"), events[4])

        val done = events[5] as HermesEvent.RunCompleted
        assertEquals("Warp core nominal.", done.output)
        assertEquals(HermesUsage(inputTokens = 11, outputTokens = 7, totalTokens = 18), done.usage)
        assertTrue(done.isTerminal)
    }

    @Test
    fun `an approval request arrives with the command and the choices that resolve it`() {
        server.enqueue(
            sseResponse(
                event(
                    """
                    {"event":"approval.request","run_id":"$RUN","timestamp":1,"command":"rm -rf /tmp/x",
                    "description":"delete a directory","pattern_key":"rm","pattern_keys":["rm"],
                    "allow_permanent":true,"choices":["once","session","always","deny"]}
                    """,
                ),
            ),
        )

        val request = events().single() as HermesEvent.ApprovalRequest

        assertEquals("rm -rf /tmp/x", request.command)
        assertEquals("delete a directory", request.description)
        assertEquals(listOf("once", "session", "always", "deny"), request.choices)
        assertFalse(request.isTerminal)
    }

    @Test
    fun `a failed run carries its error, and a cancelled one just ends`() {
        server.enqueue(
            sseResponse(event("""{"event":"run.failed","run_id":"$RUN","timestamp":1,"error":"model unreachable"}""")),
        )
        val failed = events().single() as HermesEvent.RunFailed
        assertEquals("model unreachable", failed.error)
        assertTrue(failed.isTerminal)

        server.enqueue(
            sseResponse(event("""{"event":"run.cancelled","run_id":"$RUN","timestamp":2}""")),
        )
        val cancelled = events().single() as HermesEvent.RunCancelled
        assertEquals(RUN, cancelled.runId)
        assertTrue(cancelled.isTerminal)
    }

    @Test
    fun `the stream is bearer-authenticated on the run's own events path`() {
        server.enqueue(sseResponse(event("""{"event":"run.cancelled","run_id":"$RUN","timestamp":1}""")))

        events()

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/runs/$RUN/events", request.path)
        assertEquals("Bearer $TEST_KEY", request.getHeader("Authorization"))
        assertEquals("text/event-stream", request.getHeader("Accept"))
    }

    @Test
    fun `a rejected key on the stream is terminal, not a stream that quietly ends`() {
        server.enqueue(MockResponse().setResponseCode(403))

        val failure = assertThrows(HermesException.Auth::class.java) { events() }
        assertEquals(403, failure.code)
    }

    private companion object {
        const val RUN = "run_abc123"
    }
}
