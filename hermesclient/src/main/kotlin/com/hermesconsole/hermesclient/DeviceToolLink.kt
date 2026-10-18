package com.hermesconsole.hermesclient

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min

/**
 * One tool the console runs for the model: what the model is told about it, and what runs when the
 * model calls it. [run] gets the call's argument object and returns the text the model reads.
 */
class DeviceTool(
    val name: String,
    val description: String,
    /** The JSON Schema of the argument object, as the model sees it. */
    val parameters: JsonObject,
    val run: suspend (args: JsonObject) -> String,
)

/**
 * The console's device tool socket: a WebSocket it opens to the gateway's device route, so the
 * gateway never has to reach the tablet - a console behind a VPN or on a changing address works
 * the same. Each connect declares [tools] in a hello; each call frame the gateway sends runs its
 * tool and the answer goes back on the same socket with the call's id.
 *
 * Kept up until [close]: a dropped socket is dialled again with a capped backoff, and the hello
 * goes out again on every connect. A wrong key is retried too - the console's other Hermes traffic
 * shows that fault, and a capped retry costs the gateway nothing.
 *
 * This is not conversation: turns stay on /v1/runs. The socket only carries tool calls the model
 * made inside those turns.
 */
class DeviceToolLink(
    private val config: HermesConfig,
    private val tools: List<DeviceTool>,
    private val http: OkHttpClient = defaultDeviceHttp(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val reconnectDelayMs: Long = 1_000,
    private val maxReconnectDelayMs: Long = 30_000,
) : AutoCloseable {

    private val byName = tools.associateBy { it.name }

    /**
     * Which dial the live socket belongs to. A callback from an older dial is dropped: OkHttp
     * delivers a dead socket's stragglers on its own threads, and one arriving after a reconnect
     * would otherwise schedule a second dial.
     */
    @Volatile
    private var generation: Long = 0

    @Volatile
    private var wantConnected = false

    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0

    /** Dial the gateway and keep the socket up until [close]. */
    @Synchronized
    fun connect() {
        if (wantConnected) return
        wantConnected = true
        dial()
    }

    @Synchronized
    override fun close() {
        wantConnected = false
        reconnectJob?.cancel()
        reconnectJob = null
        socket?.close(NORMAL_CLOSURE, null)
        socket = null
        generation++
        scope.cancel()
    }

    @Synchronized
    private fun dial() {
        generation++
        val request = Request.Builder()
            .url(config.deviceSocketUrl())
            .header("Authorization", config.authorization)
            .build()
        socket = http.newWebSocket(request, listenerFor(generation))
    }

    /**
     * The reconnect job's dial. Under the lock, the job first stops counting as pending, so a
     * failure of this very dial schedules the next one; and it dials only if [close] has not run
     * while it waited.
     */
    @Synchronized
    private fun redial() {
        reconnectJob = null
        if (wantConnected) dial()
    }

    @Synchronized
    private fun dropped(dialGeneration: Long) {
        if (dialGeneration != generation) return
        socket = null
        if (!wantConnected || reconnectJob?.isActive == true) return
        // Back off so a gateway that is down is not hammered, capped so a console that waited all
        // night is back within seconds of the gateway's return.
        val delayMs = min(reconnectDelayMs shl min(reconnectAttempt, MAX_BACKOFF_SHIFT), maxReconnectDelayMs)
        reconnectAttempt++
        reconnectJob = scope.launch {
            delay(delayMs)
            redial()
        }
    }

    private fun listenerFor(dialGeneration: Long) = object : WebSocketListener() {

        private fun stale() = dialGeneration != generation

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (stale()) return
            reconnectAttempt = 0
            val hello = DeviceHelloFrame(
                type = "hello",
                tools = tools.map { DeviceToolSpec(it.name, it.description, it.parameters) },
            )
            webSocket.send(hermesJson.encodeToString(DeviceHelloFrame.serializer(), hello))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (stale()) return
            val call = parseCall(text) ?: return
            scope.launch { webSocket.send(answer(call)) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
            dropped(dialGeneration)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            dropped(dialGeneration)
        }
    }

    /** Run one call and frame its answer. A tool's failure is the model's to read, never a crash. */
    private suspend fun answer(call: DeviceCall): String {
        val tool = byName[call.tool]
        val frame = if (tool == null) {
            DeviceResultFrame(type = "result", id = call.id, error = "the console has no tool named ${call.tool}")
        } else {
            try {
                DeviceResultFrame(type = "result", id = call.id, result = tool.run(call.args))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                DeviceResultFrame(type = "result", id = call.id, error = e.message ?: e.javaClass.simpleName)
            }
        }
        return hermesJson.encodeToString(DeviceResultFrame.serializer(), frame)
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
        const val MAX_BACKOFF_SHIFT = 16
    }
}

/**
 * The link's HTTP client. It pings the socket every 30 s: a path that dies without a close - a VPN
 * that drops, a gateway that loses power - then fails the socket instead of leaving the console
 * holding a dead link that it never redials.
 */
internal fun defaultDeviceHttp(): OkHttpClient = OkHttpClient.Builder()
    .pingInterval(30, TimeUnit.SECONDS)
    .build()

@Serializable
internal data class DeviceToolSpec(val name: String, val description: String, val parameters: JsonObject)

@Serializable
internal data class DeviceHelloFrame(val type: String, val tools: List<DeviceToolSpec>)

/** One answer. Exactly one of [result] and [error] is set; the other stays off the wire. */
@Serializable
internal data class DeviceResultFrame(
    val type: String,
    val id: String,
    val result: String? = null,
    val error: String? = null,
)

internal class DeviceCall(val id: String, val tool: String, val args: JsonObject)

/** A call frame, or null for anything else (the ready frame, or a frame this console does not know). */
internal fun parseCall(text: String): DeviceCall? = runCatching {
    val frame = hermesJson.parseToJsonElement(text).jsonObject
    if (frame["type"]?.jsonPrimitive?.content != "call") return@runCatching null
    DeviceCall(
        id = frame["id"]!!.jsonPrimitive.content,
        tool = frame["tool"]!!.jsonPrimitive.content,
        args = frame["args"]?.jsonObject ?: JsonObject(emptyMap()),
    )
}.getOrNull()
