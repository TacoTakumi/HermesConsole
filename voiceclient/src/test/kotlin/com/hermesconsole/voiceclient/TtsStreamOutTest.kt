package com.hermesconsole.voiceclient

import kotlinx.coroutines.channels.Channel
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
 * T-13 / REQ-12: TTS out - the console pushes the assistant's words as they are generated.
 *
 * This is the outbound half of the TTS stream, and the reason the console can speak before Hermes
 * has finished thinking: every `message.delta` goes straight out as a `tts_stream` frame, and only
 * the run's terminal event closes the request with `done`.
 *
 * The trap this test exists to catch is a client that batches. VoiceManager dispatches
 * *all-but-the-last* sentence it can see (pipeline/tts.py: `sentences[:-1]`), holding the final
 * fragment back in case more text is coming - so a client that helpfully buffers a whole sentence
 * before sending it hands the server exactly one sentence, which the server then sits on until
 * `done` arrives. Being clever here makes the console slower, not faster. So: text goes out
 * verbatim, in the order it arrived, one frame per delta.
 */
class TtsStreamOutTest {

    private lateinit var server: MockWebServer
    private lateinit var client: VoiceManagerClient
    private val http = OkHttpClient()

    /** Every text frame the server received, in wire order. */
    private val received = Channel<String>(Channel.UNLIMITED)

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    received.trySend(text)
                }

                // Finish the close handshake the client starts. Without this the server's socket
                // never completes and MockWebServer gives up waiting for its queue at shutdown.
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

    private suspend fun nextFrame(): Map<String, Any?> =
        withTimeout(5_000) {
            Json.parseToJsonElement(received.receive()).jsonObject
                .mapValues { (_, v) -> v.jsonPrimitive.content }
        }

    @Test
    fun `each delta goes out verbatim as its own tts_stream frame, in order`() = runBlocking {
        client.connect()
        client.awaitState<VoiceLinkState.Connected>()

        // Three deltas of one sentence, exactly as Hermes emits them: no client-side sentence
        // assembly, no coalescing, no waiting for a full stop.
        client.sendTtsText("req-1", "The warp core ")
        client.sendTtsText("req-1", "is stable. ")
        client.sendTtsText("req-1", "All decks report ready.")

        val frames = listOf(nextFrame(), nextFrame(), nextFrame())

        assertEquals(
            listOf("The warp core ", "is stable. ", "All decks report ready."),
            frames.map { it["text"] },
            "deltas must reach the wire verbatim and in order - batching them delays synthesis",
        )
        frames.forEach { frame ->
            assertEquals("tts_stream", frame["type"])
            assertEquals("req-1", frame["request_id"])
            assertEquals("false", frame["done"], "an in-flight delta never closes the request")
        }
    }

    @Test
    fun `done closes the request with no further text`() = runBlocking {
        client.connect()
        client.awaitState<VoiceLinkState.Connected>()

        client.sendTtsText("req-2", "Course laid in.")
        client.sendTtsText("req-2", "", done = true)

        assertEquals("false", nextFrame()["done"])

        val last = nextFrame()
        assertEquals("tts_stream", last["type"])
        assertEquals("req-2", last["request_id"])
        assertEquals("", last["text"])
        assertEquals("true", last["done"], "the run's terminal event is what flushes the last sentence")
    }
}
