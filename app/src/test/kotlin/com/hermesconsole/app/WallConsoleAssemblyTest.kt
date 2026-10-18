package com.hermesconsole.app

import com.hermesconsole.app.devicetools.appTools
import com.hermesconsole.audio.AudioCapture
import com.hermesconsole.audio.AudioConfig
import com.hermesconsole.audio.AudioSink
import com.hermesconsole.hermesclient.HermesConfig
import com.hermesconsole.voiceclient.VoiceManagerConfig
import com.hermesconsole.wakeword.WakeWord
import com.hermesconsole.wakeword.WakeWordSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.ServerSocket

/**
 * T-49: the SHIPPING wall assembly routes artifacts - not the debug screen, not a rig.
 *
 * The whole suite was green while the wall shipped without a router, because every test that
 * proved routing built its own [com.hermesconsole.app.artifacts.ArtifactRouter]: the debug
 * console carries one (T-24), the JVM turn test wires one ([artifacts.ArtifactTurnTest]), and
 * the live check builds its own loop (ArtifactLiveTurnTest). Nothing ever constructed
 * [WallConsole] and asked it for a pane. This test does exactly that: the real class - real
 * [com.hermesconsole.hermesclient.HermesClient] against a canned gateway, real approval desk,
 * real loop - with only the audio hardware faked, because a JVM has none.
 *
 * The stream deliberately never ends the run: a terminal event takes a still-pending approval
 * pane down (T-27), so the canned turn parks mid-run where both taps' evidence holds still.
 */
class WallConsoleAssemblyTest {

    // -- the audio hardware a JVM does not have ------------------------------------------------

    private class NoCapture : AudioCapture {
        override val config = AudioConfig()
        override fun frames(): Flow<ByteArray> = emptyFlow()
        override fun close() = Unit
    }

    private class NoWake : WakeWordSource {
        override fun detections(frames: Flow<ByteArray>): Flow<WakeWord> = flow {
            frames.collect { } // consume, never wake: this turn is typed
        }

        override fun reset() = Unit
        override fun close() = Unit
    }

    private class NoSink(override val sampleRateHz: Int) : AudioSink {
        override fun write(pcm: ByteArray) = Unit
        override fun stop() = Unit
        override fun close() = Unit
    }

    // -- the canned gateway --------------------------------------------------------------------

    /** One SSE event as the gateway frames it (hermesclient's TestSupport, module-internal there). */
    private fun event(json: String) =
        "data: ${json.trimIndent().lineSequence().joinToString("") { it.trim() }}\n"

    /** The stream: each event's data line, then the blank line that terminates it on the wire. */
    private fun sse(vararg events: String) = events.joinToString(separator = "") { "$it\n" }

    // -- the gateway's device socket ------------------------------------------------------------

    /** Each text frame the console sent on its device socket, and whether that socket closed. */
    private val deviceFrames = Channel<String>(Channel.UNLIMITED)
    private val deviceClosed = Channel<Unit>(Channel.UNLIMITED)

