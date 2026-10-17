package com.hermesconsole.voiceclient

import okhttp3.HttpUrl

/**
 * Where VoiceManager lives and how we talk to it. Runtime config, never compiled in (REQ-28): the
 * console supplies host, port and key from settings, and nothing here is ever logged.
 *
 * The audio format is not negotiated - VoiceManager's /ws/stream takes raw mono 16-bit
 * little-endian PCM at [sampleRate], and [chunkSamples] is how much of it we put in one frame.
 */
data class VoiceManagerConfig(
    val host: String,
    val port: Int,
    val apiKey: String,
    /** 16 kHz is what the wire takes; the capture path resamples to it, not us. */
    val sampleRate: Int = 16_000,
    /** Samples per outbound frame. 320 = 20 ms at 16 kHz. */
    val chunkSamples: Int = 320,
    val tls: Boolean = false,
    /** First reconnect delay after the link drops; doubles up to [maxReconnectDelayMs] (REQ-10). */
    val reconnectDelayMs: Long = 500,
    val maxReconnectDelayMs: Long = 10_000,
) {
    init {
        require(chunkSamples > 0) { "chunkSamples must be positive" }
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(reconnectDelayMs > 0) { "reconnectDelayMs must be positive" }
        require(maxReconnectDelayMs >= reconnectDelayMs) { "maxReconnectDelayMs must not be below reconnectDelayMs" }
    }

    /** Bytes on the wire per frame: 16-bit mono. */
    val chunkBytes: Int get() = chunkSamples * 2

    /**
     * Built through [HttpUrl] so a key containing '&' or '#' is escaped rather than truncating the
     * query. OkHttp dials ws:// over the http scheme, so http/https is the right scheme here.
     */
    internal fun streamUrl(): HttpUrl = HttpUrl.Builder()
        .scheme(if (tls) "https" else "http")
        .host(host)
        .port(port)
        .addPathSegments("ws/stream")
        .addQueryParameter("api_key", apiKey)
        .build()
}
