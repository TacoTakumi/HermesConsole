package com.hermesconsole.voiceclient

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The turn engine on the wire, as VoiceManager's client-integration guide states it: an opted-in
 * connection sends `turn_engine` before any audio, learns the answer from `turn_engine_ready` or an
 * `error` frame, then receives `turn_end` and `barge` and reports playback edges with `playback`.
 * The frame shapes here come from the server's protocol, not from this client.
 */
class TurnEngineWireTest {

    private lateinit var server: MockWebServer
    private lateinit var client: VoiceManagerClient
    private val http = OkHttpClient()

    private val serverSocket = Channel<WebSocket>(Channel.UNLIMITED)

    /** Every frame the server received, in wire order: text as is, binary as "<binary N>". */
    private val received = Channel<String>(Channel.UNLIMITED)

    private fun upgrade() = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            serverSocket.trySend(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            received.trySend(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            received.trySend("<binary ${bytes.size}>")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    })

    private fun clientWith(turnEngine: Boolean) = VoiceManagerClient(
        VoiceManagerConfig(
            host = server.hostName,
            port = server.port,
            apiKey = "k",
            turnEngine = turnEngine,
            reconnectDelayMs = 50,
            maxReconnectDelayMs = 50,
        ),
        http,
    )

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        server.enqueue(upgrade())
        server.enqueue(upgrade())
    }

    @AfterEach
    fun tearDown() {
        client.shutdown()
        server.shutdown()
        http.dispatcher.executorService.shutdown()
    }

    private suspend fun nextFrame(): String = withTimeout(5_000) { received.receive() }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    private suspend fun connect(): WebSocket {
        client.connect()
        val socket = withTimeout(5_000) { serverSocket.receive() }
        client.awaitState<VoiceLinkState.Connected>()
        return socket
    }

    private val tenMs = ShortArray(160)
    private val twentyMs = ShortArray(320)

    @Test
    fun `an opted-in connection sends the opt-in first and no audio before the answer`() = runBlocking {
        client = clientWith(turnEngine = true)
        val socket = connect()

        val first = json(nextFrame())
        assertEquals("turn_engine", first.str("type"))
        assertEquals("tyger", first.str("engine"))
        assertEquals(TurnEngineStatus.Pending, client.turnEngineStatus.value)

        // Audio offered before the answer does not go out and is not counted.
        client.sendAudio(twentyMs)
        assertNull(withTimeoutOrNull(300) { received.receive() })
        assertEquals(0L, client.micSamplesSent)

        socket.send("""{"type":"turn_engine_ready","engine":"tyger"}""")
        withTimeout(5_000) { client.turnEngineStatus.first { it == TurnEngineStatus.Ready } }
        client.sendAudio(twentyMs)
        assertEquals("<binary 640>", nextFrame())
        assertEquals(320L, client.micSamplesSent)
    }

    @Test
    fun `a reconnect opts in again before its first audio and counts from zero`() = runBlocking<Unit> {
        client = clientWith(turnEngine = true)
        val socket = connect()
        assertEquals("turn_engine", json(nextFrame()).str("type"))
        socket.send("""{"type":"turn_engine_ready","engine":"tyger"}""")
        withTimeout(5_000) { client.turnEngineStatus.first { it == TurnEngineStatus.Ready } }
        client.sendAudio(twentyMs)
        assertEquals("<binary 640>", nextFrame())

        socket.close(1001, "going away")
        val second = withTimeout(5_000) { serverSocket.receive() }
        assertEquals("turn_engine", json(nextFrame()).str("type"))
        assertEquals(0L, client.micSamplesSent)
        second.send("""{"type":"turn_engine_ready","engine":"tyger"}""")
        withTimeout(5_000) { client.turnEngineStatus.first { it == TurnEngineStatus.Ready } }
    }

    @Test
    fun `a refused opt-in leaves a plain connection that sends audio`() = runBlocking {
        client = clientWith(turnEngine = true)
        val socket = connect()
        nextFrame()
        socket.send("""{"type":"error","error_type":"turn_engine_unsupported","message":"needs audiocpp"}""")
        val status = withTimeout(5_000) { client.turnEngineStatus.first { it is TurnEngineStatus.Refused } }
        assertEquals(TurnEngineStatus.Refused("turn_engine_unsupported"), status)

        client.sendAudio(twentyMs)
        assertEquals("<binary 640>", nextFrame())
    }

    @Test
    fun `turn_engine_lost marks the connection lost`() = runBlocking<Unit> {
        client = clientWith(turnEngine = true)
        val socket = connect()
        nextFrame()
        socket.send("""{"type":"turn_engine_ready","engine":"tyger"}""")
        withTimeout(5_000) { client.turnEngineStatus.first { it == TurnEngineStatus.Ready } }
        socket.send("""{"type":"error","error_type":"turn_engine_lost","message":"the sidecar stopped"}""")
        withTimeout(5_000) { client.turnEngineStatus.first { it == TurnEngineStatus.Lost } }
    }

    @Test
    fun `an invalid_request after the opt-in was answered does not undo it`() = runBlocking {
        client = clientWith(turnEngine = true)
        val socket = connect()
        nextFrame()
        socket.send("""{"type":"turn_engine_ready","engine":"tyger"}""")
        withTimeout(5_000) { client.turnEngineStatus.first { it == TurnEngineStatus.Ready } }
        // A refused playback frame answers with invalid_request; the connection stays opted in.
        socket.send("""{"type":"error","error_type":"invalid_request","message":"unknown request_id"}""")
        socket.send("""{"type":"turn_end","turn_id":1,"text":"x","mic_sample":1,"by":"model"}""")
        withTimeout(5_000) { client.turnEnds.first() }
        assertEquals(TurnEngineStatus.Ready, client.turnEngineStatus.value)
    }

    @Test
    fun `turn_end, barge and the audio_end reason reach their flows`() = runBlocking {
        client = clientWith(turnEngine = true)
        val socket = connect()
        nextFrame()
        socket.send("""{"type":"turn_engine_ready","engine":"tyger"}""")
        socket.send("""{"type":"turn_end","turn_id":4,"text":"book me a flight","mic_sample":163840,"by":"model"}""")
        socket.send("""{"type":"barge","request_id":"r-1","onset_mic_sample":112000,"mic_sample":115840}""")
        socket.send("""{"type":"audio_start","sample_rate":24000,"request_id":"r-1"}""")
        socket.send("""{"type":"audio_end","request_id":"r-1","reason":"interrupted"}""")

        assertEquals(TurnEnd(turnId = 4, text = "book me a flight", micSample = 163840, by = "model"), withTimeout(5_000) { client.turnEnds.first() })
        assertEquals(Barge(requestId = "r-1", onsetMicSample = 112000, micSample = 115840), withTimeout(5_000) { client.barges.first() })
        val events = mutableListOf<TtsEvent>()
        withTimeout(5_000) { client.tts.first { events += it; it is TtsEvent.End } }
        assertEquals(TtsEvent.End("r-1", reason = "interrupted"), events.last())
    }

    @Test
    fun `playback frames carry the contract's fields`() = runBlocking {
        client = clientWith(turnEngine = true)
        val socket = connect()
        nextFrame()
        socket.send("""{"type":"turn_engine_ready","engine":"tyger"}""")
        withTimeout(5_000) { client.turnEngineStatus.first { it == TurnEngineStatus.Ready } }

        client.sendPlaybackStart("r-1", micSample = 81920)
        client.sendPlaybackStop("r-1", micSample = 129600, replySample = 71520)

        val start = json(nextFrame())
        assertEquals("playback", start.str("type"))
        assertEquals("r-1", start.str("request_id"))
        assertEquals("start", start.str("event"))
        assertEquals("81920", start.str("mic_sample"))
        assertFalse(start.containsKey("reply_sample"), "a start carries no reply_sample")

        val stop = json(nextFrame())
        assertEquals("stop", stop.str("event"))
        assertEquals("129600", stop.str("mic_sample"))
        assertEquals("71520", stop.str("reply_sample"))
    }

    @Test
    fun `the sent count holds only whole frames that went out`() = runBlocking {
        client = clientWith(turnEngine = true)
        val socket = connect()
        nextFrame()
        socket.send("""{"type":"turn_engine_ready","engine":"tyger"}""")
        withTimeout(5_000) { client.turnEngineStatus.first { it == TurnEngineStatus.Ready } }

        client.sendAudio(tenMs)
        assertEquals(0L, client.micSamplesSent, "half a frame waits in the framer")
        client.sendAudio(tenMs)
        assertEquals("<binary 640>", nextFrame())
        assertEquals(320L, client.micSamplesSent)
    }

    @Test
    fun `a plain connection sends neither the opt-in nor playback frames`() = runBlocking {
        client = clientWith(turnEngine = false)
        connect()
        assertEquals(TurnEngineStatus.Off, client.turnEngineStatus.value)
        client.sendPlaybackStart("r-1", micSample = 1)
        client.sendAudio(twentyMs)
        assertEquals("<binary 640>", nextFrame())
        assertNull(withTimeoutOrNull(300) { received.receive() })
        assertTrue(client.micSamplesSent == 320L)
    }
}
