package com.hermesconsole.voiceclient

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import kotlin.math.min

/**
 * The console's link to VoiceManager: one full-duplex WebSocket to /ws/stream (D-08, REQ-06).
 *
 * Dials, authenticates, pushes capture audio, carries the transcript and the TTS stream back, and
 * puts itself together again when the socket dies (REQ-10) - a tablet on a wall does not get
 * restarted.
 *
 * Pure Kotlin/JVM by construction (D-09): nothing here imports android.* or androidx.*.
 */
class VoiceManagerClient(
    private val config: VoiceManagerConfig,
    private val http: OkHttpClient = OkHttpClient(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _state = MutableStateFlow<VoiceLinkState>(VoiceLinkState.Idle)

    /** The link's state, for the console to render. [VoiceLinkState.AuthFailed] is terminal. */
    val state: StateFlow<VoiceLinkState> = _state.asStateFlow()

    private val _transcript = MutableStateFlow(Transcript())

    /**
     * What the speaker has said this turn. Updates as VoiceManager revises it - including revising
     * a segment it already sent, which is why the console renders [Transcript.segments] rather than
     * appending text as it arrives (REQ-07).
     */
    val transcript: StateFlow<Transcript> = _transcript.asStateFlow()

    /**
     * The TTS stream: Start / Audio / End in wire order, each chunk tagged with the rate its turn
     * declared (REQ-09). Unbounded and ordered - dropping a chunk of speech would be audible, and
     * reordering one would be worse.
     */
    private val ttsEvents = Channel<TtsEvent>(Channel.UNLIMITED)
    val tts: Flow<TtsEvent> = ttsEvents.receiveAsFlow()

    /**
     * Which dial the live socket belongs to. Every listener captures the generation it was created
     * under, and a callback from any other generation is dropped (REQ-10): OkHttp delivers a dead
     * socket's stragglers on its own threads, and one arriving after we have reconnected would
     * otherwise knock the fresh link down or splice the last turn's audio into this one.
     */
    @Volatile
    internal var generation: Long = 0
        private set

    /** The consumer wants a link. False once [close] is called, or once the key is rejected. */
    @Volatile
    private var wantConnected = false

    @Volatile
    private var ttsSampleRate: Int? = null

    /** The [turnStart] call waiting on its turn_started ack, if any. */
    @Volatile
    private var pendingTurnStart: CompletableDeferred<Int>? = null

    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0

    private val framer = PcmFramer(config.chunkBytes) { frame ->
        socket?.send(frame.toByteString())
    }

    /**
     * Dial VoiceManager, and keep it dialled until [close]. A no-op once the key has been rejected:
     * a bad credential does not get retried (REQ-06).
     */
    @Synchronized
    fun connect() {
        if (_state.value == VoiceLinkState.AuthFailed || wantConnected) return
        wantConnected = true
        dial()
    }

    /**
     * Offer capture audio. Samples are mono 16-bit at [VoiceManagerConfig.sampleRate]; the client
     * cuts them into wire frames, so any buffer size is fine. Audio offered while the link is down
     * is dropped rather than queued - a backlog of stale speech helps nobody.
     */
    @Synchronized
    fun sendAudio(samples: ShortArray) {
        if (_state.value != VoiceLinkState.Connected) return
        framer.offer(samples)
    }

    /**
     * Push a piece of the assistant's reply to be spoken, under [requestId] (REQ-12).
     *
     * Send each token as it arrives - do not assemble sentences first. VoiceManager dispatches every
     * sentence it can see the end of and keeps the trailing fragment, so a caller that "helpfully"
     * waits for a full stop before sending hands the server a lone sentence that it then sits on.
     * Buffering here makes the console slower to speak, which is the one thing REQ-12 exists to
     * prevent.
     *
     * [done] closes the request and flushes that trailing fragment. It is the run's terminal event,
     * not a sentence boundary, that ends a turn.
     */
    @Synchronized
    fun sendTtsText(requestId: String, text: String, done: Boolean = false) {
        socket?.send(protocolJson.encodeToString(TtsStreamFrame(requestId = requestId, text = text, done = done)))
    }

    /**
     * Tell VoiceManager to stop synthesising [requestId]. Barge-in's outbound half: the console has
     * already stopped playing, and this stops the speech being made (T-14).
     */
    @Synchronized
    fun cancelTts(requestId: String) {
        socket?.send(protocolJson.encodeToString(TtsCancelFrame(requestId = requestId)))
    }

    /**
     * Open a new conversation turn and return its id (D-18). Sends the bare turn_start control
     * frame and suspends until VoiceManager's turn_started ack - the server owns the turn counter,
     * so the id comes from the ack, never from counting locally. Filter [transcript] segments by
     * the returned id to fence out a previous turn's stale utterances.
     *
     * The ack only arrives on a live link: the call is cancelled if the link is closed or redials
     * while it waits, so bound it with `withTimeout` if the caller cannot wait for a reconnect.
     */
    suspend fun turnStart(): Int {
        val ack = CompletableDeferred<Int>()
        synchronized(this) {
            // A second ask supersedes the first: the server will ack the newest turn_start, and
            // handing that id to the older waiter too would have two callers owning one turn.
            pendingTurnStart?.cancel()
            pendingTurnStart = ack
            socket?.send(protocolJson.encodeToString(TurnStartFrame()))
        }
        return ack.await()
    }

    /** Close the link for good. Does not clear a terminal [VoiceLinkState.AuthFailed]. */
    @Synchronized
    fun close() {
        wantConnected = false
        reconnectJob?.cancel()
        reconnectJob = null
        socket?.close(NORMAL_CLOSURE, null)
        socket = null
        pendingTurnStart?.cancel()
        pendingTurnStart = null
        // Bump the generation: whatever the closing socket says on its way out is not ours to hear.
        generation++
        if (_state.value != VoiceLinkState.AuthFailed) _state.value = VoiceLinkState.Idle
    }

    /** Release the client's own scope. Only for a client that will not be reconnected. */
    fun shutdown() {
        close()
        scope.cancel()
    }

    @Synchronized
    private fun dial() {
        val request = Request.Builder().url(config.streamUrl())
        try {
            request.header("Authorization", config.authorization)
        } catch (_: IllegalArgumentException) {
            // A key with a control or non-ASCII character cannot go on the wire at all. That is a bad
            // credential like any other, not a crash in the caller's connect().
            authFailed()
            return
        }
        generation++
        drainStaleState()
        _state.value = VoiceLinkState.Connecting
        socket = http.newWebSocket(request.build(), listenerFor(generation))
    }

    /**
     * Forget everything the last socket left behind. Half a sentence from a dead link is not the
     * start of this turn, and its undelivered TTS is not this turn's speech (REQ-10).
     */
    private fun drainStaleState() {
        framer.reset()
        _transcript.value = Transcript()
        ttsSampleRate = null
        // A turn_start sent on the dead socket will never be acked by the new one.
        pendingTurnStart?.cancel()
        pendingTurnStart = null
        @Suppress("ControlFlowWithEmptyBody")
        while (ttsEvents.tryReceive().isSuccess) {
        }
    }

    @Synchronized
    private fun scheduleReconnect() {
        if (!wantConnected || reconnectJob?.isActive == true) return
        // Back off, so a server that is down does not get hammered - but cap it, so a console that
        // has been waiting all night still comes back promptly when the server returns.
        val delayMs = min(
            config.reconnectDelayMs shl min(reconnectAttempt, MAX_BACKOFF_SHIFT),
            config.maxReconnectDelayMs,
        )
        reconnectAttempt++
        reconnectJob = scope.launch {
            delay(delayMs)
            if (wantConnected) dial()
        }
    }

    /**
     * The listener for one dial. It ignores everything that reaches it after its generation has been
     * superseded - see [generation].
     */
    internal fun listenerFor(dialGeneration: Long) = object : WebSocketListener() {

        private fun stale() = dialGeneration != generation

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (stale()) return
            reconnectAttempt = 0
            _state.value = VoiceLinkState.Connected
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (stale()) return
            when (val frame = parseInboundFrame(text)) {
                is InboundFrame.Transcript ->
                    _transcript.update { it.upsert(frame.frame.toSegment()) }

                is InboundFrame.AudioStart -> {
                    ttsSampleRate = frame.frame.sampleRate
                    ttsEvents.trySend(TtsEvent.Start(frame.frame.requestId, frame.frame.sampleRate))
                }

                is InboundFrame.AudioEnd -> {
                    ttsSampleRate = null
                    ttsEvents.trySend(TtsEvent.End(frame.frame.requestId))
                }

                // An unsolicited ack (no waiter) is dropped, per the ignore-the-unknown posture.
                is InboundFrame.TurnStarted -> pendingTurnStart?.complete(frame.frame.turnId)

                // Unknown or malformed: ignore. A console must not die on a server upgrade.
                null -> Unit
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (stale()) return
            // Inbound binary is TTS PCM. Tag it with the rate its turn declared - PCM that arrives
            // before any audio_start has no declared rate, and guessing one would play it wrong.
            val rate = ttsSampleRate ?: return
            ttsEvents.trySend(TtsEvent.Audio(bytes.toByteArray(), rate))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
            if (stale()) return
            // VoiceManager rejects a bad token before it accepts the upgrade, which arrives in
            // onFailure as an HTTP 403. A policy-violation close is kept as the same verdict for a
            // server that closes instead. Everything else (including the 1001 inactivity close)
            // is a reconnect.
            if (code == POLICY_VIOLATION) authFailed()
            else dropped(VoiceLinkState.Dropped(code, reason))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (stale()) return
            if (response?.code == 401 || response?.code == 403) authFailed()
            else dropped(VoiceLinkState.Dropped(response?.code, t.message))
        }
    }

    @Synchronized
    private fun authFailed() {
        // Terminal: retrying the same wrong key just hammers the server, and a bad credential is a
        // human's problem (REQ-06).
        wantConnected = false
        socket = null
        _state.value = VoiceLinkState.AuthFailed
    }

    @Synchronized
    private fun dropped(end: VoiceLinkState.Dropped) {
        socket = null
        if (_state.value == VoiceLinkState.AuthFailed) return
        _state.value = end
        scheduleReconnect()
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
        const val POLICY_VIOLATION = 1008

        /** Caps the doubling before maxReconnectDelayMs does, so the shift cannot overflow. */
        const val MAX_BACKOFF_SHIFT = 16
    }
}
