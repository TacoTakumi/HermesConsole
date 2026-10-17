package com.hermesconsole.app

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * The console's idleness, on two clocks (T-21/T-16, REQ-22/REQ-16): the conversation handed back
 * after [sessionTimeoutMillis] of nobody needing the console, the screen dark after
 * [screensaverTimeoutMillis], and both undone the instant somebody does.
 *
 * Two rules, and everything here is one of them. A countdown only runs while the loop is READY -
 * a console mid-turn is in use, however long the reply, so any state but READY holds both timers.
 * And restoration is event-shaped, never polled: a touch flips [idle] and [visitOver] on the spot,
 * and the state leaving READY (which is what a wake word becomes by the time anything can see it)
 * flips them the moment the transition lands. Nothing about either is ever told to the voice loop -
 * the screensaver is presentation and the visit is the composition root's, and that one-way
 * dependency is what makes a wake word from the dark an ordinary turn (REQ-22's third clause).
 *
 * They are two clocks and not one because the wall said so (REQ-16, superseding REQ-10). Sharing a
 * single interval is tidier and is what T-08 built, but at 5/5 it makes the visual half of the
 * return unobservable: the screen goes black in the same instant the conversation is handed back,
 * so the console tidies itself in the dark. Split, a person across the room watches it go back to
 * the session list and beep, and go dark two minutes later. They still share everything that made
 * one clock attractive - the same silence, the same engagement rule, the same restart - which is
 * why they are two delays in here rather than two objects that have to be kept agreeing.
 */
class IdleController(
    /** The loop's state: engagement holds both countdowns, and leaving READY restarts them. */
    private val state: StateFlow<ConsoleState>,
    private val scope: CoroutineScope,
    /**
     * How long a conversation stands open with nobody speaking to it (REQ-16), read fresh at the
     * top of every countdown rather than fixed at construction (REQ-17) - the composition root
     * builds these clocks in `onCreate`, so an interval handed over there could only be changed by
     * a reinstall. Read on each restart instead, and a value somebody just saved is the one the
     * very next stretch of silence is measured against.
     */
    private val sessionTimeoutMillis: () -> Long = { DEFAULT_SESSION_TIMEOUT_MILLIS },
    /** How long the wall stays lit with nobody in front of it (REQ-22). The longer of the two. */
    private val screensaverTimeoutMillis: () -> Long = { DEFAULT_SCREENSAVER_TIMEOUT_MILLIS },
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val _idle = MutableStateFlow(false)

    /** Is the screensaver up? The shell renders it. */
    val idle: StateFlow<Boolean> = _idle.asStateFlow()

    private val _visitOver = MutableStateFlow(false)

    /**
     * Has the console stood in a conversation long enough to leave it (REQ-16)? [endVisitWhenIdle]
     * hangs off this, and it falls back to false on the next sign of life - so the visit after a
     * return can end in its turn, rather than the first return being the only one this console ever
     * performs.
     */
    val visitOver: StateFlow<Boolean> = _visitOver.asStateFlow()

    private val touches = MutableSharedFlow<Unit>(extraBufferCapacity = 16)

    /**
     * When the pending restore was triggered, or [NONE]. Written where the trigger lands (the UI
     * thread for a touch, the watcher for a wake) and consumed by [restored] on the frame that
     * brings the shell back - hence atomic rather than a field.
     */
    private val restoreTriggeredAt = AtomicLong(NONE)

    private var watcher: Job? = null

    /** Arm both countdowns. Idempotent, like [VoiceLoop.start]. */
    fun start() {
        if (watcher != null) return
        watcher = scope.launch {
            // Two countdowns off the one silence, deliberately independent rather than one delay
            // chained onto the other: the intervals are separately configurable (REQ-17), so "the
            // shorter one first" is an assumption a person with the config screen open can break,
            // and nothing in here should care which of them that is.
            launch { countdown(sessionTimeoutMillis, "session") { _visitOver.value = true } }
            launch { countdown(screensaverTimeoutMillis, "screensaver") { _idle.value = true } }
        }
    }

    /**
     * One restartable countdown: every touch and every state transition lands here, kills the delay
     * in flight, and starts the decision over. `collectLatest` is the whole timer - there is no
     * tick, no polling loop, and nothing to drift; and because the restart is what measures, the
     * interval runs from the end of the most recent turn rather than from the visit's start
     * (REQ-15).
     */
    private suspend fun countdown(timeoutMillis: () -> Long, what: String, elapsed: () -> Unit) {
        merge(touches, state.map { }).collectLatest {
            if (state.value != ConsoleState.READY) {
                // Engaged. If the screen was dark this is the wake word arriving - the loop took
                // the turn without ever knowing about the screensaver.
                sign(of = "wake")
                return@collectLatest
            }
            // Read here, not at construction: the restart is what applies a saved interval, and
            // leaving the settings screen is a touch, so the very next silence uses the new value.
            val interval = timeoutMillis()
            delay(interval)
            elapsed()
            Log.i(TAG, "no interaction for $interval ms -> $what")
        }
    }

    /**
     * A finger landed anywhere on the console. From the dark this is a restore; on a live shell it
     * only buys the screen another timeout.
     */
    fun touched() {
        sign(of = "touch")
        touches.tryEmit(Unit)
    }

    /**
     * The ready shell is back on screen after a restore: how long did it take?
     *
     * Called by the shell's host on the frame that renders the restored state, and answers null
     * unless a restore was actually pending - so the log line this produces (the evidence REQ-22's
     * "within 1 s" is verified against) can never be minted by an ordinary recomposition.
     */
    fun restored(): Long? {
        val triggeredAt = restoreTriggeredAt.getAndSet(NONE)
        if (triggeredAt == NONE) return null
        val latency = now() - triggeredAt
        Log.i(TAG, "screensaver off -> ready in $latency ms")
        return latency
    }

    /**
     * A sign of life: both clocks go back to zero, and a dark screen comes back.
     *
     * Idempotent by construction on the restore half - only the transition out of the dark stamps a
     * restore - and unconditional on the visit half, which has no latency to measure and no log
     * line to mint. Called from both triggers, because a touch and a wake word are the same news.
     */
    private fun sign(of: String) {
        if (_idle.compareAndSet(expect = true, update = false)) {
            restoreTriggeredAt.set(now())
            Log.i(TAG, "screensaver off: $of")
        }
        _visitOver.value = false
    }

    companion object {
        /** How long a conversation stands open with nobody speaking to it (REQ-16): five minutes. */
        const val DEFAULT_SESSION_TIMEOUT_MILLIS = 5 * 60_000L

        /**
         * How long the wall stays lit with nobody in front of it (REQ-22/REQ-16): seven minutes.
         * Deliberately the longer of the two - the gap is the window in which the console is seen
         * handing the conversation back, rather than doing it behind a screen already dark.
         */
        const val DEFAULT_SCREENSAVER_TIMEOUT_MILLIS = 7 * 60_000L

        private const val NONE = Long.MIN_VALUE
        private const val TAG = "IdleScreen"
    }
}

/**
 * The visit ends in its own silence (T-08/T-16, REQ-07/REQ-08/REQ-16).
 *
 * [IdleController.visitOver] going true *is* the session interval elapsing with nobody engaged, and
 * the countdown that produced it already refuses to run under a turn, however long the reply
 * (REQ-08). Nothing here re-decides any of that. It was [IdleController.idle] until the wall showed
 * why the two clocks have to be separate (REQ-16); the seam is unchanged, and only which boolean it
 * is handed.
 *
 * The dependency stays one-way, which is the point of the seam. The controller is handed a boolean
 * and never learns that a session hangs off it, exactly as it never learns the loop's business;
 * [endVisit] is the composition root's, and what "leaving" costs - the store cleared, the wall
 * handed back, the cue sounded - is decided there. [visitOver] is a StateFlow, so it emits only on
 * change: this is the rising edge, once per stretch of idleness, never a re-fire under a wall that
 * was already handed back.
 */
fun CoroutineScope.endVisitWhenIdle(visitOver: StateFlow<Boolean>, endVisit: () -> Unit): Job = launch {
    visitOver.filter { it }.collect { endVisit() }
}

/**
 * The screensaver (REQ-22): black, and nothing else - deliberately not a dimmed dashboard. On the
 * wall this panel is a lit rectangle in a dark room, and any fixed content is a burn-in pattern on
 * a console meant to run for years; the host additionally drops the backlight while this is up.
 * The touch that dismisses it is caught by the shell's own press observer, so there is nothing to
 * handle here either.
 */
@Composable
fun IdleScreen(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().background(Color.Black))
}
