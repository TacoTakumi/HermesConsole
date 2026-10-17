package com.hermesconsole.app

import android.util.Log
import com.hermesconsole.hermesclient.HermesSession
import com.hermesconsole.voiceclient.VoiceLinkState
import com.hermesconsole.voiceclient.VoiceManagerInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the wall shows about a backend: not yet asked, answering, or not answering (REQ-23). */
enum class BackendHealth { UNKNOWN, OK, FAILED }

/**
 * Live reachability for both backends (T-22, REQ-23), shaped by how each one talks.
 *
 * Hermes is request/response, so its verdict is a poll: one cheap authenticated call every
 * [POLL_MILLIS], and the call is the session list - the same fetch feeds the ready state's
 * session rows, so the list is never staler than the health lamp that vouches for it.
 * VoiceManager holds a socket open, so its verdict is a mapping of the link state the client
 * already maintains - steadied, because a redialling client flaps Dropped/Connecting for as long
 * as the server is down, and an indicator that flapped with it would be unreadable on a wall.
 *
 * The acceptance clock (indicator moves within 10 s of a kill or a restart) is bounded by
 * [POLL_MILLIS] plus the probe's own deadline on the Hermes side, and by the client's reconnect
 * backoff on the VoiceManager side. Transitions are logged with the tag [TAG] - the on-device
 * timing evidence reads straight out of logcat.
 */
