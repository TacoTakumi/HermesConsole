package com.hermesconsole.hermesclient

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * T-12 / REQ-11: the two things a console does to a run it is already listening to - stop it, and
 * answer the approval it is stalled on.
 *
 * Stop is the server half of barge-in (REQ-13, T-14): the human talks over the assistant, and the
 * run has to die at the gateway, not just be ignored here - an ignored run keeps burning tokens and
 * keeps streaming into a console that has moved on to the next turn. What the consumer must never
 * see is a delta that arrives *after* the cancel: Hermes' agent runs in an executor thread that
 * `interrupt()` cannot preempt, so a token generated before the stop lands can reach the stream
 * after `run.cancelled` does. The stream ends at the terminal event, so that token dies here.
 *
 * The fake gateway's stream is canned - posting an approval to it does not cause the events that
 * follow - so what these tests prove is the client's half: the request it issues, and the events it
 * hands the consumer. That Hermes really does stall on approval and really does halt on stop is what
 * the live run in the task's Verify is for.
 */
class StopAndApprovalTest {

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
    fun `stop posts to the run's own stop path, bearer-authenticated`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"run_id":"$RUN","status":"stopping"}"""),
        )

        client.stop(RUN)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/runs/$RUN/stop", request.path)
        assertEquals("Bearer $TEST_KEY", request.getHeader("Authorization"))
        // The gateway takes the run id from the path and reads no body. Sending one would only be a
        // body it ignores.
        assertEquals("", request.body.readUtf8())
    }

    @Test
    fun `stopping a run that is already over is not a failure`() = runBlocking {
        // The race barge-in loses sometimes: the run completed between the last delta and the stop.
        // There is nothing left to stop, which is the outcome stop wanted - not an error to throw at
        // a console that is already listening to the human again.
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"message":"Run not found"}}"""))

        client.stop(RUN)

        assertEquals("/v1/runs/$RUN/stop", server.takeRequest().path)
    }

    @Test
    fun `a rejected key on stop is terminal, and never echoes the key`() {
        server.enqueue(MockResponse().setResponseCode(401))

        val failure = assertThrows(HermesException.Auth::class.java) {
            runBlocking { client.stop(RUN) }
        }
        assertEquals(401, failure.code)
        assertEquals(false, failure.message.orEmpty().contains(TEST_KEY))
    }

    @Test
    fun `the stream ends at run cancelled, and a delta that lands after it never reaches the consumer`() {
        server.enqueue(
            sseResponse(
                event("""{"event":"message.delta","run_id":"$RUN","timestamp":1,"delta":"Warp core "}"""),
                event("""{"event":"run.cancelled","run_id":"$RUN","timestamp":2}"""),
                // Generated before the stop landed, delivered after it. The turn it belongs to is
                // over; speaking it would talk over the human who just barged in.
                event("""{"event":"message.delta","run_id":"$RUN","timestamp":3,"delta":"nominal."}"""),
            ),
        )

        val events = runBlocking { withTimeout(5_000) { client.events(RUN).toList() } }

        assertEquals(2, events.size)
        assertEquals(HermesEvent.MessageDelta(RUN, "Warp core "), events[0])
        assertEquals(HermesEvent.RunCancelled(RUN), events[1])
    }

    @Test
    fun `an approval is resolved by posting the choice, and the run streams on`() {
        server.enqueue(
            sseResponse(
                event(
                    """
                    {"event":"approval.request","run_id":"$RUN","timestamp":1,"command":"rm -rf /tmp/x",
                    "description":"delete a directory","choices":["once","session","always","deny"]}
                    """,
                ),
                event("""{"event":"approval.responded","run_id":"$RUN","timestamp":2,"choice":"once"}"""),
                event("""{"event":"message.delta","run_id":"$RUN","timestamp":3,"delta":"Deleted."}"""),
                event("""{"event":"run.completed","run_id":"$RUN","timestamp":4,"output":"Deleted."}"""),
            ),
        )
        server.enqueue(
            MockResponse().setBody("""{"object":"hermes.run.approval_response","run_id":"$RUN","choice":"once","resolved":1}"""),
        )

        // Drive it the way the console will (T-27): the pane answers from inside the stream it is
        // watching, without tearing the stream down to do it.
        val events = runBlocking {
            withTimeout(5_000) {
                client.events(RUN)
                    .onEach { if (it is HermesEvent.ApprovalRequest) client.resolveApproval(RUN, ApprovalChoice.ONCE) }
                    .toList()
            }
        }

        // The run kept going: the tool ran and the turn finished.
        assertEquals(HermesEvent.MessageDelta(RUN, "Deleted."), events[events.size - 2])
        assertEquals(true, events.last().isTerminal)

        server.takeRequest() // the GET of the event stream
        val approval = server.takeRequest()
        assertEquals("POST", approval.method)
        assertEquals("/v1/runs/$RUN/approval", approval.path)
        assertEquals("Bearer $TEST_KEY", approval.getHeader("Authorization"))
        assertEquals("application/json", approval.getHeader("Content-Type")?.substringBefore(';'))

        val body = Json.parseToJsonElement(approval.body.readUtf8()).jsonObject
        assertEquals("once", body["choice"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a denial posts deny, and the run carries on without the tool`() {
        // A deny is not a stop, and the fixture says so because Hermes does: resolving with "deny"
        // hands the model a "BLOCKED: User denied this potentially dangerous action" result and the
        // agent loop keeps running - it adapts and answers without the tool. A console that wants the
        // turn dead has to stop() it as well (REQ-14, T-27).
        server.enqueue(
            sseResponse(
                event(
                    """
                    {"event":"approval.request","run_id":"$RUN","timestamp":1,"command":"rm -rf /",
                    "description":"delete everything","choices":["once","session","always","deny"]}
                    """,
                ),
                event("""{"event":"approval.responded","run_id":"$RUN","timestamp":2,"choice":"deny"}"""),
                event("""{"event":"message.delta","run_id":"$RUN","timestamp":3,"delta":"I won't do that."}"""),
                event("""{"event":"run.completed","run_id":"$RUN","timestamp":4,"output":"I won't do that."}"""),
            ),
        )
        server.enqueue(
            MockResponse().setBody("""{"object":"hermes.run.approval_response","run_id":"$RUN","choice":"deny","resolved":1}"""),
        )

        val events = runBlocking {
            withTimeout(5_000) {
                client.events(RUN)
                    .onEach { if (it is HermesEvent.ApprovalRequest) client.resolveApproval(RUN, ApprovalChoice.DENY) }
                    .toList()
            }
        }

        assertEquals(HermesEvent.MessageDelta(RUN, "I won't do that."), events[events.size - 2])
        assertEquals(true, events.last() is HermesEvent.RunCompleted)

        server.takeRequest()
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("deny", body["choice"]?.jsonPrimitive?.content)
        // The gateway's own vocabulary, verbatim: once / session / always / deny. It rejects
        // anything else with a 400, so the choice is an enum here and never a free string.
        assertNull(body["decision"])
    }

    @Test
    fun `an approval the gateway is no longer waiting on surfaces its refusal`() {
        // The auto-deny (T-27) and the human can both answer, and Hermes only accepts the first:
        // the loser gets a 409, which the console must see rather than swallow.
        server.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setBody("""{"error":{"message":"Run has no pending approval","code":"approval_not_pending"}}"""),
        )

        val failure = assertThrows(HermesException.Http::class.java) {
            runBlocking { client.resolveApproval(RUN, ApprovalChoice.ONCE) }
        }
        assertEquals(409, failure.code)
    }

    private companion object {
        const val RUN = "run_abc123"
    }
}
