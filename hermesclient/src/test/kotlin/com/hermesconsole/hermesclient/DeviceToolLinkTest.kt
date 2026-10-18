package com.hermesconsole.hermesclient

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The device tool link against a fake gateway socket: it dials the device route with the key,
 * declares its tools in a hello, answers each call frame on the same socket, and dials again after
 * a drop. MockWebServer upgrades each dial; the fake gateway side records what the link sends.
 */
class DeviceToolLinkTest {

    private lateinit var server: MockWebServer
    private val http = OkHttpClient()
    private val json = Json

    /** Every text frame the link sent, in order, across all its sockets. */
    private val framesIn = Channel<String>(Channel.UNLIMITED)

    /** The gateway's end of each socket the link opened, in dial order. */
    private val sockets = Channel<WebSocket>(Channel.UNLIMITED)

    private val gateway = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(opened) { opened += webSocket }
            sockets.trySend(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            framesIn.trySend(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }

    /** Every gateway end opened so far, kept so teardown can drop them all. */
    private val opened = mutableListOf<WebSocket>()

    private val launchParameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { putJsonObject("name") { put("type", "string") } }
    }

    private val tools = listOf(
        DeviceTool("list_apps", "List the apps", buildJsonObject { put("type", "object") }) { "Calculator" },
        DeviceTool("launch_app", "Launch an app", launchParameters) { args ->
            val name = args["name"]?.jsonPrimitive?.content
            if (name == "boom") error("launcher is gone")
            "Launched $name"
        },
    )