class HealthMonitor(
    /** The one poll: the session list, from hermesclient. Throws when the gateway is not there. */
    private val probe: suspend () -> List<HermesSession>,
    private val link: StateFlow<VoiceLinkState>,
    /** GET /info, from voiceclient (T-05, REQ-09). Throws when VoiceManager is not there. */
    private val vmInfoProbe: suspend () -> VoiceManagerInfo,
    /** The gateway's advertised profile (T-06, REQ-07). Throws when the gateway is not there. */
    private val profileProbe: suspend () -> String?,
    /** The gateway's configured model selection (2026-08-14). Throws when the gateway is not there. */
    private val modelProbe: suspend () -> String?,
    private val scope: CoroutineScope,
) {

    private val _hermes = MutableStateFlow(BackendHealth.UNKNOWN)

    /** Is the Hermes gateway answering? */
    val hermes: StateFlow<BackendHealth> = _hermes.asStateFlow()

    private val _voice = MutableStateFlow(BackendHealth.UNKNOWN)

    /** Is the VoiceManager link up? */
    val voice: StateFlow<BackendHealth> = _voice.asStateFlow()

    private val _sessions = MutableStateFlow<List<HermesSession>>(emptyList())

    /**
     * The gateway's sessions as of the last successful poll. Held through a failed one: a stale
     * list next to a FAILED lamp reads honestly, a list snapping blank reads like data loss.
     */
    val sessions: StateFlow<List<HermesSession>> = _sessions.asStateFlow()

    private val _vmInfo = MutableStateFlow<VoiceManagerInfo?>(null)

    /**
     * What VoiceManager says it is running, as of the last successful /info poll - the engines
     * block's source (T-05, REQ-09). Null before the first answer, held through a failed poll
     * after it - the same rule as the session list: the lamp carries the outage, and a readout
     * snapping to NO DATA on every restart window reads as data loss (2026-08-14).
     */
    val vmInfo: StateFlow<VoiceManagerInfo?> = _vmInfo.asStateFlow()

    private val _profile = MutableStateFlow<String?>(null)

    /**
     * The gateway's advertised profile name, as of the last successful poll - the profile block's
     * source (T-06, REQ-07). Null before the first answer, held through a failed poll after it:
     * NO DATA means "never answered", not "not answering right now" (2026-08-14).
     */
    val profile: StateFlow<String?> = _profile.asStateFlow()

    private val _configuredModel = MutableStateFlow<String?>(null)

    /**
     * The gateway's configured model selection, the MODEL block's seed (2026-08-14): known the
     * moment the gateway answers, where the effective model waits for a completed run to report
     * (ModelReadout, which outranks this). Fetched on the link coming up rather than on every
     * poll - /api/model/options carries the whole provider catalog, and the selection only
     * changes across restarts, which the lamp's arrival already marks. Retried on the clock
     * while empty, held sticky through an outage like every readout.
     */
    val configuredModel: StateFlow<String?> = _configuredModel.asStateFlow()

    /** Rings to bring the next poll forward - conflated, because "soon" does not accumulate. */
    private val pokes = Channel<Unit>(Channel.CONFLATED)

    private var poller: Job? = null

    /** Arm both watches. Idempotent, like [VoiceLoop.start]. */
    fun start() {
        if (poller != null) return

        poller = scope.launch {
            while (true) {
                val wasOk = _hermes.value == BackendHealth.OK
                val verdict = runCatching { probe() }
                verdict
                    .onSuccess { _sessions.value = it }
                    .onFailure { Log.i(TAG, "hermes poll failed: ${it.message}") }
                transition(_hermes, "hermes", if (verdict.isSuccess) BackendHealth.OK else BackendHealth.FAILED)
                // The configured model, on the link's arrival rather than the clock (2026-08-14):
                // an answering gateway that was not answering before - or whose catalog fetch has
                // not yet landed - is asked once; a steadily OK link is not asked again.
                if (verdict.isSuccess && (!wasOk || _configuredModel.value == null)) {
                    runCatching { modelProbe() }
                        .onSuccess { _configuredModel.value = it }
                        .onFailure { Log.i(TAG, "hermes model options poll failed: ${it.message}") }
                }
                // The engines readout, on the same clock (T-05, REQ-09): its own runCatching,
                // because one backend being down is no reason to stop reading the other. A
                // failed poll holds the last answer, like the session list above.
                runCatching { vmInfoProbe() }
                    .onSuccess { _vmInfo.value = it }
                    .onFailure { Log.i(TAG, "voicemanager info poll failed: ${it.message}") }
                // The profile readout (T-06, REQ-07), likewise held through an outage - the
                // lamp says whether the gateway is answering; the readout says who it was.
                runCatching { profileProbe() }
                    .onSuccess { _profile.value = it }
                    .onFailure { Log.i(TAG, "hermes profile poll failed: ${it.message}") }
                withTimeoutOrNull(POLL_MILLIS) { pokes.receive() }
            }
        }

        scope.launch {
            link.collect { state ->
                when (state) {
                    is VoiceLinkState.Connected -> transition(_voice, "voicemanager", BackendHealth.OK)
                    is VoiceLinkState.Dropped,
                    VoiceLinkState.AuthFailed,
                    -> transition(_voice, "voicemanager", BackendHealth.FAILED)
                    // Idle before the first dial, Connecting mid-redial: neither is a verdict.
                    // The last one stands until the socket settles it one way or the other.
                    VoiceLinkState.Idle, VoiceLinkState.Connecting -> Unit
                }
            }
        }
    }

    /** The ready state just changed something (a create, a switch): poll now, not a period from now. */
    fun poke() {
        pokes.trySend(Unit)
    }

    /** One writer per lamp; the log line under the state change is REQ-23's timing evidence. */
    private fun transition(lamp: MutableStateFlow<BackendHealth>, name: String, to: BackendHealth) {
        val from = lamp.value
        if (from == to) return
        lamp.value = to
        Log.i(TAG, "$name health: $from -> $to")
    }

    companion object {
        /**
         * The Hermes detection clock (REQ-23): worst case is a kill landing just after a poll, so
         * detection takes up to a period plus the probe's 3 s deadline - 8 s against the 10 s
         * acceptance. Named and asserted in tests so a retune cannot quietly break the promise.
         */
        const val POLL_MILLIS = 5_000L

        private const val TAG = "BackendHealth"
    }
}
