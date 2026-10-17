package com.hermesconsole.hermesclient

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.Duration

/**
 * The console's link to Hermes: POST /v1/runs to start a turn, GET /v1/runs/{id}/events to hear it
 * (D-06, REQ-11).
 *
 * Hand-rolled on OkHttp, with no agent framework behind it: Hermes runs its own agent loop and its
 * own tools server-side, so a client-side one earns nothing and costs the token stream that makes
 * the console speak while the model is still talking (REQ-12).
 *
 * Pure Kotlin/JVM by construction (D-09): nothing here imports android.* or androidx.*.
 */
class HermesClient(
    internal val config: HermesConfig,
    internal val http: OkHttpClient = OkHttpClient(),
) {
    /**
     * The same client, with the read timeout taken off. An SSE stream is idle by design - Hermes
     * thinks, calls a tool, waits on a human at an approval - and OkHttp's default 10 s read timeout
     * would guillotine the run mid-thought. The gateway's 30 s keepalive comments are not enough to
     * hold it open, and lengthening the timeout only moves the guillotine. Built from [http], so it
     * shares its connection pool and dispatcher rather than standing up a second one.
     */
    private val streamHttp: OkHttpClient by lazy {
        http.newBuilder().readTimeout(Duration.ZERO).build()
    }

    /**
     * Start a turn. Returns the run id that the rest of the turn hangs off: its event stream
     * ([events]), its stop, its approvals.
     *
     * [sessionId] is Hermes' conversation scope - pass the same one across turns and the patched
     * gateway (hermes-patch 0002, T-42) both persists the turns and reads them back as the run's
     * context, which is why the console carries no conversation state of its own.
     *
     * @throws HermesException.Auth if the key is rejected - terminal, and not worth retrying.
     */
    suspend fun dispatch(
        input: String,
        sessionId: String? = null,
        instructions: String? = null,
        model: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val body = hermesJson.encodeToString(RunRequest(input, sessionId, instructions, model))
        val request = Request.Builder()
            .url(config.runsUrl())
            .header("Authorization", config.authorization)
            .post(body.toRequestBody(JSON))
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw response.toFailure()
            val accepted = runCatching {
                hermesJson.decodeFromString<RunAccepted>(response.body.string())
            }.getOrNull()
            // A run with no id is a turn we can neither hear nor stop. Fail here, loudly, rather
            // than hand back a blank and stream nothing.
            accepted?.runId?.takeIf { it.isNotBlank() }
                ?: throw HermesException.Protocol("POST /v1/runs returned no run_id")
        }
    }

    /**
     * The run's events, as they happen. Cold: collecting subscribes, and the flow completes when the
     * stream closes - which the gateway does once the run reaches a terminal event.
     *
     * Malformed and unrecognised events are skipped rather than thrown (REQ-11): the console must
     * survive a Hermes upgrade that grows an event it has never heard of.
     *
     * @throws HermesException.Auth if the key is rejected; [HermesException.Http] if the run is gone.
     */
    fun events(runId: String): Flow<HermesEvent> = flow {
        val request = Request.Builder()
            .url(config.eventsUrl(runId))
            .header("Authorization", config.authorization)
            .header("Accept", "text/event-stream")
            .build()

        val call = streamHttp.newCall(request)
        // A blocking socket read does not notice that its coroutine was cancelled - the call does.
        // Barge-in cancels this collection mid-stream (T-14), and without this the reader would sit
        // on the dead socket, holding a connection and a thread, until the server got bored.
        val cancelWithCollector = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw response.toFailure()
                val framer = SseFramer()
                val source = response.body.source()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    val payload = framer.read(line) ?: continue
                    val event = parseEvent(payload) ?: continue
                    emit(event)
                    // The run ends here, so the stream does too. This is not just tidiness: a
                    // stopped run's agent keeps generating for a moment - it runs in an executor
                    // thread that Hermes' interrupt cannot preempt - so a token from the turn we
                    // just killed can land behind run.cancelled. Delivering it would let a
                    // barged-in-on reply speak over the human who interrupted it (T-14).
                    if (event.isTerminal) break
                }
            }
        } catch (e: IOException) {
            // The socket we just cancelled ourselves reports as a broken read. That is the
            // collector leaving, not Hermes failing.
            currentCoroutineContext().ensureActive()
            throw e
        } finally {
            cancelWithCollector.dispose()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Stop a run, at the gateway. The server half of barge-in (REQ-13): the human talking over the
     * assistant has to kill the run, not just stop listening to it - an abandoned run keeps thinking,
     * keeps calling tools, and keeps costing tokens for an answer nobody will hear.
     *
     * Idempotent by design. A run that has already ended is a 404, and that is the outcome stop
     * wanted: barge-in races the last token, and losing that race is not an error worth throwing at
     * a console that is already listening to the next turn.
     *
     * Returns once the gateway has taken the stop, not once the run is quiet - the last few tokens
     * of a killed run can still arrive, and [events] is what drops them.
     *
     * @throws HermesException.Auth if the key is rejected; [HermesException.Http] on any other refusal.
     */
    suspend fun stop(runId: String): Unit = withContext(Dispatchers.IO) {
        // The gateway takes the run id from the path and reads no body; OkHttp still needs one on a
        // POST, so it gets an empty one.
        val request = Request.Builder()
            .url(config.stopUrl(runId))
            .header("Authorization", config.authorization)
            .post(EMPTY_BODY)
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 404) throw response.toFailure()
        }
    }

    /**
     * Answer the approval a run is stalled on (REQ-14). Until this lands, Hermes is blocked in the
     * tool call and the stream is silent - which is why an unanswered request is auto-denied rather
     * than left to hang (T-27).
     *
     * A [ApprovalChoice.DENY] does not end the run: Hermes tells the model the call was refused and
     * the turn carries on without it. To end the turn, [stop] it.
     *
     * @throws HermesException.Http 409 if the gateway is no longer waiting on this decision - the
     *   human and the auto-deny can both answer, and only the first one wins.
     * @throws HermesException.Auth if the key is rejected.
     */
    suspend fun resolveApproval(runId: String, choice: ApprovalChoice): Unit = withContext(Dispatchers.IO) {
        val body = hermesJson.encodeToString(ApprovalDecision(choice.wire))
        val request = Request.Builder()
            .url(config.approvalUrl(runId))
            .header("Authorization", config.authorization)
            .post(body.toRequestBody(JSON))
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw response.toFailure()
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()

        /** A POST with nothing to say: /stop names its run in the path and reads no body. */
        val EMPTY_BODY = ByteArray(0).toRequestBody()
    }
}

/** An error response, as an exception. The body may carry Hermes' reason; the key never does. */
internal fun Response.toFailure(): HermesException =
    if (code == 401 || code == 403) {
        // Terminal (REQ-28 keeps the key out of the message): a bad credential is a human's
        // problem, and retrying it just hammers the gateway with the same wrong key.
        HermesException.Auth(code)
    } else {
        HermesException.Http(code, runCatching { body.string() }.getOrDefault("").take(DETAIL_MAX))
    }

/** Enough of an error body to diagnose it, not enough to paste a page of HTML into a log. */
private const val DETAIL_MAX = 200

/** What can go wrong talking to Hermes. No message ever carries the api key (REQ-28). */
sealed class HermesException(message: String) : RuntimeException(message) {

    /** The gateway rejected the key. Terminal: the same key will be rejected again. */
    class Auth(val code: Int) : HermesException("hermes rejected the api key (HTTP $code)")

    /** The gateway answered, unhappily. */
    class Http(val code: Int, val detail: String) : HermesException("hermes returned HTTP $code: $detail")

    /** The gateway answered with something we cannot act on. */
    class Protocol(detail: String) : HermesException(detail)
}
