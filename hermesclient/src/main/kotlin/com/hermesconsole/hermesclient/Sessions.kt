package com.hermesconsole.hermesclient

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * The session surface the ready state stands on (T-22, REQ-23): list what conversations exist,
 * open a new one, and scope a dispatched turn to one of them.
 *
 * This is /api/sessions - metadata and history-for-display, never conversation. Every
 * conversational turn goes through /v1/runs and nothing else (REQ-11), and carries nothing but
 * itself: the patched gateway (hermes-patch 0002, T-42) reads the session's stored conversation
 * from its own db and hands it to the run, so continuity is entirely Hermes' business. The
 * console once carried the history to each run itself ([dispatchInSession] fetched, filtered and
 * capped the stored messages); that carry is gone, and [sessionMessages] remains only so a
 * screen can show a session's past.
 */

/** One Hermes session, as the gateway's client-safe representation has it. */
@Serializable
data class HermesSession(
    val id: String,
    val title: String? = null,
    /** The gateway's one-line tail of the conversation - what makes a row recognisable. */
    val preview: String? = null,
    @SerialName("message_count") val messageCount: Int = 0,
    /** Epoch seconds, the gateway's ordering key. Null on a session never spoken in. */
    @SerialName("last_active") val lastActive: Double? = null,
)

/**
 * One spoken turn of a session's history: a user or assistant line, never a tool row. The
 * gateway stores rows with a null content too (a `session_meta` row, an assistant row that only
 * called tools); the default lets them decode as blank, and the spoken-turn filter drops them.
 */
@Serializable
data class HermesMessage(
    val role: String,
    val content: String = "",
    /**
     * The tools the model called in the turn this assistant line ends, in call order - empty on a
     * user line. A call made through the gateway's `tool_call` bridge is listed as the tool it
     * bridged to, so a reader sees `render_artifact`, not the bridge.
     */
    val toolCalls: List<HermesToolCall> = emptyList(),
)

/** One stored tool call: the tool's name and its argument dict. */
@Serializable
data class HermesToolCall(val name: String, val args: JsonObject = JsonObject(emptyMap()))

@Serializable
internal data class SessionList(val data: List<HermesSession> = emptyList())

@Serializable
internal data class SessionEnvelope(val session: HermesSession)

/** A row as the gateway stores it. `tool_calls` stays raw: its shape is the gateway's to vary. */
@Serializable
internal data class StoredMessage(
    val role: String,
    val content: String = "",
    @SerialName("tool_calls") val toolCalls: JsonElement? = null,
)

@Serializable
internal data class MessageList(val data: List<StoredMessage> = emptyList())

/** The sessions the gateway knows, most recently active first (the server's own order). */
suspend fun HermesClient.listSessions(limit: Int = 20): List<HermesSession> =
    get(config.sessionsUrl(limit)) { hermesJson.decodeFromString<SessionList>(it).data }

/** Open an empty session for the next turn to land in. The gateway names it. */
suspend fun HermesClient.createSession(title: String? = null): HermesSession = withContext(Dispatchers.IO) {
    val body = buildJsonObject { title?.let { put("title", JsonPrimitive(it)) } }
    val request = Request.Builder()
        .url(config.sessionsUrl())
        .header("Authorization", config.authorization)
        .post(hermesJson.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA))
        .build()
    http.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw response.toFailure()
        hermesJson.decodeFromString<SessionEnvelope>(response.body.string()).session
    }
}

/**
 * Open a session bearing [title], surviving the gateway's duplicate-title rejection (T-12,
 * REQ-14): the server enforces title uniqueness and fails the whole create on a collision, so
 * two consoles - or one console twice inside a minute - colliding on a date/time title retry
 * with a numbered disambiguation ("(2)", "(3)", ...). Only the collision earns a retry; a 400
 * the gateway means for its own reasons still throws. A collision space somehow exhausted falls
 * back to creating untitled - REQ-14's permitted fallback - because the caller is a visit that
 * needs a session to exist at the end of this call.
 */
suspend fun HermesClient.createSessionDisambiguated(title: String, attempts: Int = TITLE_ATTEMPTS): HermesSession {
    for (n in 1..attempts) {
        val candidate = if (n == 1) title else "$title ($n)"
        try {
            return createSession(candidate)
        } catch (rejected: HermesException.Http) {
            if (!rejected.isDuplicateTitle) throw rejected
        }
    }
    return createSession()
}

/** The gateway's duplicate-title rejection, and nothing else: 400 with its "already in use" reason. */
private val HermesException.Http.isDuplicateTitle: Boolean
    get() = code == 400 && "already in use" in detail

