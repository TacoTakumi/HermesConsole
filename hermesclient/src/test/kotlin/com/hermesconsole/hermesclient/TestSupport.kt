package com.hermesconsole.hermesclient

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

internal const val TEST_KEY = "test-key-not-a-secret"

/** Point a client at the fake gateway. Plain HTTP on a loopback port: no TLS, no Android. */
internal fun MockWebServer.hermesConfig() = HermesConfig(
    host = hostName,
    port = port,
    apiKey = TEST_KEY,
)

/**
 * One Hermes SSE response, from lines already framed as the wire frames them. The gateway writes
 * each event as a single `data:` line followed by a blank line, and punctuates the quiet with
 * `: keepalive` comments - so the fixtures do too.
 */
internal fun sseResponse(vararg lines: String) = MockResponse()
    .setHeader("Content-Type", "text/event-stream")
    .setBody(lines.joinToString(separator = "") { "$it\n" })

/**
 * An event as the gateway frames it: one data line, then the blank line that ends the event. The
 * fixture's JSON may be written across lines for reading; it goes out on one, as the wire has it.
 */
internal fun event(json: String) =
    "data: ${json.trimIndent().lineSequence().joinToString("") { it.trim() }}\n"
