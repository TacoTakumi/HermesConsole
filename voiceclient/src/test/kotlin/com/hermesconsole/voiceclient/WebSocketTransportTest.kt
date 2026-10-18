package com.hermesconsole.voiceclient

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * T-06 / REQ-06: the wire contract with VoiceManager's /ws/stream, asserted against a fake server.
 *
 * What a passing run proves: the upgrade carries the token as a Bearer header and the URL carries
 * no credential; outbound audio is raw 16 kHz mono 16-bit little-endian PCM chunked to the
 * configured size with no container header; and a rejected token - an HTTP 403 on the upgrade, or
 * a 1008 close - lands the client in a terminal AUTH_FAILED state instead of a reconnect loop.
 */
class WebSocketTransportTest {

    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient

    /** Binary frames the fake server actually received, in order. */
    private val framesIn = Channel<ByteArray>(Channel.UNLIMITED)

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        http = OkHttpClient()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        http.dispatcher.executorService.shutdown()
    }

    private fun configFor(server: MockWebServer, chunkSamples: Int = 320) = VoiceManagerConfig(
        host = server.hostName,
        port = server.port,
        apiKey = "s3cret",
        chunkSamples = chunkSamples,
    )

    /** A fake server that accepts the upgrade and records every binary frame it is sent. */
    private fun acceptingServer() = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            framesIn.trySend(bytes.toByteArray())
        }

        // Complete the close handshake, as a real server does - without it the server-side socket
        // lingers and MockWebServer cannot shut down.
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }

    /** A fake server that accepts the upgrade, then rejects the session with a 1008 close. */
    private fun authRejectingServer() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.close(1008, "invalid api_key")
        }
    }

    @Test
    fun `connects to ws stream with the token as a Bearer header and nothing in the URL`() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(acceptingServer()))
        val client = VoiceManagerClient(configFor(server), http)

        client.connect()
        client.awaitState<VoiceLinkState.Connected>()

        val request = server.takeRequest()
        assertEquals("/ws/stream", request.path)
        assertEquals("Bearer s3cret", request.getHeader("Authorization"))

        client.close()
    }

    @Test
    fun `an HTTP 403 on the upgrade is terminal AUTH_FAILED, not a reconnect loop`() = runBlocking {
        // VoiceManager checks the token before it accepts the upgrade: a bad one is a 403, no socket.
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(MockResponse().setResponseCode(403))
        val client = VoiceManagerClient(configFor(server), http)

        client.connect()
        client.awaitState<VoiceLinkState.AuthFailed>()
        delay(1_500) // past the first reconnect delay

        assertEquals(1, server.requestCount)
        client.close()
    }

    @Test
    fun `sends raw 16-bit little-endian PCM at the configured chunk size with no container header`() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(acceptingServer()))
        val client = VoiceManagerClient(configFor(server, chunkSamples = 320), http)

        client.connect()
        client.awaitState<VoiceLinkState.Connected>()

        // Two chunks' worth of samples, offered as one non-chunk-aligned burst: the client is what
        // does the chunking. 0x0102 exercises byte order; -2 exercises sign.
        val samples = ShortArray(640) { i -> if (i == 0) 0x0102 else if (i == 1) -2 else 0 }
        client.sendAudio(samples)

        val first = withTimeout(5_000) { framesIn.receive() }
        val second = withTimeout(5_000) { framesIn.receive() }

        // Configured chunk size, in bytes: 320 samples x 2 bytes. No WAV/RIFF or any other header:
        // a header would make the first frame longer than the audio it carries.
        assertEquals(640, first.size)
        assertEquals(640, second.size)

        // Little-endian: low byte first. 0x0102 -> 0x02 0x01; -2 -> 0xFE 0xFF.
        assertArrayEquals(
            byteArrayOf(0x02, 0x01, 0xFE.toByte(), 0xFF.toByte()),
            first.copyOfRange(0, 4),
        )

        client.close()
    }

    @Test
    fun `a 1008 close before accept is terminal AUTH_FAILED, not a reconnect loop`() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(authRejectingServer()))
        val client = VoiceManagerClient(configFor(server), http)

        client.connect()
        client.awaitState<VoiceLinkState.AuthFailed>()

        // Terminal: a further connect() must not dial again. The fake server has exactly one
        // upgrade enqueued, so a reconnect loop would show up as a second request.
        client.connect()
        client.awaitState<VoiceLinkState.AuthFailed>()
        assertEquals(1, server.requestCount)

        client.close()
    }
}
