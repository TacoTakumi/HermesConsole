package com.hermesconsole.hermesclient

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * T-07 / REQ-08: the run's effective runtime, read off the completed event's usage.
 *
 * The gateway reports which provider and model actually served a run inside the usage object it
 * already emits (api_server.py: usage may carry `runtime` with provider/model plus fields this
 * client has no use for). The model readout wants exactly that - and a payload without it, or
 * with extras around it, must parse exactly as before: additive and safe is the whole point.
 */
class RuntimeMetadataTest {

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

    private fun completed(usageJson: String, extra: String = "") = runBlocking {
        withTimeout(5_000) {
            server.enqueue(
                sseResponse(
                    event("""{"event":"run.completed","run_id":"$RUN","timestamp":9,"output":"done","usage":$usageJson$extra}"""),
                ),
            )
            client.events(RUN).toList().filterIsInstance<HermesEvent.RunCompleted>().single()
        }
    }

    @Test
    fun `a completed run carrying runtime metadata yields the effective provider and model`() {
        val done = completed(
            """
            {"input_tokens":900,"output_tokens":120,"total_tokens":1020,
             "runtime":{"provider":"anthropic","model":"claude-sonnet-5","route_source":"global","model_lock":"confirmed"}}
            """.trimIndent().lineSequence().joinToString("") { it.trim() },
        )

        assertEquals("anthropic", done.usage?.runtime?.provider)
        assertEquals("claude-sonnet-5", done.usage?.runtime?.model)
        assertEquals(1020, done.usage?.totalTokens, "the accounting beside it is untouched")
    }

    @Test
    fun `upstream's top-level runtime parses beside usage`() {
        val done = completed(
            """{"input_tokens":10,"output_tokens":2,"total_tokens":12}""",
            extra = ""","runtime":{"provider":"anthropic","model":"claude-sonnet-5","route_source":"global",""" +
                """"requested":{"model":"claude-sonnet-5"},"model_lock":"confirmed"}""",
        )

        assertEquals("anthropic", done.runtime?.provider)
        assertEquals("claude-sonnet-5", done.runtime?.model)
        assertNull(done.usage?.runtime, "upstream does not nest it in usage")
    }

    @Test
    fun `a completed run without runtime metadata parses exactly as before`() {
        val done = completed("""{"input_tokens":10,"output_tokens":2,"total_tokens":12}""")

        assertNull(done.usage?.runtime, "no report is a null, never an invention")
        assertEquals(12, done.usage?.totalTokens)
    }

    private companion object {
        const val RUN = "run_abc123"
    }
}