/**
 * The session's spoken history, for showing on a screen. Tool rows and the blank assistant rows
 * that carried only tool calls are filtered here: a chat pane shows what was said, not the
 * model's own tool plumbing. Display is this read's only purpose - a dispatch never needs it.
 */
suspend fun HermesClient.sessionMessages(sessionId: String): List<HermesMessage> =
    get(config.sessionMessagesUrl(sessionId)) { body ->
        spokenTurns(hermesJson.decodeFromString<MessageList>(body).data)
    }

/**
 * The spoken lines, each assistant line carrying the tool calls of the turn it ends. A turn's
 * calls sit on the blank assistant rows before its spoken answer; a user line starts the next turn.
 */
internal fun spokenTurns(rows: List<StoredMessage>): List<HermesMessage> {
    val out = mutableListOf<HermesMessage>()
    val pending = mutableListOf<HermesToolCall>()
    for (row in rows) {
        if (row.role == "user") pending.clear()
        pending += toolCallsOf(row.toolCalls)
        if (row.role !in SPOKEN_ROLES || row.content.isBlank()) continue
        val calls = if (row.role == "assistant") pending.toList() else emptyList()
        out += HermesMessage(row.role, row.content, calls)
        if (row.role == "assistant") pending.clear()
    }
    return out
}

/** A stored `tool_calls` list read leniently: anything that is not a function call is skipped. */
private fun toolCallsOf(raw: JsonElement?): List<HermesToolCall> =
    (raw as? JsonArray).orEmpty().flatMap { call ->
        val function = (call as? JsonObject)?.get("function") as? JsonObject ?: return@flatMap emptyList()
        val name = (function["name"] as? JsonPrimitive)?.contentOrNull ?: return@flatMap emptyList()
        val args = argsOf(function["arguments"])
        if (name == BRIDGE_TOOL) bridgedCalls(args) else listOf(HermesToolCall(name, args))
    }

/** The bridge's `{"calls": [{"name", "arguments"}]}`, as the calls it made. */
private fun bridgedCalls(args: JsonObject): List<HermesToolCall> =
    (args["calls"] as? JsonArray).orEmpty().mapNotNull { inner ->
        val obj = inner as? JsonObject ?: return@mapNotNull null
        val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        HermesToolCall(name, argsOf(obj["arguments"]))
    }

/** Arguments arrive as a JSON string (the OpenAI shape) or an object (the bridge's inner calls). */
private fun argsOf(raw: JsonElement?): JsonObject = when (raw) {
    is JsonObject -> raw
    is JsonPrimitive -> if (raw.isString) {
        runCatching { hermesJson.parseToJsonElement(raw.content) as? JsonObject }.getOrNull()
            ?: JsonObject(emptyMap())
    } else {
        JsonObject(emptyMap())
    }
    else -> JsonObject(emptyMap())
}

/**
 * One turn *in* a session: the utterance and the session id, nothing else. The patched gateway
 * (hermes-patch 0002, T-42) assembles the run's context from the session's own stored
 * conversation, at native fidelity - no cap, no client round-trip of the past on every turn.
 */
suspend fun HermesClient.dispatchInSession(input: String, sessionId: String): String =
    dispatch(input, sessionId = sessionId)

/**
 * Is the gateway there at all? One cheap authenticated GET with a deadline of its own: the health
 * poll must come back on the poll's clock, not OkHttp's patient default - a hung host and a dead
 * one look the same to a wall indicator (REQ-23).
 */
suspend fun HermesClient.probe(): Unit = withContext(Dispatchers.IO) {
    val request = Request.Builder()
        .url(config.modelsUrl())
        .header("Authorization", config.authorization)
        .get()
        .build()
    val call = http.newCall(request)
    call.timeout().timeout(PROBE_DEADLINE_MS, TimeUnit.MILLISECONDS)
    call.execute().use { response ->
        if (!response.isSuccessful) throw response.toFailure()
    }
}

/** GET a URL, check it, hand the body to [parse]. The shared shape of every read on this surface. */
private suspend fun <T> HermesClient.get(url: okhttp3.HttpUrl, parse: (String) -> T): T =
    withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", config.authorization)
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw response.toFailure()
            parse(response.body.string())
        }
    }

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

private val SPOKEN_ROLES = setOf("user", "assistant")

/** The gateway's tool for calling a deferred tool by name; its calls are listed as what they called. */
private const val BRIDGE_TOOL = "tool_call"

private const val PROBE_DEADLINE_MS = 3_000L

/** Bare title plus (2)..(5): far past any real same-minute collision before the untitled fallback. */
private const val TITLE_ATTEMPTS = 5
