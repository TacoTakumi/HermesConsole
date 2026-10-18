package com.hermesconsole.hermesclient

import okhttp3.HttpUrl

/**
 * Where the Hermes gateway lives and how we authenticate to it. Runtime config, never compiled in
 * (REQ-28): the console supplies host, port and key from settings, and nothing here is ever logged.
 *
 * One surface, deliberately (D-06, REQ-11): every conversational turn is a POST to /v1/runs and a
 * GET of that run's SSE events. No /v1/chat/completions, no /v1/responses, no agent framework.
 */
data class HermesConfig(
    val host: String,
    val port: Int,
    val apiKey: String,
    val tls: Boolean = false,
) {
    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(port in 1..65_535) { "port must be a valid TCP port" }
        // Blank would go out as a bare "Bearer " and come back 401. Say so here, not three hops later.
        require(apiKey.isNotBlank()) { "apiKey must not be blank" }
    }

    /** The bearer credential, formatted for the wire. Never logged, never in an exception message. */
    internal val authorization: String get() = "Bearer $apiKey"

    /** The generated toString would print the key into any log line or message that shows a config. */
    override fun toString(): String = "HermesConfig(host=$host, port=$port, apiKey=***, tls=$tls)"

    internal fun runsUrl(): HttpUrl = base().addPathSegments("v1/runs").build()

    /** The health probe's target (REQ-23): the cheapest authenticated GET the gateway serves. */
    internal fun modelsUrl(): HttpUrl = base().addPathSegments("v1/models").build()

    /** The model picker's catalog; its top level names the configured selection (2026-08-14). */
    internal fun modelOptionsUrl(): HttpUrl = base().addPathSegments("api/model/options").build()

    /** The session resource (T-22): metadata and history only - conversation stays on /v1/runs. */
    internal fun sessionsUrl(limit: Int? = null): HttpUrl = base()
        .addPathSegments("api/sessions")
        .apply { limit?.let { addQueryParameter("limit", it.toString()) } }
        .build()

    /** Built segment-wise so a session id is escaped rather than pasted into the path. */
    internal fun sessionMessagesUrl(sessionId: String): HttpUrl = base()
        .addPathSegments("api/sessions")
        .addPathSegment(sessionId)
        .addPathSegment("messages")
        .build()

    /** Built segment-wise so a run id is escaped rather than pasted into the path. */
    internal fun eventsUrl(runId: String): HttpUrl = runUrl(runId, "events")

    internal fun stopUrl(runId: String): HttpUrl = runUrl(runId, "stop")

    internal fun approvalUrl(runId: String): HttpUrl = runUrl(runId, "approval")

    /** The device tool socket: tool calls inside a /v1/runs turn, not a second conversation surface. */
    internal fun deviceSocketUrl(): HttpUrl = base().addPathSegments("v1/device/ws").build()

    private fun runUrl(runId: String, verb: String): HttpUrl = base()
        .addPathSegments("v1/runs")
        .addPathSegment(runId)
        .addPathSegment(verb)
        .build()

    private fun base(): HttpUrl.Builder = HttpUrl.Builder()
        .scheme(if (tls) "https" else "http")
        .host(host)
        .port(port)
}