    private var link: DeviceToolLink? = null

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        link?.close()
        synchronized(opened) { opened.forEach { runCatching { it.close(1001, null) } } }
        server.shutdown()
        http.dispatcher.executorService.shutdown()
    }

    private fun startLink() = DeviceToolLink(
        config = server.hermesConfig(),
        tools = tools,
        http = http,
        reconnectDelayMs = 50,
        maxReconnectDelayMs = 200,
    ).also { link = it; it.connect() }

    private suspend fun nextFrame(): JsonObject =
        withTimeout(5_000) { json.parseToJsonElement(framesIn.receive()).jsonObject }

    private suspend fun nextSocket(): WebSocket = withTimeout(5_000) { sockets.receive() }

    private fun upgrade() = server.enqueue(MockResponse().withWebSocketUpgrade(gateway))

    private fun call(id: String, tool: String, args: JsonObject) = buildJsonObject {
        put("type", "call")
        put("id", id)
        put("tool", tool)
        put("args", args)
    }.toString()

    @Test
    fun `dials the device route with the key and declares every tool in a hello`() = runBlocking {
        upgrade()
        startLink()

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/v1/device/ws", request.path)
        assertEquals("Bearer $TEST_KEY", request.getHeader("Authorization"))

        val hello = nextFrame()
        assertEquals("hello", hello["type"]!!.jsonPrimitive.content)
        val declared = hello["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("list_apps", "launch_app"), declared.map { it["name"]!!.jsonPrimitive.content })
        assertEquals("Launch an app", declared[1]["description"]!!.jsonPrimitive.content)
        assertEquals(launchParameters, declared[1]["parameters"])
    }

    @Test
    fun `a call runs its tool and the result goes back with the same id`() = runBlocking {
        upgrade()
        startLink()
        nextFrame() // hello
        val socket = nextSocket()
        socket.send("""{"type":"ready"}""")
        assertEquals(claim(null), nextFrame())

        socket.send(call("c1", "launch_app", buildJsonObject { put("name", "Calculator") }))

        val result = nextFrame()
        assertEquals("result", result["type"]!!.jsonPrimitive.content)
        assertEquals("c1", result["id"]!!.jsonPrimitive.content)
        assertEquals("Launched Calculator", result["result"]!!.jsonPrimitive.content)
        assertTrue("error" !in result)
    }

    @Test
    fun `an unknown tool and a throwing tool each answer with an error frame`() = runBlocking {
        upgrade()
        startLink()
        nextFrame() // hello
        val socket = nextSocket()

        socket.send(call("c1", "make_coffee", JsonObject(emptyMap())))
        val unknown = nextFrame()
        assertEquals("c1", unknown["id"]!!.jsonPrimitive.content)
        assertTrue(unknown["error"]!!.jsonPrimitive.content.contains("make_coffee"))
        assertTrue("result" !in unknown)

        socket.send(call("c2", "launch_app", buildJsonObject { put("name", "boom") }))
        val thrown = nextFrame()
        assertEquals("c2", thrown["id"]!!.jsonPrimitive.content)
        assertTrue(thrown["error"]!!.jsonPrimitive.content.contains("launcher is gone"))
    }

    @Test
    fun `after a drop it dials again and sends the hello again`() = runBlocking {
        upgrade()
        upgrade()
        startLink()
        assertEquals("hello", nextFrame()["type"]!!.jsonPrimitive.content)

        nextSocket().close(1001, "gateway restarting")

        assertEquals("hello", nextFrame()["type"]!!.jsonPrimitive.content)
        nextSocket()
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `after each ready it claims the current session and claims again on every change`() = runBlocking {
        upgrade()
        upgrade()
        val session = MutableStateFlow<String?>("s1")
        link = DeviceToolLink(
            config = server.hermesConfig(),
            tools = tools,
            http = http,
            currentSession = session,
            reconnectDelayMs = 50,
            maxReconnectDelayMs = 200,
        ).also { it.connect() }
        assertEquals("hello", nextFrame()["type"]!!.jsonPrimitive.content)
        val first = nextSocket()
        first.send("""{"type":"ready"}""")
        assertEquals(claim("s1"), nextFrame())

        session.value = "s2"
        assertEquals(claim("s2"), nextFrame())
        session.value = null
        assertEquals(claim(null), nextFrame())

        session.value = "s3"
        assertEquals(claim("s3"), nextFrame())
        first.close(1001, "gateway restarting")
        assertEquals("hello", nextFrame()["type"]!!.jsonPrimitive.content)
        nextSocket().send("""{"type":"ready"}""")
        assertEquals(claim("s3"), nextFrame())
    }

    private fun claim(id: String?) = buildJsonObject {
        put("type", "session")
        put("id", id)
    }

    /** Runs each OkHttp call on the thread that enqueues it, so a refused dial fails inside dial(). */
    private class CallingThreadExecutor : AbstractExecutorService() {
        @Volatile private var down = false
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() { down = true }
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf<Runnable>().also { down = true }
        override fun isShutdown() = down
        override fun isTerminated() = down
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }

    /** A client whose every dial is counted and refused at once, on the dialling thread. */
    private fun refusingClient(dials: AtomicInteger) = OkHttpClient.Builder()
        .dispatcher(Dispatcher(CallingThreadExecutor()))
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                dials.incrementAndGet()
                return Dns.SYSTEM.lookup(hostname)
            }
        })
        .build()

    private fun closedPort() = ServerSocket(0).use { it.localPort }

    @Test
    fun `a dial that fails while its reconnect is still finishing is dialled again`() = runBlocking {
        val dials = AtomicInteger()
        link = DeviceToolLink(
            config = HermesConfig(host = "localhost", port = closedPort(), apiKey = TEST_KEY),
            tools = tools,
            http = refusingClient(dials),
            reconnectDelayMs = 10,
            maxReconnectDelayMs = 20,
        ).also { it.connect() }

        withTimeout(5_000) { while (dials.get() <= 3) delay(10) }
    }

    @Test
    fun `close during a pending reconnect opens nothing`() = runBlocking {
        val dials = AtomicInteger()
        val started = DeviceToolLink(
            config = HermesConfig(host = "localhost", port = closedPort(), apiKey = TEST_KEY),
            tools = tools,
            http = refusingClient(dials),
            reconnectDelayMs = 200,
            maxReconnectDelayMs = 200,
        ).also { link = it; it.connect() }
        // The first dial was refused; its reconnect now waits out the delay.
        assertEquals(1, dials.get())

        started.close()
        delay(500)

        assertEquals(1, dials.get())
    }

    @Test
    fun `close while a waking reconnect waits on the lock opens nothing`() = runBlocking {
        val dials = AtomicInteger()
        val started = DeviceToolLink(
            config = HermesConfig(host = "localhost", port = closedPort(), apiKey = TEST_KEY),
            tools = tools,
            http = refusingClient(dials),
            reconnectDelayMs = 50,
            maxReconnectDelayMs = 50,
        ).also { link = it; it.connect() }
        assertEquals(1, dials.get())

        // Hold the link's lock past the reconnect delay, so the waking job blocks on it; close
        // inside the hold, then let the job through.
        synchronized(started) {
            Thread.sleep(300)
            started.close()
        }
        delay(300)

        assertEquals(1, dials.get())
    }

    /** A gateway end that records when each socket opened, answers ready or not, then hangs up. */
    private fun hangingUp(opens: MutableList<Long>, sayReady: Boolean) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(opens) { opens += System.nanoTime() }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (sayReady) webSocket.send("""{"type":"ready"}""")
            webSocket.close(1000, "bye")
        }
    }

    private fun gapsMs(opens: List<Long>) = opens.zipWithNext { a, b -> (b - a) / 1_000_000 }

    private suspend fun dialsUntil(opens: MutableList<Long>, count: Int) =
        withTimeout(10_000) { while (synchronized(opens) { opens.size } < count) delay(10) }

    @Test
    fun `a gateway that hangs up before ready makes the link back off`() = runBlocking {
        val opens = mutableListOf<Long>()
        repeat(6) { server.enqueue(MockResponse().withWebSocketUpgrade(hangingUp(opens, sayReady = false))) }
        link = DeviceToolLink(server.hermesConfig(), tools, http, reconnectDelayMs = 50, maxReconnectDelayMs = 5_000)
            .also { it.connect() }

        dialsUntil(opens, 5)

        val gaps = gapsMs(synchronized(opens) { opens.toList() })
        assertTrue(gaps[3] > 3 * gaps[0], "gaps should grow without a ready: $gaps")
    }

    @Test
    fun `a ready resets the backoff to the base delay`() = runBlocking {
        val opens = mutableListOf<Long>()
        repeat(6) { server.enqueue(MockResponse().withWebSocketUpgrade(hangingUp(opens, sayReady = true))) }
        link = DeviceToolLink(server.hermesConfig(), tools, http, reconnectDelayMs = 50, maxReconnectDelayMs = 5_000)
            .also { it.connect() }

        dialsUntil(opens, 5)

        val gaps = gapsMs(synchronized(opens) { opens.toList() })
        assertTrue(gaps.all { it < 300 }, "every redial after a ready should wait about the base delay: $gaps")
    }

    @Test
    fun `the default client pings the socket so a silently dead path is noticed`() {
        assertEquals(30_000, defaultDeviceHttp().pingIntervalMillis)
    }

    @Test
    fun `close stops the link and it does not dial again`() = runBlocking {
        upgrade()
        upgrade()
        val started = startLink()
        nextFrame() // hello
        nextSocket()

        started.close()
        delay(500)

        assertEquals(1, server.requestCount)
    }
}
