package com.hermesconsole.audio

/**
 * Holds barge-in back while the echo canceller is cold (REQ-05, REQ-04).
 *
 * The hardware gate (T-04) passed with one condition. Once the canceller has converged, the
 * tablet's own voice never crosses the barge-in gate - mean -112 dBFS, zero detections across
 * 3 x 60 s of playback. But a *cold* canceller leaks: opening a fresh capture session and
 * immediately playing TTS produces detections at 1.3 s, 1.7 s and 5.4 s into playback, peaking at
 * -15 dBFS, which is as loud as a person at the wall mount (docs/aec-spike.md). Unguarded, the
 * console interrupts itself on its first sentence after every boot.
 *
 * The guard suppresses the barge-in *action*, never the detection - the gate keeps measuring, and
 * the wake-word path is untouched. And it is scoped to the convergence window alone: a person who
 * speaks over the tail of the same long answer is still heard.
 *
 * Convergence is counted in **playback exposure**, not wall-clock: the canceller has nothing to
 * model until the console speaks, so an open microphone in a quiet room stays exactly as cold as it
 * started, however long it listens. Exposure belongs to the record session that learned it -
 * [onCaptureStarted] hands us a session that has learned nothing.
 *
 * But convergence is not learned once and kept. The playback track - the canceller's reference
 * signal - is torn down and rebuilt before every reply, so a canceller that was warm on the last
 * reply meets a fresh reference on the next and leaks its opening at reply onset, with the guard
 * still latched converged from an hour ago (docs/aec-spike.md, "The fifth and sixth sightings").
 * [onPlaybackStarted] re-arms the guard at each reply onset. The first, cold-boot convergence pays
 * the full [windowMs]; every warm re-arm after it pays only the short [reArmWindowMs], because the
 * canceller is re-adapting a reference it already half-knows, not learning from nothing.
 *
 * Everything the guard protects against is echo: the speaker's own sound coming back through the
 * microphone. With the speaker silent there is none, and [BargeInDetector.accept] may skip the
 * guard - which keeps counting exposure all the same, so a speaker that becomes audible again
 * meets a guard that knows how warm the canceller is.
 */
class ConvergenceGuard(
    private val config: AudioConfig = AudioConfig(),
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val reArmWindowMs: Long = DEFAULT_REARM_WINDOW_MS,
) {
    private var exposureMs = 0.0
    private var converged = false

    /** Has the canceller ever converged on this capture session? Cold boot pays [windowMs], warm re-arms pay [reArmWindowMs]. */
    private var everConverged = false

    /** True once the canceller has heard enough of the console's own voice to be trusted. */
    val isConverged: Boolean get() = converged

    /** Playback the canceller has heard on this session, in ms. Reported, so the guard is legible. */
    val exposureMillis: Long get() = exposureMs.toLong()

    /** A new record session carries none of the old one's convergence. */
    @Synchronized
    fun onCaptureStarted() {
        exposureMs = 0.0
        converged = false
        everConverged = false
    }

    /**
     * A reply's playback has started, meeting the canceller with a freshly rebuilt reference.
     *
     * Once the guard has ever converged, this re-arms it: the console must speak [reArmWindowMs] of
     * this reply before barge-in on it is trusted again, which covers the onset leak (measured at
     * 1.3-1.7 s once warm) while restoring interruptibility for the rest of the reply. Before the
     * first convergence it is a no-op - the cold-boot [windowMs] is already accumulating and must
     * not be reset to a shorter one.
     *
     * Called at a reply's `audio_start`, and again whenever playback resumes after a gap of real
     * silence *within* one reply - a tool call runs silent mid-run, the canceller de-adapts, and the
     * speech after it leaks with no fresh `audio_start` to re-arm on (docs/aec-spike.md, "The eighth
     * sighting"). Idempotent, so calling it on both signals is safe.
     */
    @Synchronized
    fun onPlaybackStarted() {
        if (everConverged) {
            converged = false
            exposureMs = 0.0
        }
    }

    /**
     * Feed one capture frame, with whether the console was playing audio while it was captured.
     * Returns true when barge-in may be acted on.
     */
    @Synchronized
    fun accept(frame: ByteArray, playing: Boolean): Boolean {
        if (converged) return true
        if (playing) {
            exposureMs += frame.size * 1000.0 / config.bytesPerSecond
            val target = if (everConverged) reArmWindowMs else windowMs
            if (exposureMs >= target) {
                converged = true
                everConverged = true
            }
        }
        return converged
    }

    companion object {
        /**
         * 10 s of playback exposure: twice the span of the leak as it was measured (its last
         * detection at 5.4 s), and no more.
         *
         * The window is a trade, and both ends of it cost something. Too tight and the console
         * talks over itself; too generous and the first answer after every capture restart cannot
         * be interrupted, which is REQ-04 turned off for exactly the moments a person is most
         * likely to want it. 10 s covers the leak twice over at half the price.
         *
         * It is insurance, and it is honest about being insurance: the leak did not reproduce on
         * the re-measurement (docs/aec-spike.md, "The leak, six hours later"). The guard is kept
         * because the failure it prevents - the console interrupting itself - was observed on this
         * hardware, and nothing we changed explains its disappearance.
         */
        const val DEFAULT_WINDOW_MS: Long = 10_000L

        /**
         * 2 s of playback exposure to re-trust the canceller at each reply onset once it has been
         * warm - not the full [DEFAULT_WINDOW_MS], because a warm canceller is re-adapting a
         * reference it already half-knows rather than learning from a cold start.
         *
         * Sized from the warm onset leaks measured at 1.3 s and 1.7 s (the 5.4 s outlier belonged to
         * a truly cold canceller, which the boot window covers). This is REQ-04 switched off for the
         * first two speaking-seconds of every reply after a playback gap - the price the leak's
         * per-playback recurrence bought (docs/aec-spike.md, "The fifth and sixth sightings"). Every
         * second here is paid on every reply, forever; keep it as tight as the measured onset allows.
         *
         * Not a knob for tail-of-reply self-barges: `exposure` freezes at this value once the guard
         * converges, so a logged `exposure=2000ms` only means "past the re-arm window", never *when*
         * the trip fired. A trip deep in a draining reply (high underruns, droppedAudioBytes=0) is a
         * different failure - or a real person - and widening this window cannot address it without
         * eating real interruptions (docs/aec-spike.md, "The seventh sighting").
         */
        const val DEFAULT_REARM_WINDOW_MS: Long = 2_000L
    }
}

