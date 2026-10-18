package com.hermesconsole.voiceclient

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * T-09 / REQ-10: the link survives socket loss and the server's 300 s inactivity close.
 *
 * Two ways the socket goes away: it dies mid-turn, or VoiceManager closes an idle session with
 * 1001. Either way the console reconnects itself and comes back READY - a tablet on a wall does not
 * get restarted.
 *
 * The subtle half is the generation guard. OkHttp delivers a dead socket's callbacks on its own
 * threads, so a straggler from the socket we just replaced can land *after* the new one is up. Left
 * unguarded it knocks the fresh link straight back down, or splices last turn's speech into this
 * one. So every listener carries the generation it was dialled under, and a callback from any
 * generation but the current one is dropped on the floor.
 */
class ReconnectTest {

    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient

    private val serverSockets = Channel<WebSocket>(Channel.UNLIMITED)

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        http = OkHttpClient()
        // Enough upgrades for a drop and a reconnect, several times over.
        repeat(4) {
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverSockets.trySend(webSocket)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }
            }))
        }
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        http.dispatcher.executorService.shutdown()
    }

    /** Reconnect fast, so the tests do not spend their lives in backoff. */
    private fun client() = VoiceManagerClient(
        VoiceManagerConfig(
            host = server.hostName,
            port = server.port,
            apiKey = "s3cret",
            reconnectDelayMs = 20,
            maxReconnectDelayMs = 100,
        ),
        http,
    )

    private fun transcriptFrame(segment: Int, text: String, isFinal: Boolean) =
        """{"type":"transcription","segment_index":$segment,"text":"$text","is_final":$isFinal,"endpoint":$isFinal}"""

    @Test
    fun `a socket killed mid-turn reconnects, drains the half-turn, and comes back READY`() = runBlocking {
        val client = client()
        client.connect()
        client.awaitState<VoiceLinkState.Connected>()
        val first = withTimeout(5_000) { serverSockets.receive() }

        // Mid-turn: the speaker is halfway through a sentence.
        first.send(transcriptFrame(0, "what is the weather in", isFinal = false))
        withTimeout(5_000) { client.transcript.first { it.text == "what is the weather in" } }

        // The socket dies the way the network kills one: the call is aborted underneath the client.
        // No close handshake, no 1008, no warning - the read just fails with an IOException. (This
        // is done through OkHttp, outside the client under test; a graceful server close would
        // exercise the onClosing path instead, which the 1001 test already covers.)
        http.dispatcher.cancelAll()

        // The console puts itself back together, with nobody touching the tablet.
        withTimeout(5_000) { client.state.first { it is VoiceLinkState.Dropped } }
        client.awaitState<VoiceLinkState.Connected>()
        val second = withTimeout(5_000) { serverSockets.receive() }
        assertEquals(2, server.requestCount)

        // Stale state is drained: half a sentence from a dead socket is not the start of this turn.
        assertEquals("", client.transcript.value.text)
        assertTrue(client.transcript.value.segments.isEmpty())

        // And the new link works, with no restart.
        second.send(transcriptFrame(0, "hello again", isFinal = true))
        val settled = withTimeout(5_000) { client.transcript.first { it.isFinal } }
        assertEquals("hello again", settled.text)

        client.close()
    }

    @Test
    fun `the 300 s inactivity close (1001) reconnects without a restart`() = runBlocking {
        val client = client()
        client.connect()
        client.awaitState<VoiceLinkState.Connected>()
        val first = withTimeout(5_000) { serverSockets.receive() }

        // What VoiceManager does to a session that has said nothing for 300 s.
        first.close(1001, "inactivity")

        withTimeout(5_000) { client.state.first { it is VoiceLinkState.Dropped } }
        client.awaitState<VoiceLinkState.Connected>()
        withTimeout(5_000) { serverSockets.receive() }
        assertEquals(2, server.requestCount)

        client.close()
    }

    @Test
    fun `callbacks from a pre-reconnect generation are delivered to nobody`() = runBlocking {
        val client = client()
        client.connect()
        client.awaitState<VoiceLinkState.Connected>()
        val first = withTimeout(5_000) { serverSockets.receive() }

        // Hold the listener belonging to the generation that is about to die.
        val staleGeneration = client.generation
        val staleListener = client.listenerFor(staleGeneration)

        first.close(1001, "inactivity")
        client.awaitState<VoiceLinkState.Connected>()
        withTimeout(5_000) { serverSockets.receive() }
        assertEquals(staleGeneration + 1, client.generation)
        assertEquals(2, server.requestCount)

        // Now the dead socket's stragglers arrive, late, exactly as OkHttp would deliver them.
        val ghost = DeadWebSocket()
        staleListener.onMessage(ghost, transcriptFrame(0, "ghost of the last turn", isFinal = true))
        staleListener.onMessage(ghost, ByteString.of(1, 2, 3, 4))
        staleListener.onFailure(ghost, IOException("stale socket died"), null)
        staleListener.onClosing(ghost, 1001, "stale inactivity")

        delay(300) // give any wrongly-scheduled reconnect time to fire

        // None of it lands: no ghost transcript...
        assertEquals("", client.transcript.value.text)
        // ...the fresh link is still up, not knocked down by the old socket's failure...
        assertEquals(VoiceLinkState.Connected, client.state.value)
        // ...and no third dial was scheduled off a stale callback.
        assertEquals(2, server.requestCount)

        client.close()
    }

    @Test
    fun `an auth failure is still terminal - it does not reconnect`() = runBlocking {
        val authServer = MockWebServer()
        authServer.start()
        repeat(2) {
            authServer.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.close(1008, "invalid token")
                }
            }))
        }
        val client = VoiceManagerClient(
            VoiceManagerConfig(
                host = authServer.hostName,
                port = authServer.port,
                apiKey = "wrong",
                reconnectDelayMs = 20,
                maxReconnectDelayMs = 100,
            ),
            http,
        )

        client.connect()
        client.awaitState<VoiceLinkState.AuthFailed>()

        delay(300) // a reconnect loop, if there were one, would have dialled by now

        assertEquals(1, authServer.requestCount)
        assertEquals(VoiceLinkState.AuthFailed, client.state.value)

        client.close()
        authServer.shutdown()
    }

    @Test
    fun `a key that cannot go in a header is an auth failure, not a crash`() = runBlocking {
        // OkHttp refuses a header value with a control or non-ASCII character before any request.
        for (key in listOf("vmp_made-up\nkey", "vmp_made-up-\u00e9")) {
            val client = VoiceManagerClient(
                VoiceManagerConfig(host = server.hostName, port = server.port, apiKey = key),
                http,
            )

            client.connect()

            assertEquals(VoiceLinkState.AuthFailed, client.state.value)
            client.close()
        }
        delay(300) // a stray dial, if there were one, would have reached the server by now
        assertEquals(0, server.requestCount)
    }

    /** A WebSocket that is already dead - stands in for the socket a stale callback arrives from. */
    private class DeadWebSocket : WebSocket {
        override fun cancel() = Unit
        override fun close(code: Int, reason: String?) = false
        override fun queueSize() = 0L
        override fun request(): Request = Request.Builder().url("http://dead/").build()
        override fun send(text: String) = false
        override fun send(bytes: ByteString) = false
    }
}