    private val deviceGateway = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            deviceFrames.trySend(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            deviceClosed.trySend(Unit)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            deviceClosed.trySend(Unit)
        }
    }

    private val deviceTools = appTools(
        resolved = { emptyList() },
        selfPackage = "com.hermesconsole",
        launch = {},
        consoleInFront = { true },
        bringConsoleToFront = {},
    )

    /** A test that closes the console itself; teardown must not close it twice. */
    private var closedByTest = false

    private lateinit var hermes: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var console: WallConsole

    @BeforeEach
    fun setUp() {
        hermes = MockWebServer()
        hermes.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    request.method == "POST" && path == "/v1/runs" ->
                        MockResponse()
                            .setResponseCode(202)
                            .setHeader("Content-Type", "application/json")
                            .setBody("""{"run_id":"$RUN","status":"started"}""")

                    path == "/v1/runs/$RUN/events" ->
                        MockResponse()
                            .setHeader("Content-Type", "text/event-stream")
                            .setBody(
                                sse(
                                    event("""{"event":"message.delta","run_id":"$RUN","timestamp":1,"delta":"Here is "}"""),
                                    event(
                                        """
                                        {"event":"tool.started","run_id":"$RUN","timestamp":2,"tool":"render_artifact",
                                        "preview":"render_artifact(kind=image)",
                                        "args":{"kind":"image","url":"http://x/warp-core.png"}}
                                        """,
                                    ),
                                    event(
                                        """
                                        {"event":"approval.request","run_id":"$RUN","timestamp":3,"command":"rm -rf /tmp/x",
                                        "description":"delete a directory","pattern_key":"rm","pattern_keys":["rm"],
                                        "allow_permanent":true,"choices":["once","session","always","deny"]}
                                        """,
                                    ),
                                    event("""{"event":"message.delta","run_id":"$RUN","timestamp":4,"delta":"the warp core."}"""),
                                ),
                            )

                    // The visit's first dispatch makes its session (REQ-06): the typed turn below
                    // is unscoped until the loop's provider creates one here.
                    request.method == "POST" && path == "/api/sessions" ->
                        MockResponse()
                            .setResponseCode(201)
                            .setHeader("Content-Type", "application/json")
                            .setBody("""{"session":{"id":"$SESSION","title":null,"preview":null,"message_count":0}}""")

                    // The health monitor's probe (T-22): an empty session list is a healthy gateway.
                    path.startsWith("/api/sessions") ->
                        MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody("""{"object":"list","data":[],"limit":20,"offset":0,"has_more":false}""")

                    // The configured-model seed (2026-08-14): the picker catalog's top level.
                    path == "/api/model/options" ->
                        MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody("""{"providers":[],"model":"Qwen3.6-27B-MTP-Q8","provider":"custom:llamacpp"}""")

                    path == "/v1/device/ws" -> MockResponse().withWebSocketUpgrade(deviceGateway)

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        hermes.start()

        // A VoiceManager nobody answers: the link stays down and every TTS send is the client's
        // documented no-op. The tap under test is fed by the Hermes stream, not the voice link.
        val deadPort = ServerSocket(0).use { it.localPort }

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        console = WallConsole(
            backends = DebugBackends(
                hermes = HermesConfig(host = hermes.hostName, port = hermes.port, apiKey = TEST_KEY),
                voice = VoiceManagerConfig(host = "127.0.0.1", port = deadPort, apiKey = TEST_KEY),
            ),
            cues = {},
            sinks = { rate -> NoSink(rate) },
            capture = NoCapture(),
            wake = NoWake(),
            followUpWindowMillis = { 0L },
            deviceTools = deviceTools,
            scope = scope,
        )
        console.start()
    }

    @AfterEach
    fun tearDown() {
        if (!closedByTest) console.close()
        scope.cancel()
        hermes.shutdown()
    }

    @Test
    fun `a live turn's artifact reaches the shell's panes, approvals beside it on the same tap`(): Unit =
        runBlocking {
            console.loop.say("show me the warp core")

            // The approval desk saw the run's events - the tap the review found alone in the
            // shipping assembly still sees everything with the router beside it.
            withTimeout(10_000) {
                console.approvals.display.first { it is ApprovalDisplay.Pending }
            }

            // THE ASSERTION (T-49): the wall's own router is in that same tap, and its routed
            // artifact comes out of the assembly as the shell's socket type - a pane the content
            // column renders as-is (REQ-15/16). Before this test, no assembly the wall shipped
            // had ever produced one.
            val panes = withTimeout(10_000) { console.panes.first { it.isNotEmpty() } }
            assertEquals(listOf("image"), panes.map { it.title })
        }

    @Test
    fun `the console opens its device socket with both app tools and closes it with itself`(): Unit =
        runBlocking {
            val hello = withTimeout(10_000) { Json.parseToJsonElement(deviceFrames.receive()).jsonObject }
            assertEquals("hello", hello["type"]!!.jsonPrimitive.content)
            assertEquals(
                listOf("list_apps", "launch_app"),
                hello["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content },
            )

            closedByTest = true
            console.close()

            withTimeout(10_000) { deviceClosed.receive() }
        }

    @Test
    fun `leaving the session clears its artifact panes`(): Unit =
        runBlocking {
            console.loop.say("show me the warp core")
            withTimeout(10_000) { console.panes.first { it.isNotEmpty() } }

            console.endVisit()

            withTimeout(10_000) { console.panes.first { it.isEmpty() } }
        }

    @Test
    fun `the MODEL readout reads the gateway's configured model before any run reports`(): Unit =
        runBlocking {
            // No turn is spoken: the seed rides the health poll's arrival, so the shipping
            // assembly shows the configured model from the first reachable moment - not
            // NO DATA until someone talks to the wall (2026-08-14).
            val model = withTimeout(10_000) { console.effectiveModel.first { it != null } }
            assertEquals("Qwen3.6-27B-MTP-Q8", model)
        }

    private companion object {
        const val RUN = "run-49"
        const val SESSION = "session-49"
        const val TEST_KEY = "test-key-not-a-secret"
    }
}
