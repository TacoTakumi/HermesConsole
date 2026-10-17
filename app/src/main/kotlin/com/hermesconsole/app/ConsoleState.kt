package com.hermesconsole.app

import com.hermesconsole.audio.SILENCE_DBFS

/**
 * What the console is doing, as the shell renders it (T-13).
 *
 * Four states, because four is what a person across the room can read at a glance: the console is
 * waiting, hearing you, thinking, or talking. Everything finer-grained than that - which run is in
 * flight, how much of the reply has been synthesised - is the loop's business, not the shell's.
 */
enum class ConsoleState {

    /** Idle. Listening for the wake word, and for nothing else: no audio leaves the tablet. */
    READY,

    /** Woken. Capture is going to VoiceManager and the transcript is being revised as you speak. */
    LISTENING,

    /** The turn is with Hermes: dispatched, and not yet speaking. */
    THINKING,

    /** TTS audio is reaching the speaker. */
    SPEAKING,
}

/**
 * One turn, timed - and the evidence for REQ-12.
 *
 * The requirement is a claim about *ordering*, not about throughput: the console must be speaking
 * while Hermes is still writing. That is only observable across the two pipes at once, so the loop
 * stamps the four moments that pin it down and the instrumented turn reads them back
 * ([spokeBeforeRunCompleted], [firstAudioLatencyMillis]).
 *
 * Times are wall-clock millis from the loop's own clock, and only ever compared with each other.
 */
data class TurnLog(
    val runId: String? = null,

    /** What was dispatched to Hermes - the transcript at the endpoint, or typed text. */
    val prompt: String = "",

    /** The reply, accumulated from the deltas as they were spoken. */
    val reply: String = "",

    /** The wake word landed. Null for a turn that was typed rather than spoken (T-17). */
    val wakeAtMillis: Long? = null,

    /** POST /v1/runs returned a run id. */
    val dispatchedAtMillis: Long? = null,

    /** The first `message.delta` arrived - the earliest the console could possibly speak. */
    val firstDeltaAtMillis: Long? = null,

    /** The first PCM of the reply was written to the speaker. */
    val firstAudioAtMillis: Long? = null,

    /** The run's terminal event arrived (completed, failed or cancelled). */
    val runCompletedAtMillis: Long? = null,

    /**
     * The turn died on the wire rather than on a terminal event: Hermes was unreachable at
     * dispatch, or the run's event stream dropped mid-reply (T-50). The message it failed with,
     * carried on the log past the error voice so a fault is evidence and not just a sound. Null on
     * every turn that reached a real terminal - completed, a server-reported failure, or cancelled.
     */
    val error: String? = null,

    /** How much of the reply was actually played. */
    val audioBytes: Int = 0,

    /**
     * Did capture audio flow through the loop while this turn was alive?
     *
     * False for a typed turn with the mic path disengaged - the idle capture path REQ-24 asks the
     * turn log to show (T-17), so an agent-side failure reproduced from the debug screen is known
     * to have excluded STT. Any frame during a live turn flips it: the log may only claim an idle
     * path when the path really was idle.
     */
    val captureEngaged: Boolean = false,
) {

    /**
     * REQ-12's first assertion: audio reached the speaker before the run finished.
     *
     * A turn still in flight satisfies this the moment it makes a sound - the run has not completed,
     * so the console has already beaten it. A turn that never made a sound never did.
     */
    val spokeBeforeRunCompleted: Boolean
        get() {
            val audio = firstAudioAtMillis ?: return false
            val completed = runCompletedAtMillis ?: return true
            return audio < completed
        }

    /**
     * REQ-12's second: how long the first token took to become sound. Null until it has.
     *
     * Measured from the *first delta* rather than from dispatch on purpose - the model's thinking
     * time is Hermes' business, and folding it in would measure the agent instead of the pipe.
     */
    val firstAudioLatencyMillis: Long?
        get() {
            val delta = firstDeltaAtMillis ?: return null
            val audio = firstAudioAtMillis ?: return null
            return audio - delta
        }
}

/**
 * One finished exchange in the session's spoken record (T-44/T-10): the query, the reply, and the run
 * that produced them. The runId is kept so the reply's tool lines can be woven back into the reply at
 * the point each fired (T-10) even after the turn retires. Null runId is a restored back-log turn -
 * session history carries no run id, and no tools with it - which simply renders as prose.
 */
data class SpokenTurn(
    val runId: String?,
    val prompt: String,
    val reply: String,
)

/**
 * One barge-in, timed - and the evidence for REQ-04 (T-14).
 *
 * Interrupting the console is three cancellations, not one, and they go to three different places:
 * the speaker in the tablet's hand, the synthesiser at VoiceManager, and the agent loop at Hermes.
 * Miss any of them and the interruption is a lie - the tablet goes quiet but the run keeps thinking
 * and the sentence keeps being made, and both come back a second later to talk over the person who
 * just interrupted. So all three are stamped separately, and [cancelLatencyMillis] is the *slowest*
 * of them: the requirement's 500 ms is a claim about the last one to land, not the first.
 *
 * It lives beside [TurnLog] rather than in it because a barge-in belongs to two turns at once - the
 * one it kills and the one it starts - and the turn log is already the new turn's by the time the
 * stops have all been answered.
 */
