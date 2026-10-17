package com.hermesconsole.hermesclient

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * T-11 / REQ-11: junk on the stream is skipped, never fatal.
 *
 * A console that dies on an event it does not recognise is a console that dies on a Hermes upgrade,
 * hung mid-turn on a wall where nobody can restart it. Hermes already emits event types this client
 * does not model (and will grow more), and its stream is peppered with SSE comments. So the reader
 * drops what it cannot read and keeps going: what a passing run proves is that a run whose stream is
 * half garbage still delivers every event that *was* good, in order.
 */
class MalformedEventTest {

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
    fun `garbage, keepalives and unknown events are skipped, and the good ones still arrive`() {
        server.enqueue(
            sseResponse(
                ": keepalive",                                              // the gateway's 30 s tick
                event("""{"event":"message.delta","run_id":"$RUN","timestamp":1,"delta":"one"}"""),
                "data: this is not json at all\n",                          // truncated or corrupt
                event("""{"event":"subagent.progress","run_id":"$RUN","step":3}"""), // a type we do not model
                event("""{"run_id":"$RUN","timestamp":2}"""),               // no event type at all
                event("""{"event":"message.delta","run_id":"$RUN"}"""),     // right type, missing its delta
                "event: message.delta",                                     // SSE fields we do not use
                "id: 17",
                event("""{"event":"message.delta","run_id":"$RUN","timestamp":3,"delta":"two"}"""),
                ": stream closed",
            ),
        )

        val events = runBlocking { withTimeout(5_000) { client.events(RUN).toList() } }

        assertEquals(
            listOf(HermesEvent.MessageDelta(RUN, "one"), HermesEvent.MessageDelta(RUN, "two")),
            events,
        )
    }

    @Test
    fun `an event split across data lines is reassembled, and a colon needs no space after it`() {
        server.enqueue(
            sseResponse(
                // SSE lets one event's data span several lines; the gateway sends one, but a proxy
                // in the path may not, and the reader owes the spec more than it owes the gateway.
                """data: {"event":"message.delta","run_id":"$RUN",""",
                """data: "timestamp":1,"delta":"split"}""",
                "",
                """data:{"event":"run.completed","run_id":"$RUN","timestamp":2,"output":"done"}""",
                "",
            ),
        )

        val events = runBlocking { withTimeout(5_000) { client.events(RUN).toList() } }

        assertEquals(HermesEvent.MessageDelta(RUN, "split"), events[0])
        assertEquals("done", (events[1] as HermesEvent.RunCompleted).output)
        // The gateway omits usage on some paths; its absence is not a parse failure.
        assertEquals(null, (events[1] as HermesEvent.RunCompleted).usage)
    }

    @Test
    fun `an explicit null where the model has a default is coerced, not fatal - the live tool-started shape`() {
        // Captured verbatim from the deployed gateway (T-40): a tool with no preview formatter
        // ships "preview": null, and an explicit null is not an absent key - the default does not
        // apply, the decode throws, and without coercion the artifact event this whole contract
        // rides on is dropped on the floor while the run completes normally around it.
        server.enqueue(
            sseResponse(
                event(
                    """{"event":"tool.started","run_id":"$RUN","timestamp":1784331974.242988,""" +
                        """"tool":"render_artifact","preview":null,""" +
                        """"args":{"kind":"markdown","markdown":"# Hi"}}""",
                ),
            ),
        )

        val events = runBlocking { withTimeout(5_000) { client.events(RUN).toList() } }

        val started = events.single() as HermesEvent.ToolStarted
        assertEquals("render_artifact", started.tool)
        assertEquals("", started.preview)
        assertEquals("markdown", (started.args["kind"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `a stream that ends without a terminal event just ends`() {
        server.enqueue(sseResponse(event("""{"event":"message.delta","run_id":"$RUN","timestamp":1,"delta":"cut"}""")))

        val events = runBlocking { withTimeout(5_000) { client.events(RUN).toList() } }

        assertEquals(listOf(HermesEvent.MessageDelta(RUN, "cut")), events)
    }

    private companion object {
        const val RUN = "run_abc123"
    }
}
