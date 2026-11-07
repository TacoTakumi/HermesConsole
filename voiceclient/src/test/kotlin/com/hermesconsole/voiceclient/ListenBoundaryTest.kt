package com.hermesconsole.voiceclient

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The listen boundary on the wire: the client sends a bare `listen_stop` and `listen_start`, and
 * the server answers a stop with `listen_stopped` carrying the mic samples it accepted so far.
 */
class ListenBoundaryTest {

    private lateinit var server: MockWebServer
    private lateinit var client: VoiceManagerClient
    private val http = OkHttpClient()

    private val serverSocket = Channel<WebSocket>(Channel.UNLIMITED)
    private val received = Channel<String>(Channel.UNLIMITED)

    private fun upgrade() = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            serverSocket.trySend(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            received.trySend(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    })

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        server.enqueue(upgrade())
        client = VoiceManagerClient(
            VoiceManagerConfig(host = server.hostName, port = server.port, apiKey = "k"),
            http,
        )
    }

    @AfterEach
    fun tearDown() {
        client.shutdown()
        server.shutdown()
        http.dispatcher.executorService.shutdown()
    }

    private suspend fun connect(): WebSocket {
        client.connect()
        val socket = withTimeout(5_000) { serverSocket.receive() }
        client.awaitState<VoiceLinkState.Connected>()
        return socket
    }

    private suspend fun nextFrame(): String = withTimeout(5_000) { received.receive() }

    @Test
    fun `listen_stop and listen_start go out as bare text frames`() = runBlocking {
        connect()
        client.sendListenStop()
        client.sendListenStart()

        val stop = Json.parseToJsonElement(nextFrame()).jsonObject
        assertEquals(mapOf("type" to "listen_stop"), stop.mapValues { it.value.jsonPrimitive.content })
        val start = Json.parseToJsonElement(nextFrame()).jsonObject
        assertEquals(mapOf("type" to "listen_start"), start.mapValues { it.value.jsonPrimitive.content })
    }

    @Test
    fun `listen_stopped reaches its flow as the mic sample`() = runBlocking {
        val socket = connect()
        socket.send("""{"type":"listen_stopped","mic_sample":5000000000}""")
        assertEquals(5_000_000_000L, withTimeout(5_000) { client.listenStopped.first() })
    }

    @Test
    fun `unknown frames around listen_stopped are still ignored`() = runBlocking {
        val socket = connect()
        socket.send("""{"type":"listen_paused","mic_sample":1}""")
        socket.send("""{"type":"listen_stopped","mic_sample":48000,"extra":"ignored"}""")
        assertEquals(48_000L, withTimeout(5_000) { client.listenStopped.first() })
        assertEquals(VoiceLinkState.Connected, client.state.value)
    }

    @Test
    fun `the parser reads listen_stopped and drops a malformed one`() {
        assertEquals(
            InboundFrame.ListenStopped(ListenStoppedFrame(micSample = 320)),
            parseInboundFrame("""{"type":"listen_stopped","mic_sample":320}"""),
        )
        assertNull(parseInboundFrame("""{"type":"listen_stopped"}"""))
        assertNull(parseInboundFrame("""{"type":"listen_started"}"""))
    }
}