data class BargeInLog(
    /** The frame on which sustained human speech was confirmed. Everything is measured from here. */
    val speechAtMillis: Long,

    /** The run this interrupted, and the TTS request that was speaking it. */
    val runId: String? = null,
    val requestId: String? = null,

    /** The local track was flushed and stopped: the tablet went quiet. */
    val playbackStoppedAtMillis: Long? = null,

    /** `tts_cancel` left the socket: VoiceManager stopped making the speech. */
    val ttsCancelledAtMillis: Long? = null,

    /** POST /v1/runs/{id}/stop came back: Hermes stopped writing the reply. */
    val runStoppedAtMillis: Long? = null,

    /**
     * PCM of the cancelled request that arrived after the cancel and was thrown away.
     *
     * Not a bug being tolerated - it is the race being won. A stopped synthesiser still has chunks
     * in flight, and a console that played them would speak the reply it was just told to abandon.
     * A non-zero count is proof the drop path did work.
     */
    val droppedAudioBytes: Int = 0,

    /**
     * Why the gate fired, kept so a barge-in nobody performed can be told from one somebody did.
     *
     * The console has stopped itself mid-sentence three times with nobody in the room, and every
     * one of those sightings was classified by hand afterwards from a server-side log and an
     * inference (docs/aec-spike.md). These three fields are what turn the fourth into evidence:
     *
     * - [levelDbfs] - the energy on the frame that fired. The gate's bar is -35; a person at the
     *   wall mount lands well above it, and echo residue that only just clears it is the leak's
     *   signature rather than a person's.
     * - [converged] and [exposureMillis] - whether the canceller had earned the right to be
     *   believed, and on how much of the console's own voice. A trip at 100 ms of exposure and the
     *   same trip at ten seconds are different findings.
     * - [playbackUnderruns] - how often the mixer had run dry on the track that was speaking. A
     *   starved MODE_STREAM track is restarted from underrun and each restart is a fresh onset for
     *   the canceller to re-learn, which is the standing suspect for the phantom. Null means there
     *   was no track left to ask, never that the count was zero.
     */
    val levelDbfs: Double = SILENCE_DBFS,
    val converged: Boolean = false,
    val exposureMillis: Long = 0,
    val playbackUnderruns: Int? = null,
) {

    /** How long the slowest of the three cancellations took. REQ-04's budget is 500 ms. */
    val cancelLatencyMillis: Long?
        get() {
            val stamps = listOfNotNull(playbackStoppedAtMillis, ttsCancelledAtMillis, runStoppedAtMillis)
            if (stamps.size < 3) return null
            return stamps.max() - speechAtMillis
        }
}

/**
 * One reply's playback shape, measured on arriving PCM (T-12 self-barge, A1 instrumentation).
 *
 * The self-barge diagnosis of 2026-08-18 turns on a gap the loop never measured: `audio_start`
 * arrives when VoiceManager *begins* the reply, the first chunk arrives after the model's lag, and
 * the console counts the whole of that silence as playback exposure because it is in SPEAKING all
 * the way through it. A lag past the guard's re-arm window latches the canceller trusted before the
 * tablet has made a sound, and the onset leak that follows lands unguarded
 * (docs/handoff-2026-08-18-aec-dropout.md).
 *
 * These are the two numbers that decide it. [onsetLagMillis] with [onsetConverged] and
 * [onsetExposureMillis] say whether the guard had already latched by the time sound started; the
 * gap fields say how the rest of the reply was delivered, which is also the cadence a faithful
 * replay would need.
 *
 * Measured on chunk *arrival*, not on audibility. The track's own buffer means those are different
 * intervals, and the difference is itself a suspect - the re-arm is keyed on arrival gaps while
 * what de-adapts the canceller is audible silence.
 */
data class ReplyCadence(
    /** The TTS request this reply was, so a cadence line can be read against the barge-in log. */
    val requestId: String? = null,

    /** First playable chunk minus `audio_start`. The lag the operator hears before the reply. */
    val onsetLagMillis: Long = 0,

    /** The guard's verdict at that first chunk - true means it latched on silence. */
    val onsetConverged: Boolean = false,

    /** Playback exposure the guard had counted by that first chunk, in ms. */
    val onsetExposureMillis: Long = 0,

    /** Playable chunks in the reply, and their total PCM. */
    val chunks: Int = 0,
    val audioBytes: Long = 0,

    /** Arrival gaps between consecutive chunks, in ms. Zero across the board on a one-chunk reply. */
    val minGapMillis: Long = 0,
    val medianGapMillis: Long = 0,
    val maxGapMillis: Long = 0,

    /** Gaps that reached the loop's re-arm threshold - each one re-armed the guard mid-reply. */
    val gapsOverRearmThreshold: Int = 0,
)
