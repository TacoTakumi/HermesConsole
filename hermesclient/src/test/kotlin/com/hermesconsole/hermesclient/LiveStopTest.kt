package com.hermesconsole.hermesclient

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * T-12 / REQ-11, against the real thing: a live run, stopped mid-stream, stops.
 *
 * The fake-server tests prove the request the client issues; only Hermes can prove that the request
 * lands - that `POST /stop` reaches the agent thread, that the token stream halts, and that the run
 * ends as `run.cancelled` rather than running to completion with nobody listening. That is the
 * difference between barge-in (REQ-13) and merely looking away.
 *
 * Skipped unless the gateway is named in the environment, so the module's tests still run on a plain
 * JVM with no Hermes anywhere (REQ-29). To run it (REQ-28: the key is env, never compiled in):
 *
 *     HERMES_HOST=127.0.0.1 HERMES_PORT=8642 HERMES_KEY=... ./gradlew :hermesclient:test
 */
@EnabledIfEnvironmentVariable(named = "HERMES_KEY", matches = ".+")
class LiveStopTest {

    @Test
    fun `a live run stopped mid-stream halts its token stream and ends as cancelled`() = runBlocking {
        val client = HermesClient(
            HermesConfig(
                host = System.getenv("HERMES_HOST") ?: "127.0.0.1",
                port = System.getenv("HERMES_PORT")?.toIntOrNull() ?: 8642,
                apiKey = System.getenv("HERMES_KEY"),
            ),
        )

        // Long enough to still be talking when the stop lands - the console barges in on an answer
        // in progress, not on one that already finished.
        val runId = client.dispatch("Count slowly from 1 to 100, one number per line, with a short remark about each.")

        val seen = mutableListOf<HermesEvent>()
        val stream = client.events(runId).onEach { synchronized(seen) { seen += it } }.launchIn(this)

        // Let it get properly underway, then talk over it.
        withTimeout(30_000) {
            while (synchronized(seen) { seen.count { it is HermesEvent.MessageDelta } } < 5) delay(50)
        }
        client.stop(runId)

        withTimeout(30_000) { stream.join() }

        val events = synchronized(seen) { seen.toList() }
        val terminal = events.last()
        assertTrue(terminal is HermesEvent.RunCancelled, "run ended as ${terminal::class.simpleName}, not cancelled")
        // The stream ended at the cancel: nothing was delivered behind it, and the count never got
        // anywhere near 100.
        assertEquals(1, events.count { it.isTerminal })
        assertTrue(
            events.count { it is HermesEvent.MessageDelta } < 400,
            "the run kept generating after the stop: ${events.size} events",
        )
    }
}
