package com.hermesconsole.voiceclient

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * T-08 / REQ-09: TTS in - the sample rate comes off the wire, not out of a constant.
 *
 * VoiceManager declares the rate per turn in audio_start, and it can change between turns (a
 * different voice, a different engine). The console must therefore recreate its AudioTrack per turn
 * at the declared rate.
 *
 * The trap this test exists to catch: publishing the rate on a side channel from the PCM. Then a
 * turn's first chunks can reach the consumer while the rate still reads as the *previous* turn's -
 * and the audio plays out at the wrong speed, or gets resampled. So the rate travels with its
 * audio, on one ordered channel: what a passing run proves is that every chunk names the rate it
 * was declared under, in order, with no frame dropped or altered.
 */
class TtsStreamTest {

    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient

    private val serverSocket = Channel<WebSocket>(Channel.CONFLATED)

    /** Text frames the fake server received from the client (i.e. tts_cancel). */
    private val textIn = Channel<String>(Channel.UNLIMITED)

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        http = OkHttpClient()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                serverSocket.trySend(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                textIn.trySend(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit

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

    private suspend fun connectedClient(): Pair<VoiceManagerClient, WebSocket> {
        val client = VoiceManagerClient(
            VoiceManagerConfig(host = server.hostName, port = server.port, apiKey = "s3cret"),
            http,
        )
        client.connect()
        client.awaitState<VoiceLinkState.Connected>()
        return client to withTimeout(5_000) { serverSocket.receive() }
    }

    @Test
    fun `each turn's PCM carries the rate that turn declared, in order, with nothing dropped`() = runBlocking {
        val (client, socket) = connectedClient()
        val events = Channel<TtsEvent>(Channel.UNLIMITED)
        val pump = CoroutineScope(Dispatchers.Default).launch {
            client.tts.collect { events.send(it) }
        }

        val turnOneA = byteArrayOf(1, 2, 3, 4)
        val turnOneB = byteArrayOf(5, 6, 7, 8)
        val turnTwoA = byteArrayOf(9, 10)

        // Turn one, at 24 kHz.
        socket.send("""{"type":"audio_start","request_id":"r1","sample_rate":24000}""")
        socket.send(turnOneA.toByteString())
        socket.send(turnOneB.toByteString())
        socket.send("""{"type":"audio_end","request_id":"r1"}""")

        // Turn two, at a different rate. The console must not still be at 24 kHz.
        socket.send("""{"type":"audio_start","request_id":"r2","sample_rate":22050}""")
        socket.send(turnTwoA.toByteString())
        socket.send("""{"type":"audio_end","request_id":"r2"}""")

        val received = withTimeout(5_000) { List(7) { events.receive() } }

        // Turn one: a Start the consumer recreates its AudioTrack on, then PCM tagged 24000.
        assertEquals(TtsEvent.Start("r1", 24_000), received[0])
        assertTrue(received[1] is TtsEvent.Audio)
        assertEquals(24_000, (received[1] as TtsEvent.Audio).sampleRate)
        assertArrayEquals(turnOneA, (received[1] as TtsEvent.Audio).pcm)
        assertEquals(24_000, (received[2] as TtsEvent.Audio).sampleRate)
        assertArrayEquals(turnOneB, (received[2] as TtsEvent.Audio).pcm)
        assertEquals(TtsEvent.End("r1"), received[3])

        // Turn two: the declared rate changed, and the PCM says so itself.
        assertEquals(TtsEvent.Start("r2", 22_050), received[4])
        assertEquals(22_050, (received[5] as TtsEvent.Audio).sampleRate)
        assertArrayEquals(turnTwoA, (received[5] as TtsEvent.Audio).pcm)
        assertEquals(TtsEvent.End("r2"), received[6])

        pump.cancel()
        client.close()
    }

    @Test
    fun `tts_cancel goes out as a text frame naming the request`() = runBlocking {
        val (client, _) = connectedClient()

        client.cancelTts("r1")

        val sent = withTimeout(5_000) { textIn.receive() }
        val frame = protocolJson.parseToJsonElement(sent).jsonObject
        assertEquals("tts_cancel", frame["type"]?.jsonPrimitive?.content)
        assertEquals("r1", frame["request_id"]?.jsonPrimitive?.content)

        client.close()
    }
}
