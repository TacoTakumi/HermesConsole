package com.hermesconsole.app

import android.util.Log
import com.hermesconsole.hermesclient.HermesMessage
import com.hermesconsole.lcars.LcarsCue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Which session the console is in, and what its conversation pane opens showing (T-43, REQ-23).
 *
 * Entering a session is two things at once: the next turn is scoped to it ([current], read by the
 * loop's session provider at each dispatch), and the pane starts from its stored spoken history
 * ([backlog], fetched through the display read - `sessionMessages`, which already drops tool rows
 * and the blank assistant rows that carried only tool calls). The back-log is fetched once, on
 * entry, and never refreshed behind a turn: the live turn appends *after* it on the wall, and a
 * refetch mid-conversation would hand back the very lines the turn is already showing.
 *
 * [backlog] is null when no session has been entered - the attract state owns the wall - and
 * empty for an entered session with nothing to restore: a fresh conversation. The distinction is
 * the shell's cue for READY looking deliberate (REQ-35) versus a session pane standing open,
 * empty on purpose.
 */
class SessionBacklog(
    /** The display read: the session's stored spoken lines, filtered. Throws when Hermes is not there. */
    private val fetch: suspend (String) -> List<HermesMessage>,
    /**
     * Take the conversation off the wall: whatever turn is still on display belongs to a
     * conversation this pane is done with (T-14, REQ-07). Fired on every entry, before the new
     * content lands, and again when the visit ends and the wall goes back to the attract state.
     *
     * It exists because "the ready screen is shown" is not one owner's decision: the shell asks for
     * an empty pane *and* an empty turn log, so a store that cleared only itself would hand back a
     * wall still rendering the last visitor's exchange over an attract state that believed it was
     * showing. What clearing costs the loop is the composition root's business, not this store's.
     */
    private val clearWall: () -> Unit = {},
    /**
     * Put an entered session's artifacts back on the wall, from its fetched history - the same
     * guard as the backlog: never for a session already left.
     */
    private val restoreWall: (List<HermesMessage>) -> Unit = {},
    private val scope: CoroutineScope,
) {

    private val _current = MutableStateFlow<String?>(null)

    /** Where the next turn lands (REQ-23). Null until a session is entered; the visit's first dispatch then makes one (REQ-06). */
    val current: StateFlow<String?> = _current.asStateFlow()

    private val _backlog = MutableStateFlow<List<HermesMessage>?>(null)

    /** The entered session's stored spoken history. Null until a session is entered. */
    val backlog: StateFlow<List<HermesMessage>?> = _backlog.asStateFlow()

    private var fetchJob: Job? = null

    /** The ready screen's tap: enter [sessionId], pane opening on its stored past. */
    fun select(sessionId: String) {
        clearWall()
        fetchJob?.cancel()
        _current.value = sessionId

        // Empty first, filled when the fetch lands: the wall must never open a session on another
        // session's lines, however slow the gateway is being.
        _backlog.value = emptyList()

        fetchJob = scope.launch {
            runCatching { fetch(sessionId) }
                .onSuccess {
                    if (_current.value != sessionId) return@onSuccess
                    _backlog.value = withoutMediaLines(it)
                    restoreWall(it)
                }
                .onFailure {
                    if (it is CancellationException) throw it
                    Log.w(TAG, "backlog for $sessionId did not load: ${it.message}")
                }
        }
    }

    private val createMutex = Mutex()

    /**
     * The next turn's scope: the entered session, or one made now (T-01, REQ-06, REQ-01).
     *
     * A visit's session is created on its first dispatch and reused by every later turn, so the
     * factory - the gateway's create, at the composition root - runs at most once however the
     * dispatches race: callers queue on the creation and every one of them leaves with the same
     * id. The made session is entered exactly as [opened] enters it: fresh, pane empty, nothing
     * to fetch.
     */
    suspend fun currentOrCreate(create: suspend () -> String): String =
        createMutex.withLock {
            _current.value ?: create().also(::opened)
        }

    /**
     * The loop left READY (T-06, REQ-04): with nothing entered, a visit is beginning, and the
     * pane opens now - empty - before the speaker has finished the utterance. Only the pane:
     * the session waits for the first dispatch ([currentOrCreate]), so a wake that never
     * becomes one leaves the store untouched (REQ-05). With a session entered, the departure
     * is a turn in an open conversation, and nothing here moves.
     */
    fun woken() {
        if (_current.value == null) _backlog.value = emptyList()
    }

    /**
     * The visit is over (T-08, REQ-07): the console stood idle in a conversation, and leaves it.
     *
     * The mirror of entry, and it undoes both halves - the next turn has nowhere to land until
     * something is entered again, and the pane goes back to *null*, the attract state, rather than
     * to the empty list that means a session standing open with nothing in it. A fetch still in
     * flight for the left session is dropped on arrival by [select]'s own guard, which reads a
     * [current] this has already cleared.
     *
     * Undoing entry means undoing all of it, [clearWall] included (T-14, REQ-07/REQ-14): the pane
     * going null is only this store's half of "the ready screen is shown", and the conversation the
     * wall is actually rendering - the finished turn, and the exchanges behind it - belongs to the
     * loop. Clearing one and not the other is a wall that looks returned and reads as occupied,
     * which is the M-02 wall run's one failing exit item. Unconditional, like the rest of the
     * mirror: a wall with nothing on it loses nothing by being handed back twice.
     *
     * Answers whether there was in fact a visit to end - an open pane, session or not (T-12,
     * REQ-14) - because the caller has something to say about it and an already-ready wall must
     * not be announced. The pane is the test, never [current]: a wake word that made nothing is
     * still someone's conversation on the wall.
     */
    fun ended(): Boolean {
        val wasOpen = _backlog.value != null
        _current.value = null
        _backlog.value = null
        clearWall()
        return wasOpen
    }

    /** A session that did not exist a moment ago: entered with an empty pane, nothing to fetch. */
    fun opened(sessionId: String) {
        clearWall()
        fetchJob?.cancel()
        _current.value = sessionId
        _backlog.value = emptyList()
    }

    /**
     * A MEDIA: line the model wrote is never shown as text, live or restored: its target goes back
     * on the wall as an artifact through [restoreWall]. A line left blank by that is dropped.
     */
    private fun withoutMediaLines(history: List<HermesMessage>): List<HermesMessage> =
        history.mapNotNull { message ->
            if (message.role != "assistant") return@mapNotNull message
            val text = splitMediaLines(message.content).text
            if (text.isBlank()) null else message.copy(content = text)
        }

    private companion object {
        const val TAG = "SessionBacklog"
    }
}

/**
 * The visit's end, whole (T-12/T-20, REQ-09/REQ-14/REQ-19): stop the loop, leave the conversation,
 * and say so - once, and only if there was one.
 *
 * An extension rather than a method, so [SessionBacklog] keeps knowing nothing about sound or the
 * loop: what the console does about a visit ending is the composition root's business, and this is
 * that decision written once for the root and the tests to share rather than twice for them to
 * drift. [LcarsCue.SESSION_ENDED] is the only cue in the vocabulary that speaks about a visit,
 * which is why REQ-09 asked for it (the listening pair speak about a turn, and one of those here
 * would read as the console starting to listen to an empty room).
 *
 * [abort] runs first, and the ordering is load-bearing (T-20, REQ-19): [SessionBacklog.ended]
 * hands the wall back through `clearWall`, and the loop's clear refuses any wall a turn still
 * owns - so a departure mid-listening that only cleared the store would leave the window open and
 * the visitor's turn on display over an attract state that believed it was showing. Aborted first,
 * the loop is at READY by the time the wall is asked for, whatever it was doing. No default:
 * every caller says what ending a visit stops, as the loop's own seams do.
 */
fun SessionBacklog.endVisit(abort: () -> Unit, cue: (LcarsCue) -> Unit) {
    abort()
    if (ended()) cue(LcarsCue.SESSION_ENDED)
}
