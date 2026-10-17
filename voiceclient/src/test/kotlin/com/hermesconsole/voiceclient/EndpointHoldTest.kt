package com.hermesconsole.voiceclient

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * T-07 / REQ-07: VoiceManager's speculative endpoint-hold.
 *
 * VoiceManager may call the end of a turn early - a provisional endpoint (endpoint=true,
 * is_final=false) - so the console can get a head start on the run. Sometimes the speaker was only
 * pausing, and the turn reopens under the same segment_index with revised text.
 *
 * What a passing run proves: the provisional text is exposed immediately (we act on it), a revision
 * under the same segment_index rewrites that segment rather than appending a second one, and the
 * settled transcript is exactly the final text.
 */
class EndpointHoldTest {

    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient

    /** The server side of the socket, once the client has connected. */
    private val serverSocket = Channel<WebSocket>(Channel.CONFLATED)

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        http = OkHttpClient()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                serverSocket.trySend(webSocket)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
        }))
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        http.dispatcher.executorService.shutdown()
    }

    private fun transcriptFrame(segment: Int, text: String, isFinal: Boolean, endpoint: Boolean) =
        """{"type":"transcription","segment_index":$segment,"text":"$text","is_final":$isFinal,"endpoint":$endpoint}"""

    @Test
    fun `a reopened turn revises its segment in place instead of appending a duplicate`() = runBlocking {
        val client = VoiceManagerClient(
            VoiceManagerConfig(host = server.hostName, port = server.port, apiKey = "s3cret"),
            http,
        )
        client.connect()
        client.awaitState<VoiceLinkState.Connected>()
        val socket = withTimeout(5_000) { serverSocket.receive() }

        // VoiceManager calls the endpoint early: the speaker seems done.
        socket.send(transcriptFrame(0, "what is the weather", isFinal = false, endpoint = true))

        // The console acts on the provisional text at once - that is the whole point of the hold.
        withTimeout(5_000) {
            client.transcript.first { it.text == "what is the weather" }
        }
        assertEquals(true, client.transcript.value.endpointed)
        assertEquals(false, client.transcript.value.isFinal)

        // The speaker was only drawing breath. The turn reopens under the SAME segment_index.
        socket.send(transcriptFrame(0, "what is the weather in paris", isFinal = false, endpoint = false))
        socket.send(transcriptFrame(0, "what is the weather in paris tomorrow", isFinal = true, endpoint = true))

        val settled = withTimeout(5_000) { client.transcript.first { it.isFinal } }

        // Exactly the final text - no duplicated prefix from the provisional pass, no orphan.
        assertEquals("what is the weather in paris tomorrow", settled.text)
        // Exactly one segment rendered for one segment_index.
        assertEquals(1, settled.segments.size)

        client.close()
    }

    @Test
    fun `distinct segment indices render as distinct segments, in index order`() = runBlocking {
        val client = VoiceManagerClient(
            VoiceManagerConfig(host = server.hostName, port = server.port, apiKey = "s3cret"),
            http,
        )
        client.connect()
        client.awaitState<VoiceLinkState.Connected>()
        val socket = withTimeout(5_000) { serverSocket.receive() }

        socket.send(transcriptFrame(0, "computer", isFinal = true, endpoint = true))
        socket.send(transcriptFrame(1, "set a timer", isFinal = true, endpoint = true))

        val settled = withTimeout(5_000) { client.transcript.first { it.segments.size == 2 } }
        assertEquals("computer set a timer", settled.text)

        client.close()
    }
}