/**
 * What the barge-in detector saw on the frame it last judged - the description of an interruption,
 * kept so a self-interruption can be classified rather than shrugged at.
 */
data class BargeInTrigger(
    /** RMS of that frame, in dBFS. The gate's bar is -35. */
    val levelDbfs: Double,
    /** Was the canceller's verdict trustworthy when the gate fired? */
    val converged: Boolean,
    /** Playback the canceller had heard on this capture session by then. */
    val exposureMillis: Long,
)

/**
 * The barge-in decision: sustained human-level energy on the capture stream that the console is
 * allowed to act on.
 *
 * Two questions, and both must answer yes: did the microphone hear speech ([SpeechGate]), and is
 * what it heard trustworthy ([ConvergenceGuard])? The gate runs on every frame either way - the
 * guard decides what may be done about it, never what is measured. The guard's question is only
 * worth asking when the speaker can make an echo; see [accept].
 */
class BargeInDetector(
    private val gate: SpeechGate = SpeechGate(),
    private val guard: ConvergenceGuard = ConvergenceGuard(),
) {
    val isConverged: Boolean get() = guard.isConverged

    /**
     * What the detector saw on the frame it last judged.
     *
     * Read immediately after [accept] returns true, this is the description of a barge-in that the
     * console can put on the record. The two halves are separate facts and the classification needs
     * both: the same -12 dBFS trip is a person on a canceller with ten seconds of exposure behind
     * it, and the leak on one with a hundred milliseconds (docs/aec-spike.md).
     */
    fun trigger(): BargeInTrigger = BargeInTrigger(
        levelDbfs = gate.lastLevelDbfs,
        converged = guard.isConverged,
        exposureMillis = guard.exposureMillis,
    )

    fun onCaptureStarted() {
        gate.reset()
        guard.onCaptureStarted()
    }

    /** A reply's playback has started: re-arm the guard against the freshly rebuilt reference. */
    fun onPlaybackStarted() {
        guard.onPlaybackStarted()
    }

    /**
     * Feed one capture frame, with whether the console was playing audio while it was captured.
     * Returns true on the frame where a barge-in should be acted on.
     *
     * [speakerSilent] says whether the tablet's speaker is making no sound. The guard holds a cold
     * canceller's verdict back because the speaker's voice leaks through it; with the speaker
     * silent there is nothing to leak, so speech is acted on from the first word. The guard is
     * still fed every frame, so exposure and convergence are counted - and reported in the
     * [trigger] - exactly as before. The supplier is asked only on a frame with speech that the
     * guard would hold back: it reads the device, and this runs on every 20 ms frame.
     */
    fun accept(frame: ByteArray, playing: Boolean, speakerSilent: () -> Boolean = { false }): Boolean {
        val armed = guard.accept(frame, playing)
        val speech = gate.accept(frame)
        return speech && (armed || speakerSilent())
    }
}
