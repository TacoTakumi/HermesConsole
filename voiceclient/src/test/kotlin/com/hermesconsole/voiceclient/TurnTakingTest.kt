package com.hermesconsole.voiceclient

import kotlinx.coroutines.async
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * T-37 / REQ-07, REQ-08: the turn-taking protocol surface (D-18).
 *
 * VoiceManager scopes conversation turns by `turn_id`, and the id is the server's to hand out: the
 * client opens a turn with the bare `{"type":"turn_start"}` control frame and learns the new id
 * from the `turn_started` ack - never by counting locally, and never by inferring it from the next
 * transcription frame. Every `transcription` frame carries the `turn_id` its *audio* belonged to
 * (default 0 on a connection that never opened a turn), which is what lets a consumer fence out a
 * stale prior-turn utterance without ever discarding an in-place revision of the current turn - the
 * exact failure mode of the segment_index watermark this replaces (D-18).
 *
 * The frame names and shapes here are taken from VoiceManager/docs/client-integration.md, not from
 * this client's own expectations - see the scar comment on [TranscriptFrame] for why that matters.
 */
class TurnTakingTest {

    private lateinit var server: MockWebServer
    private lateinit var client: VoiceManagerClient
    private val http = OkHttpClient()

    /** The server side of the socket, once the client has connected. */
    private val serverSocket = Channel<WebSocket>(Channel.CONFLATED)

    /** Every text frame the server received, in wire order. */
    private val received = Channel<String>(Channel.UNLIMITED)

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverSocket.trySend(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    received.trySend(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }
            }),
        )
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

    private suspend fun connectedSocket(): WebSocket {
        client.connect()
        client.awaitState<VoiceLinkState.Connected>()
        return withTimeout(5_000) { serverSocket.receive() }
    }

    private fun transcription(segment: Int, text: String, turnId: Int? = null) = buildString {
        append("""{"type":"transcription","segment_index":$segment,"text":"$text","is_final":true""")
        if (turnId != null) append(""","turn_id":$turnId""")
        append("}")
    }

    @Test
    fun `turn_start goes out bare and turnStart returns the server-assigned id`() = runBlocking {
        val socket = connectedSocket()

        val ack = async { client.turnStart() }

        val sent = withTimeout(5_000) { Json.parseToJsonElement(received.receive()).jsonObject }
        assertEquals("turn_start", sent["type"]?.jsonPrimitive?.content)
        // Bare means bare: the doc says "no payload", so any extra field is a protocol drift.
        assertEquals(setOf("type"), sent.keys)

        // The server owns the turn counter - 5, not the 1 a client counting locally would guess.
        socket.send("""{"type":"turn_started","turn_id":5}""")
        assertEquals(5, withTimeout(5_000) { ack.await() })
    }

    @Test
    fun `transcription frames carry their turn_id onto the transcript`() = runBlocking {
        val socket = connectedSocket()

        socket.send(transcription(0, "engage", turnId = 4))

        val settled = withTimeout(5_000) { client.transcript.first { it.segments.isNotEmpty() } }
        assertEquals(4, settled.segments.single().turnId)
    }

    @Test
    fun `an omitted turn_id defaults to zero`() = runBlocking {
        val socket = connectedSocket()

        // A pre-turn-taking server sends no turn_id at all; the consumer must still be able to
        // read the field unconditionally.
        socket.send(transcription(0, "status report"))

        val settled = withTimeout(5_000) { client.transcript.first { it.segments.isNotEmpty() } }
        assertEquals(0, settled.segments.single().turnId)
    }

    @Test
    fun `unsolicited turn-taking frames are ignored without harm`() = runBlocking {
        val socket = connectedSocket()

        // An ack nobody asked for, and an audio_end carrying the new reason field: both are part
        // of the additive protocol surface a non-opted-in consumer must survive (D-18).
        socket.send("""{"type":"turn_started","turn_id":9}""")
        socket.send("""{"type":"audio_end","request_id":"r1","reason":"cancelled"}""")

        // The link is still alive and still transcribing.
        socket.send(transcription(0, "still here", turnId = 9))
        val settled = withTimeout(5_000) { client.transcript.first { it.segments.isNotEmpty() } }
        assertEquals("still here", settled.text)
        assertEquals(9, settled.segments.single().turnId)
    }
}
