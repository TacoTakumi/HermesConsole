package com.hermesconsole.app

import android.util.Log
import com.hermesconsole.audio.AudioCapture
import com.hermesconsole.audio.BargeInDetector
import com.hermesconsole.audio.ReferenceResampler
import com.hermesconsole.audio.ReferencePacer
import com.hermesconsole.audio.SerializedSoftwareAec
import com.hermesconsole.audio.SoftwareAec
import com.hermesconsole.audio.SpeechGate
import com.hermesconsole.audio.TtsPlayer
import com.hermesconsole.audio.rmsDbfs
import com.hermesconsole.hermesclient.HermesClient
import com.hermesconsole.hermesclient.HermesEvent
import com.hermesconsole.hermesclient.dispatchInSession
import com.hermesconsole.lcars.LcarsCue
import com.hermesconsole.voiceclient.Transcript
import com.hermesconsole.voiceclient.TtsEvent
import com.hermesconsole.voiceclient.VoiceManagerClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.hermesconsole.wakeword.WakeWordSource
import java.util.UUID

/**
 * The voice loop: wake word -> STT -> Hermes run -> streaming TTS, and the barge-in that cuts all
 * three off mid-sentence (T-13, T-14, REQ-12, REQ-04).
 *
 * This is the console's one piece of orchestration, and it exists because nothing else can do it:
 * VoiceManager and Hermes know nothing of each other, so the join between them - a transcript
 * becoming a run, a run's tokens becoming speech - is the tablet's alone.
 *
 * The whole point is that the two pipes run *at the same time*. Hermes' `message.delta` tokens go
 * straight out to VoiceManager's `tts_stream` as they arrive, and VoiceManager's PCM comes back and
 * is played while the model is still writing - so the console starts speaking well before
 * `run.completed` (REQ-12). Waiting for the run to finish and then speaking the whole reply would be
 * simpler, and would make the console feel dead for the length of every answer.
 *
 * And because both pipes are running when the human interrupts, both have to be stopped - along with
 * the speaker. [onBargeIn] is the one place that can do it, for the same reason: it is the only code
 * in the system that knows the run and the TTS request are two halves of the same sentence.
 *
 * The loop talks to ports rather than to the clients themselves ([VoiceLink], [Agent]), so the
 * ordering guarantee above can be tested without a server, a socket or a tablet.
 */
class VoiceLoop(
    private val capture: AudioCapture,
    private val wake: WakeWordSource,
    private val voice: VoiceLink,
    private val agent: Agent,
    private val player: TtsPlayer,
    /**
     * Hermes' conversation scope, read fresh at every dispatch: the same id across turns is what
     * gives the console a memory, and reading it through a provider rather than fixing it at
     * construction is what lets the ready state switch sessions without rebuilding the loop
     * (T-22, REQ-23). Suspend, because the visit's first dispatch may have to make the session
     * before it can name one (REQ-06); total, because a turn without a session does not exist -
     * a provider that cannot yield one throws, and the turn fails rather than dispatch unscoped
     * (REQ-01, REQ-03). No default: every assembly says where its turns land.
     */
    private val sessionId: suspend () -> String,
    /**
     * The follow-up window (REQ-40): how long a finished spoken reply holds the floor open for
     * the next utterance before the console drops back behind the wake word. Read fresh at every
     * reply's end, so the settings knob applies to the very next exchange with nothing rebuilt.
     * Zero disables the window - the turn closes at the reply's end - which is the harness
     * default; the shipping assembly wires the settings store's value here (default 10 s).
     */
    private val followUpWindowMillis: () -> Long = { 0 },
    /**
     * The quiet beat between a reply's last audio and the follow-up window's cue (T-23, REQ-21):
     * a beep in the same instant playback stops reads, in the room, as the console demanding the
     * next question before its own reply has landed. Only the announcement waits - the window's
     * STT turn opens at the reply's end regardless, because a floor that waited out the beat
     * would stamp a fast reply with the finished turn's id and fence it out (D-18), which is the
     * lost-reply shape T-24 exists to close. Injectable for the tests; a constant otherwise -
     * this is sound design, not a settings knob.
     */
    private val followUpCueGraceMillis: Long = FOLLOW_UP_CUE_GRACE_MILLIS,
    /**
     * What counts as being interrupted: sustained human-level energy on the capture stream that the
     * echo canceller is warm enough to be believed about (REQ-04, REQ-05). The console's own voice
     * must never clear this bar - that is T-15, and it is the whole reason the detector is a
     * measured thing rather than an `if` in here.
     */
    private val bargeIn: BargeInDetector = BargeInDetector(),
    /**
     * Whether a detected barge-in is acted on (REQ-04). True is the shipped design after REQ-01/REQ-02
     * passed on-device with the software echo canceller (T-11): speech over the console stops it, on the
     * cancelled stream. False is the half-duplex stopgap - the ZEF->ZGC OTA broke this device's hardware
     * AEC (docs/aec-spike.md, "The tenth"), so the console heard its own TTS and interrupted itself;
     * false trades away voice-interrupt for a console that never cuts itself off. The toggle is the
     * stored barge-in setting, read here at each detected barge-in - like [followUpWindowMillis], a
     * supplier rather than a value - so a flip on the settings screen applies to the very next trip
     * with nothing rebuilt. The detector measures either way.
     */
    private val bargeInEnabled: () -> Boolean = { true },
    /**
     * Whether the tablet's speaker is silent, so the console's voice cannot reach the microphone.
     * The detector's convergence guard holds a barge-in back while the echo canceller is cold,
     * because a cold canceller leaks the speaker's sound back as speech - and with the speaker
     * silent there is no sound to leak, so the guard would only make the person repeat
     * themselves after every capture start. A supplier, like [bargeInEnabled]: the detector asks
     * it only when it hears speech the guard would hold back, so the 50 Hz frame loop pays for
     * no device read. False - the default - keeps the guard as it always was.
     */
    private val speakerSilent: () -> Boolean = { false },
    /**
     * The software echo canceller's factory, or null to run raw capture (the stopgap and the
     * test harness default). One instance is created per capture session - here in [start], seeded
     * with the measured output-path latency, and closed in [close] - and every call funnels
     * through [SerializedSoftwareAec], so the reference path and this capture path can never hit
     * the (non-thread-safe) APM concurrently (REQ-06). The factory may return null: a device that
     * cannot build a canceller captures raw, which is the half-duplex fallback (REQ-13).
     */
    private val aecFactory: (() -> SoftwareAec?)? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val now: () -> Long = System::currentTimeMillis,
    private val newRequestId: () -> String = { UUID.randomUUID().toString() },
    /**
     * The shell's window onto the run's event stream: the artifact router (T-24), and whatever
     * else the shell hangs off a turn's events. Called with every event, after the loop has done
     * its own work with it - and never trusted: a tap that throws is logged and dropped, because
     * no pane is worth interrupting the spoken reply (REQ-15).
     */
    private val eventTap: (HermesEvent) -> Unit = {},
    /**
     * Where a `MEDIA:<target>` line taken out of the reply goes ([MediaLineFilter]): the line is
     * never spoken or shown as text, and its target is handed here with the run's id. Never
     * trusted, like [eventTap].
     */
    private val mediaTap: (runId: String, target: String) -> Unit = { _, _ -> },
    /**
     * The loop's earcons (REQ-39): fired at the moments a person across the room needs to hear -
     * listening opening and closing, a barge-in accepted, a run dying. Dispatch is deliberately
     * silent, and the wake has no cue of its own: its acknowledgement folds into the open
     * sequence at the STT turn's ack. The kit owns the sounds and the playback path; the loop
     * only names the moments. Silent by default, and never trusted - see [cue].
     */
    private val cues: (LcarsCue) -> Unit = {},
) : AutoCloseable {

    private val _state = MutableStateFlow(ConsoleState.READY)

    /** What the console is doing, for the shell to render (T-13). */
    val state: StateFlow<ConsoleState> = _state.asStateFlow()

    private val _turn = MutableStateFlow(TurnLog())

    /** The turn in flight, or the last one to finish. The evidence for REQ-12. */
    val turn: StateFlow<TurnLog> = _turn.asStateFlow()

    private val _spoken = MutableStateFlow<List<SpokenTurn>>(emptyList())

    /**
     * The live session's finished exchanges, in spoken order (T-44, REQ-23): each turn joins this
     * record the moment a new one takes [turn] over - a wake, a barge-in, a typed prompt - so the
     * wall keeps the conversation instead of wiping back to STANDING BY mid-session. Each carries its
     * runId (T-10) so the wall can weave the turn's tool lines back into its reply after it retires.
     * Cleared with the live turn on session entry ([clearTurn]): the freshly fetched back-log carries
     * these same lines, and an accumulation that survived entry would show the session twice.
     */
    val spoken: StateFlow<List<SpokenTurn>> = _spoken.asStateFlow()

    private val _lastBargeIn = MutableStateFlow<BargeInLog?>(null)

    /** The last time somebody talked over the console, and how fast it shut up. Evidence for REQ-04. */
    val lastBargeIn: StateFlow<BargeInLog?> = _lastBargeIn.asStateFlow()

    /**
     * Is capture audio going to VoiceManager right now?
     *
     * False until the wake word lands, and that is a privacy property as much as a protocol one: an
     * idle console sends nothing anywhere. Detection runs entirely on the tablet (REQ-01), so
     * nothing said in front of a sleeping console is transcribed by anyone.
     */
    @Volatile
    private var streamingToStt = false

    /**
     * The TTS request the console is currently having synthesised, or null between turns.
     *
     * It is the identity of the reply *on the wire*, and barge-in turns on it. Clearing it is what
     * makes the cancelled turn's audio unplayable and its remaining tokens unsendable, and the two
     * places that touch it ([speakOut], [onBargeIn]) hold the loop's monitor while they do - a
     * `tts_stream` frame that lost that race would arrive at VoiceManager *after* the `tts_cancel`
     * and re-open the very request we just killed, and the console would speak the reply it had
     * been told to abandon.
     */
    @Volatile
    private var ttsRequestId: String? = null

    /** Is the audio arriving from VoiceManager ours to play? Binary frames carry no request id. */
    @Volatile
    private var playingTts = false

    /**
     * When the last playable PCM chunk arrived, for the intra-reply gap re-arm (see [onTtsEvent]).
     * 0 means no chunk yet on this reply - reset at each [onTtsStart] so a new run cannot inherit
     * the previous one's clock and read a false gap on its first chunk.
     */
    private var lastAudioChunkAtMillis: Long = 0L

    /**
     * When this reply's `audio_start` arrived, for the onset lag in [ReplyCadence]. 0 between
     * replies. Written and read on the TTS collector thread only, like the fields below it.
     */
    private var audioStartAtMillis: Long = 0L

    /** This reply's playable chunks, their PCM, and the arrival gaps between them. See [ReplyCadence]. */
    private var replyChunks: Int = 0
    private var replyAudioBytes: Long = 0L
    private val replyGapsMillis = ArrayList<Long>()

    /** What the guard had done by this reply's first chunk. See [ReplyCadence]. */
    private var onsetLagMillis: Long = 0L
    private var onsetConverged: Boolean = false
    private var onsetExposureMillis: Long = 0L

    /**
     * The last reply's playback shape (T-12 self-barge, A1). Instrumentation, not behaviour: it is
     * logged at `audio_end` and read by the tests that pin what the numbers mean.
     */
    internal var lastReplyCadence: ReplyCadence? = null
        private set

    /**
     * The window's own ear (T-25, REQ-23): the same measured -35 dBFS / 100 ms gate barge-in
     * trusts, on its own instance - the detector's run state belongs to the playback question,
     * and this one is asked of an open floor. Every confirmed run of speech bumps [speechHeard],
     * and that bump is what re-arms a listening window while the shipped STT - which emits
     * nothing until the speaker pauses - has no transcript movement to offer.
     */
    private val windowSpeech = SpeechGate()

    /** Monotonic count of confirmed speech runs heard while capture streams. See [windowSpeech]. */
    private val speechHeard = MutableStateFlow(0L)

    private var captureJob: Job? = null
    private var turnJob: Job? = null

    /** The live software AEC for this capture session, or null when capture is raw (T-08). */
    private var aec: SoftwareAec? = null

    /**
     * Whether the measured output latency has been handed to the live [aec] (T-12 self-barge
     * follow-up, B-3). False until the first reply whose playback has made latency measurable -
     * the session-start seed is always null at boot, so the real seed is applied here instead.
     */
    private var delaySeedApplied = false

    /** The delayed latency sampler for the current reply; a reply change cancels the prior one. */
    private var delaySeedJob: Job? = null

    /**
     * Smooths the TTS reference tap into fixed-cadence 20 ms frames for the AEC (T-12 self-barge
     * fix). The tap is bursty; fed straight in, inter-chunk gaps made AEC3 leak the still-playing
     * echo as isolated bursts. The pacer hands the capture loop one whole frame per capture frame,
     * silently padding any gap, so the far-end stream never discontinues.
     */
    private val referencePacer = ReferencePacer()

    /**
     * The reference resampler for the reply currently playing (T-09, REQ-03/REQ-04): converts the
     * tapped PCM - at the engine's rate, post-downsample - to the APM's 16 kHz before
     * [SoftwareAec.processReference]. One instance per reply, built in [onTtsStart] at the rate the
     * tap actually emits, flushed and dropped at the reply's end. Written and read on the TTS
     * collector thread only: [onTtsStart] builds it and the reference consumer (which fires inside
     * [TtsPlayer.onChunk], from the same collector) reads it, so no cross-thread visibility is
     * needed.
     */
    private var referenceResampler: ReferenceResampler? = null

    /**
     * Per-frame AEC processing cost for this capture session (T-10, REQ-11). Accumulated in the
     * capture job around every [SoftwareAec.processCapture] call; read at close for the summary
     * log. Zero when the session is raw (no AEC). Test-visible: the wiring test drives a frame
     * and asserts the counters moved.
     */
    internal var aecFrameTimeNs: Long = 0L
        private set

    /** Frames processed by the AEC this session. See [aecFrameTimeNs]. */
    internal var aecFrameCount: Int = 0
        private set

    /** Slowest single [SoftwareAec.processCapture] this session, ns. See [aecFrameTimeNs]. */
    internal var aecMaxNs: Long = 0L
        private set

    /**
     * Reference bytes fed to the AEC this capture session (T-12 self-barge diagnostic). Read on the
     * capture thread by the trace log, incremented on the TTS thread by the reference consumer, so
     * @Volatile. Proves, on a real turn, that the reply's PCM reached the APM's far-end - the first
     * question the self-barge failure asks (docs/projects/software-aec/t12-self-barge.md).
     */
    @Volatile
    var refBytesFed: Long = 0L
        private set

    /** Start listening. Capture runs from here until [close], the wake word gating what leaves. */
    fun start() {
        if (captureJob != null) return

        // The software echo canceller for this capture session (T-08, REQ-01/REQ-08): one
        // instance, created here - before the first capture frame - and seeded with the measured
        // output-path latency when one exists (REQ-05). At boot nothing has played yet, so there
        // is no measurement and the AEC3 delay controller tracks from zero; the seed's job is the
        // capture session that starts after playback has run. SerializedSoftwareAec wraps the
        // instance so reference (T-09) and capture never hit the APM at once (REQ-06).
        aec = aecFactory?.invoke()?.let { SerializedSoftwareAec(it) }?.also { aec ->
            player.latencyMillis?.let { aec.setDelaySeedMs(it) }
            // T-10 (REQ-13): the engagement line is a measurement, not a feature: the AEC is
            // created and warmed whether or not barge-in is enabled, and the log has to say so
            // on the live (enabled) path too.
            Log.i(TAG, "AEC engaged: seed ${player.latencyMillis ?: "unmeasured"} ms")
        }

        // T-09 (REQ-03, REQ-04): every played chunk feeds the AEC's far-end reference. The tap fires
        // on the TTS thread with the exact bytes written to the sink; the reference resampler - the
        // only rate conversion in the reference path - brings it to 16 kHz, and the [referencePacer]
        // buffers it for the capture loop to drain on a fixed cadence, so the APM sees a contiguous
        // reference (T-12 self-barge fix). With no AEC the consumer is never registered: raw capture.
        if (aec != null) {
            player.referenceConsumer = { pcm ->
                referenceResampler?.resample(pcm)?.let { resampled ->
                    refBytesFed += resampled.size
                    referencePacer.append(resampled)
                }
            }
        }

        // A capture session that has never heard the console speak has an echo canceller that has
        // learned nothing, and a cold canceller leaks the console's own voice into the microphone
        // loudly enough to interrupt itself (T-36). The guard is armed by playback, not by time.
        bargeIn.onCaptureStarted()

        // One collector, three jobs. The wake detector consumes the capture flow; on the way through,
        // each frame is forwarded to VoiceManager and offered to the barge-in detector - so STT,
        // wake-word detection and barge-in all see exactly the same audio, in the same order, and
        // there is no second read of the recorder to drift out of sync.
        captureJob = scope.launch {
            var traceFrames = 0
            val frames = capture.frames().onEach { frame ->
                // Before the barge-in check, not after: the frames that carry the interruption are
                // the ones VoiceManager has to transcribe to make it the next turn.
                if (streamingToStt) voice.sendCapture(frame)

                // On the record, once per turn: a turn with capture audio flowing through it can
                // never claim the mic path was disengaged (REQ-24). Gated on the state so a frame
                // arriving between turns cannot mark the log of a turn already closed.
                if (_state.value != ConsoleState.READY && !_turn.value.captureEngaged) {
                    _turn.update { it.copy(captureEngaged = true) }
                }

                val speaking = _state.value == ConsoleState.SPEAKING
                // REQ-08: only the barge-in detector sees the cancelled stream. STT (above) and the
                // wake detector (below) keep the raw capture; the cancelled frame is fed exactly
                // where the detector's verdict is formed (REQ-01). With no canceller the frame
                // passes through, which is the stopgap's raw path and the test harness default.
                // T-10 (REQ-11): every call is timed - the per-frame budget is 20 ms and the
                // instrumented mean/max are what the on-device validation reads. The AEC is read
                // once into a local so the timing covers exactly one processCapture call.
                val cancelled = aec?.let { active ->
                    val t0 = System.nanoTime()
                    active.processCapture(frame).also {
                        val elapsed = System.nanoTime() - t0
                        aecFrameTimeNs += elapsed
                        aecFrameCount++
                        if (elapsed > aecMaxNs) aecMaxNs = elapsed
                    }
                } ?: frame
                // T-12 self-barge fix: hand the AEC one whole 20 ms reference frame from the pacer
                // per capture frame (both 16 kHz, so real-time with capture). While the console is
                // speaking the pacer silences any gap, so processReference never sees the
                // discontinuities that leaked as bursts; when not speaking it drains only leftover
                // buffered tail, never a stream of idle silence.
                val speakingNow = _state.value == ConsoleState.SPEAKING
                val aecNow = aec
                if (aecNow != null && (speakingNow || referencePacer.bufferedBytes > 0)) {
                    aecNow.processReference(referencePacer.drainFrame(ReferencePacer.FRAME_BYTES))
                }
                // Offered on every frame, whatever the state: playback exposure is what warms the
                // canceller, and the detector cannot count what it is not shown. With barge-in enabled
                // the verdict is acted on; the setting is read at the trip, so a flip gates the next one.
                val wouldBargeIn = bargeIn.accept(cancelled, playing = speaking, speakerSilent) && speaking
                if (wouldBargeIn) {
                    if (bargeInEnabled()) onBargeIn() else logSuppressedBargeIn()
                }

                // T-12 self-barge trace (diagnostic, behind the stopgap): every ~1 s of capture log
                // the cancelled-stream level, whether the console was speaking, and how much reply
                // reference the AEC had been fed. Reads the leak's shape on a real turn - flat at
                // raw echo level (no cancellation / no reference), or a raw-level transient that
                // decays as AEC3 locks (delay tracking). See t12-self-barge.md.
                if (++traceFrames % 50 == 0) {
                    Log.i(
                        TAG,
                        "AEC trace: cancelled=" + String.format("%.1f", rmsDbfs(cancelled)) +
                            " dBFS speaking=$speaking refFed=${refBytesFed}B frame=$traceFrames",
                    )
                }

                // The window's re-arm signal (T-25, REQ-23). Counted whenever capture streams -
                // which spans the whole spoken turn, echo and all - and that is safe because each
                // window snapshots the count as it starts waiting: only speech during the wait
                // re-arms it. Reset across the closed floor so a run cannot straddle the gate.
                if (streamingToStt) {
                    if (windowSpeech.accept(frame)) speechHeard.update { it + 1 }
                } else {
                    windowSpeech.reset()
                }
            }
            wake.detections(frames).collect { onWake(it.atMillis) }
        }

        // The TTS stream belongs to the link, not to the turn: it is one ordered channel for the life
        // of the socket. Collected once, here - a per-turn collector would be a second consumer of
        // the same channel, and cancelling it mid-turn could swallow a chunk of speech.
        scope.launch {
            voice.tts.collect(::onTtsEvent)
        }
    }

    /**
     * Speak to Hermes without the microphone - the debug screen's entry point (T-17), and how an
     * instrumented turn measures the pipe without a human in the room.
     */
    fun say(text: String) {
        if (_state.value != ConsoleState.READY) return
        retireTurn()
        _turn.value = TurnLog(prompt = text)
        startTurn { runTurn(text) }
    }

    /**
     * Take the whole conversation off the wall (T-43): entering a session replaces the pane's
     * content with that session's stored history, and every line still on display - the finished
     * turn, and the exchanges accumulated behind it (T-44) - would render below it as a duplicate
     * or an intruder. Only between turns: a turn in flight owns the wall.
     */
    fun clearTurn() {
        if (_state.value != ConsoleState.READY) return
        _turn.value = TurnLog()
        _spoken.value = emptyList()
    }

    /**
     * The visit is over (REQ-19): stop whatever the loop is doing - an open listening window, a
     * run mid-reply - and return to wake-gated READY, with nothing further dispatched into the
     * session being left. The composition root calls this from endVisit (T-20); the loop itself
     * never learns a visit exists, so departure arrives as this one imperative.
     *
     * The teardown is the barge-in family's, minus the re-arm: player, synthesis, run, and the
     * turn coroutine - whose cancellation is what makes a transcript still arriving dispatch
     * nothing, because the collector waiting on it is gone. Only a run still in flight is stopped:
     * a follow-up window's turn log names the run whose reply just finished, and reaching back to
     * stop a completed run would cancel nothing at best and somebody else's run id at worst.
     *
     * Deliberately absent: any cue - the departure has its own voice at the root, and a close beep
     * beside it would sound one ending twice - and any touch of the wall, whose clearing is the
     * root's own move, ordered after this one.
     *
     * Synchronized as the whole cancel family is: clearing [ttsRequestId] under the lock is what
     * keeps a delta already leaving [speakOut] from re-opening the request this just killed.
     */
    @Synchronized
    fun abort() {
        if (_state.value == ConsoleState.READY) return

        val turn = _turn.value
        val requestId = ttsRequestId
        val runId = turn.runId.takeIf { turn.runCompletedAtMillis == null }

        ttsRequestId = null
        playingTts = false
        player.stopImmediately()

        turnJob?.cancel()

        requestId?.let { voice.cancelTts(it) }

        if (runId != null) {
            scope.launch {
                runCatching { agent.stop(runId) }
                    .onFailure { Log.w(TAG, "aborted run $runId did not stop: ${it.message}") }
            }
        }

        streamingToStt = false
        wake.reset()
        _state.value = ConsoleState.READY
        Log.i(TAG, "aborted (run $runId, tts $requestId) -> READY")
    }

    /**
     * A new turn is taking the wall (T-44): the exchange still on display joins the session's
     * spoken record instead of vanishing. Both entry points retire - the wake word and the typed
     * prompt - and so does a barge-in, whose cut-off exchange is history, not debris. What never
     * retires is a supersede: a revision replaces the live exchange, it does not finish one.
     */
    private fun retireTurn() {
        val finished = _turn.value
        // Retire only a turn that said something; carry its runId so its tool lines stay woven into
        // the reply after it becomes history (T-10).
        if (finished.prompt.isBlank() && finished.reply.isBlank()) return
        _spoken.update {
            it + SpokenTurn(runId = finished.runId, prompt = finished.prompt, reply = finished.reply)
        }
    }

    private fun onWake(atMillis: Long) {
        // A wake word *during* a turn is not how you interrupt this console - speaking is (REQ-04),
        // and by the time the phrase is recognised the barge-in has already fired on its energy.
        if (_state.value != ConsoleState.READY) return

        // Mandatory: without it the detector keeps the hypothesis and re-fires on the frames that
        // follow - the command itself would wake the console again, mid-turn.
        wake.reset()

        retireTurn()
        _turn.value = TurnLog(wakeAtMillis = atMillis)
        streamingToStt = true
        _state.value = ConsoleState.LISTENING
        // No cue of its own (REQ-39): the wake's acknowledgement is the open sequence, which
        // waits for the STT turn's ack below - one figure, fired when the floor really is open.
        Log.i(TAG, "wake -> LISTENING")

        startTurn { dispatchWhatIsSaidNext(WAKE_WINDOW_MILLIS) }
    }

    /**
     * Wait for the speaker to finish a thought, run it - and take the run back if VoiceManager
     * takes the thought back (REQ-08). The tail of every entry point into a spoken turn: the wake
     * word, being interrupted, and a finished reply's follow-up window.
     *
     * The wait is bounded by [windowMillis], and the bound is on *silence*, not on the utterance
     * (T-24, REQ-22): every movement of this turn's transcript restarts the clock, so someone
     * mid-sentence is never cut off by it, and what it closes is only a floor nobody is taking -
     * the close beep, READY, and capture no longer streaming off-device. That last part is REQ-40's
     * privacy half, and it survives intact: an open microphone still cannot outlive the bound,
     * measured now from the last sign of anyone speaking.
     *
     * [cueGraceMillis] holds the open cue back without holding the floor (T-23, REQ-21): the
     * follow-up entry hands the reply's end a quiet beat before the announcement, while the wake
     * and barge-in entries keep their immediate go-ahead - those people are waiting for it.
     */
    private suspend fun dispatchWhatIsSaidNext(windowMillis: Long, cueGraceMillis: Long = 0) {
        // The opening keeps a hard bound of its own (T-51, REQ-40): a wake against a downed
        // VoiceManager - or a socket that drops between the turn_start frame and its ack - must
        // not latch LISTENING with capture streaming off-device to a server that never opened a
        // turn. An un-acked turn_start produces no transcript to move, so no re-arm can hold it:
        // it closes at the bound like any unanswered floor.
        //
        // The turn is the server's to number (D-18): turn_start opens it, the ack names it, and
        // from here only segments stamped with this id are this turn's speech. Everything
        // earlier - the tail of the wake phrase, the question a barge-in cut down, the reply the
        // follow-up window is listening past, the last turn's endpointed text still sitting in
        // the transcript - carries an older id and is fenced out. Never by a segment-index
        // watermark: a revision of a dispatched utterance arrives under the *same* index, and a
        // watermark parked there would filter out the very supersede below.
        val turnId = withTimeoutOrNull(windowMillis) {
            openTurn()?.also {
                // The grace beat (T-23, REQ-21), after the ack and before the announcement: the
                // floor is already open - speech from here is stamped with this turn's id and
                // heard - and only the cue waits. After the ack, not around it: a floor that
                // never opened must never be announced, however long the beat.
                if (cueGraceMillis > 0) delay(cueGraceMillis)

                // Listening is open only now, not at the wake: the go-ahead has to wait for the
                // server's ack, because speech before it is stamped with the previous turn's id
                // and fenced out. On every path into a spoken turn - the wake word, a barge-in,
                // the follow-up window - this is the moment the person can be told the floor is
                // theirs, and the wake's acknowledgement folds into the same figure (REQ-39).
                cue(LcarsCue.LISTENING_OPEN)
            }
        } ?: return closeListening()

        // The wait, silence-bounded and ceiling-capped (T-24/T-25, REQ-23): [awaitUtterance]
        // re-arms on movement, and the ceiling around it is REQ-40's backstop - the re-arm
        // trusts an energy gate, and a noise source that never pauses must not hold the mic
        // streaming off-device indefinitely. The ceiling is the working bound plus a fixed
        // headroom, never a flat number: a flat ceiling under the knob's range would silently
        // truncate a long follow-up window's silent close (the knob parses up to 120 s), and a
        // ceiling equal to the bound would leave the re-arm nothing to extend. The sum closes
        // the floor whatever the gate is saying, one headroom past where silence would have.
        val turnFlow = voice.transcript.map { it.turn(turnId) }
        val heard = withTimeoutOrNull(windowMillis + WINDOW_CEILING_HEADROOM_MILLIS) {
            awaitUtterance(windowMillis, turnFlow)
        } ?: return closeListening()

        // The utterance takes the wall (T-44). On a follow-up the finished exchange is still the
        // live display - kept there through the window, so a window that closed silently left it
        // untouched - and only now, with the next turn real, does it retire onto the spoken
        // record. The wake and barge-in entries reset the display before dispatching, so for
        // them this is a no-op.
        retireTurn()

        var thisTurn = heard
        while (true) {
            _turn.update { TurnLog(wakeAtMillis = it.wakeAtMillis, prompt = thisTurn.text) }
            thisTurn = runUntilSuperseded(thisTurn, turnId) ?: return
        }
    }

    /**
     * Wait out the open floor for a dispatchable utterance, or null when silence closes it.
     *
     * Dispatch on the endpoint, even a provisional one (D-08): VoiceManager may still reopen the
     * turn and revise what it heard, but waiting for it to promise otherwise costs a beat of
     * silence on every single turn. The clock bounds *silence*, re-armed by either sign of life
     * (REQ-23): this turn's transcript moving - `first { it != seen }`, endpointed or not - or a
     * confirmed run of speech at the microphone, which is the only signal that exists while a
     * continuous talker holds the floor, because the shipped STT emits nothing until they pause.
     * The speech branch yields [seen] unchanged: the wait re-arms and goes around again. And the
     * dispatchable check leads the wait: an utterance already retained - spoken into the grace
     * beat - dispatches now rather than waiting for a revision that may never come.
     */
    private suspend fun awaitUtterance(windowMillis: Long, turnFlow: Flow<Transcript>): Transcript? {
        var seen = turnFlow.first()
        while (!(seen.endpointed && seen.text.isNotBlank())) {
            val speechBefore = speechHeard.value
            seen = withTimeoutOrNull(windowMillis) {
                merge(
                    turnFlow.filter { it != seen },
                    speechHeard.filter { it != speechBefore }.map { seen },
                ).first()
            } ?: return null
        }
        return seen
    }

    /**
     * Open the VoiceManager turn, telling the link giving up apart from the loop cancelling us (T-51).
     *
     * [VoiceLink.turnStart] suspends on the turn_started ack, which only arrives on a live link. A
     * redial drains its pending turn_start (REQ-10), so a socket that drops between the frame and the
     * ack surfaces here as a [CancellationException] - even though *this* turn coroutine is still very
     * much alive. Left to propagate it would fall straight through [startTurn]'s cancellation re-throw
     * and silently kill the turn with LISTENING latched and capture still streaming off-device. So:
     * a cancellation while we are still active is the link going down - return null and let the caller
     * close the floor. A cancellation that is genuinely ours - a barge-in, a supersede, the next turn,
     * or the window's own timeout - leaves us inactive, and must propagate untouched.
     */
    private suspend fun openTurn(): Int? =
        try {
            voice.turnStart()
        } catch (e: CancellationException) {
            if (currentCoroutineContext().isActive) null else throw e
        }

    /**
     * The floor was open and nobody took it (REQ-40): the wake yielded no endpointed utterance
     * within its 15 s, or the follow-up window ran its configured length in silence. The close
     * beep is the same voice as any other stop-listening (REQ-39), and READY re-arms the wake
     * gate with capture no longer leaving the tablet.
     */
    @Synchronized
    private fun closeListening() {
        cue(LcarsCue.LISTENING_CLOSED)
        streamingToStt = false
        wake.reset()
        _state.value = ConsoleState.READY
        Log.i(TAG, "listening window closed unanswered -> READY")
    }

    /**
     * One run against Hermes, watched: if the endpoint-hold reopens the turn and revises what was
     * said while the run is in flight, tear the run down and return the revision for redispatch
     * (REQ-08). Returns null when the run stood - dispatched text confirmed, or final all along.
     */
    private suspend fun runUntilSuperseded(dispatched: Transcript, turnId: Int): Transcript? =
        coroutineScope {
            val run = launch { runTurn(dispatched.text) }

            // A final is immutable: there is no revision to watch for, the run stands by contract.
            if (dispatched.isFinal) return@coroutineScope null

            val settled = voice.transcript
                .map { it.turn(turnId) }
                .first { it.isFinal || (it.endpointed && it.text.isNotBlank() && it.text != dispatched.text) }

            // The hold expired and the guess was right: a confirmation, not a revision.
            if (settled.text == dispatched.text) return@coroutineScope null

            supersede(run)
            settled
        }

    /** The given conversation turn's segments, as VoiceManager stamped them (D-18). */
    private fun Transcript.turn(turnId: Int): Transcript =
        Transcript(segments.filter { it.turnId == turnId })

    /**
     * The turn reopened: the run in flight is answering a question that was taken back, and not a
     * word of its reply may be spoken (REQ-08). The same three stops as a barge-in - player,
     * synthesis, run - because the same two pipes are producing the doomed reply. What stays
     * untouched is the microphone and the state machine: the conversation turn itself is still
     * going, and the redispatch follows immediately.
     *
     * Synchronized for the same reason as [onBargeIn]: clearing [ttsRequestId] under the lock is
     * what stops a delta already leaving [speakOut] from landing after the `tts_cancel` and
     * re-opening the request it just killed.
     */
    @Synchronized
    private fun supersede(run: Job) {
        val requestId = ttsRequestId
        val runId = _turn.value.runId

        ttsRequestId = null
        playingTts = false
        player.stopImmediately()

        // The run's collector is forwarding tokens nobody may hear - and left alone it would send
        // `done` at the end of them, flushing the request the next line cancels.
        run.cancel()

        requestId?.let { voice.cancelTts(it) }

        // Hermes' stop is an HTTP round trip, handed to the scope as in [onBargeIn] - the revised
        // dispatch must not queue behind it.
        if (runId != null) {
            scope.launch {
                runCatching { agent.stop(runId) }
                    .onFailure { Log.w(TAG, "superseded run $runId did not stop: ${it.message}") }
            }
        }
        Log.i(TAG, "superseded run $runId (tts $requestId): the turn reopened")
    }

    /**
     * One turn at a time: starting a turn abandons whatever the last one was still doing.
     *
     * The catch is REQ-26's no-death clause made structural (T-50): a turn coroutine that threw -
     * a dead Hermes at dispatch, a stream that dropped mid-run, anything [runTurn] did not already
     * handle - used to reach the scope's uncaught handler and take the process down on a single bad
     * utterance. Now every exit but cancellation ends as the error voice at READY. Cancellation is
     * the loop's own doing (barge-in, supersede, the next turn) and must propagate untouched, or the
     * job that replaced this one would be torn down by its predecessor's unwind.
     */
    private fun startTurn(block: suspend () -> Unit) {
        turnJob?.cancel()
        turnJob = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failTurn(e)
            }
        }
    }

    /**
     * One turn against Hermes, with its tokens going out to be spoken as they arrive.
     *
     * This is REQ-12 in eleven lines: dispatch, then forward every delta straight to `tts_stream`,
     * and only close the request when the run reaches a terminal event.
     */
    private suspend fun runTurn(prompt: String) {
        _state.value = ConsoleState.THINKING

        val requestId = newRequestId()
        // dispatch before the request id is live: an unreachable Hermes throws here, and until it
        // returns there is no reply on the wire for barge-in to cancel or a delta to belong to.
        // A throw goes to failTurn below, never past this coroutine (T-50, REQ-26).
        val runId = try {
            agent.dispatch(prompt, sessionId())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return failTurn(e)
        }
        ttsRequestId = requestId
        _turn.update { it.copy(runId = runId, dispatchedAtMillis = now()) }
        // No cue, deliberately (REQ-39): a beep right after the person's own utterance reads as
        // the console talking over them, and the reply that follows announces itself.
        Log.i(TAG, "THINKING: run $runId")

        var spoke = false
        val media = MediaLineFilter()

        // A delta's visible text joins the reply and goes out to be spoken; its MEDIA: targets go
        // to the shell instead.
        fun emit(out: MediaLineFilter.Output) {
            out.media.forEach { target ->
                runCatching { mediaTap(runId, target) }
                    .onFailure { Log.w(TAG, "media tap failed: ${it.message}") }
            }
            if (out.text.isEmpty()) return
            _turn.update {
                it.copy(
                    reply = it.reply + out.text,
                    firstDeltaAtMillis = it.firstDeltaAtMillis ?: now(),
                )
            }
            // Straight out, verbatim. VoiceManager synthesises each sentence as soon as it can
            // see the end of it, so holding a token back here only delays the speech - the filter
            // holds back only the few characters of a line that could still be a MEDIA: tag.
            speakOut(requestId, out.text)
            spoke = true
        }

        try {
            agent.events(runId).collect { event ->
                when (event) {
                    is HermesEvent.MessageDelta -> {
                        if (event.delta.isEmpty()) return@collect
                        emit(media.feed(event.delta))
                    }

                    // Terminal. The stream ends here - HermesClient closes it on a terminal event.
                    // A failure is the one terminal the person must hear (REQ-39): completed speaks
                    // for itself and cancelled was their own doing, but a dead run left in silence
                    // reads as a console that ignored them.
                    is HermesEvent.RunFailed -> {
                        cue(LcarsCue.ERROR)
                        _turn.update { it.copy(runCompletedAtMillis = now()) }
                    }

                    is HermesEvent.RunCompleted,
                    is HermesEvent.RunCancelled,
                    -> _turn.update { it.copy(runCompletedAtMillis = now()) }

                    // Tools, reasoning and approvals are not this loop's business - the tap below
                    // hands them to the shell (the artifact router, T-24; the approval pane, T-27).
                    // None of them are ever spoken.
                    else -> Unit
                }

                // The shell's turn, strictly after the loop's own: a delta is already on its way to be
                // spoken before its event reaches a pane, so nothing a tap does - including throwing -
                // can hold up or interrupt the reply (REQ-15).
                runCatching { eventTap(event) }
                    .onFailure { Log.w(TAG, "event tap failed on ${event::class.simpleName}: ${it.message}") }
            }
        } catch (e: CancellationException) {
            // A barge-in or supersede cancelled the run: the loop's own doing, and its handler has
            // already stopped the speaker and the synthesis. Must propagate, not become an error.
            throw e
        } catch (e: Throwable) {
            // The event stream dropped mid-run: a token may already be spoken, but no terminal is
            // coming to end the turn, and no `done` may be flushed into a run that is gone. The
            // error voice, then READY - never past this coroutine (T-50, REQ-26).
            return failTurn(e)
        }

        // The stream is over: whatever the filter still holds is the reply's last text or tag.
        emit(media.finish())

        if (spoke) {
            // The only thing that flushes VoiceManager's last sentence: it holds the trailing
            // fragment back because it cannot know the reply is over. The turn ends on `audio_end`.
            speakOut(requestId, "", done = true)
        } else {
            // A run that said nothing - failed, cancelled, or answered with a tool call and no prose.
            // No TTS request was ever opened, so no `audio_end` is coming to end the turn for us.
            Log.i(TAG, "run $runId produced no speech")
            endTurn()
        }
    }

    /**
     * The turn died on the wire, not on a terminal event (T-50): Hermes was unreachable at dispatch,
     * or the run's event stream dropped mid-reply. The one failure no server event announces, so the
     * console announces it - the error voice (REQ-39) over a fault the room would otherwise read as
     * being ignored - and hands the floor back to READY. It never lets the throw past the turn
     * coroutine, which is REQ-26's no-death-in-logcat clause: one bad utterance must not be able to
     * take the process down.
     *
     * Synchronized against the barge-in family for the same reason they guard each other: it clears
     * the request the reply was speaking under, and a delta racing out of [speakOut] must not re-open
     * it. A request that was mid-flight is cancelled so VoiceManager stops synthesising into a dead
     * run; its residual audio then drops once [endTurn] clears the id.
     */
    @Synchronized
    private fun failTurn(cause: Throwable) {
        Log.w(TAG, "turn failed on the wire: ${cause.message}", cause)
        cue(LcarsCue.ERROR)
        _turn.update {
            it.copy(
                error = cause.message ?: cause::class.simpleName ?: "run failed",
                runCompletedAtMillis = it.runCompletedAtMillis ?: now(),
            )
        }
        ttsRequestId?.let { voice.cancelTts(it) }
        player.stopImmediately()
        // repliedAloud stays false: a failed run never earns a follow-up window, so this is READY -
        // and if the turn was a spoken one, the close beep, as any stop-listening is (REQ-39).
        endTurn()
    }

    /**
     * Send text to be spoken, unless the request it belongs to has been cancelled.
     *
     * Synchronized against [onBargeIn], and that is the entire point of the method: without the
     * lock, a delta already on its way out of the `collect` above can overtake the `tts_cancel` and
     * land at VoiceManager afterwards - which does not just leak a token, it *re-opens* the request
     * (the server accumulates by request_id) and the abandoned reply starts speaking over the person
     * who interrupted it. Losing the token is the correct outcome; the run it came from is dead.
     */
    @Synchronized
    private fun speakOut(requestId: String, text: String, done: Boolean = false) {
        if (requestId != ttsRequestId) return
        voice.sendTtsText(requestId, text, done)
    }

    /**
     * The trip the stopgap swallowed.
     *
     * Barge-in switched off suppresses the barge-in *action*, never the detection - and a
     * detection nobody records is a leak nobody can measure, on exactly the runs the stopgap is
     * kept for. This logs what [onBargeIn] logs at its own decision, in the same fields and the
     * same order, so a suppressed trip and a live one read line for line against each other.
     *
     * The reply is not stopped, and that is the point: playback continues and the rest of it stays
     * measurable, where a real barge-in would flush the track and end the very reply under
     * investigation. Fires once per sustained run - [SpeechGate] confirms on the fifth frame and
     * not again until the level drops back under the gate - so a long leak logs once, not fifty
     * times.
     */
    private fun logSuppressedBargeIn() {
        val trigger = bargeIn.trigger()
        Log.i(
            TAG,
            "barge-in SUPPRESSED (stopgap) level=${trigger.levelDbfs} dBFS " +
                "converged=${trigger.converged} exposure=${trigger.exposureMillis}ms " +
                "underruns=${player.underrunCount} refFed=${refBytesFed}B",
        )
    }

    /**
     * Somebody talked over the console (REQ-04). Stop all three of the things that are still
     * producing this reply, and make what they are saying the next turn.
     *
     * Runs on the capture collector, so it is all non-blocking: the two local cancels and the socket
     * frame happen here and now, and only Hermes' stop - an HTTP round trip - is handed to the scope.
     * The 500 ms budget is measured to the *last* of the three, and a blocking POST in this method
     * would spend it holding up the microphone.
     */
    @Synchronized
    private fun onBargeIn() {
        val speechAt = now()
        val cancelled = ttsRequestId
        val runId = _turn.value.runId

        // Why the gate fired, taken now because in three lines' time there will be nothing left to
        // ask: the detector's verdict describes the frame it just judged, and the underrun count
        // belongs to a track that [stopImmediately] is about to release. Neither read can block or
        // throw - [TtsPlayer.underrunCount] swallows whatever the device says back - so the speaker
        // still stops on the next line, which is the one promise this method cannot break.
        val trigger = bargeIn.trigger()
        val underruns = player.underrunCount

        // 1. The speaker, first and locally: whatever else fails, the tablet shuts up. Clearing the
        //    request id under the lock is what stops any further token of this reply being sent, and
        //    what makes the PCM still arriving for it unplayable ([onTtsEvent]).
        ttsRequestId = null
        playingTts = false
        player.stopImmediately()
        _lastBargeIn.value = BargeInLog(
            speechAtMillis = speechAt,
            runId = runId,
            requestId = cancelled,
            playbackStoppedAtMillis = now(),
            levelDbfs = trigger.levelDbfs,
            converged = trigger.converged,
            exposureMillis = trigger.exposureMillis,
            playbackUnderruns = underruns,
        )
        // The instant the tablet goes quiet, not when the remote cancels land: the yield is what
        // the interrupter needs confirmed, and only the local stop is theirs to hear (REQ-39).
        cue(LcarsCue.BARGE_IN)

        // 2. The turn's coroutine: it is sitting in `collect`, forwarding the tokens of a reply
        //    nobody is going to hear, and it would send `done` at the end of them.
        turnJob?.cancel()

        // 3. VoiceManager, so the sentence stops being made rather than being made into silence.
        cancelled?.let {
            voice.cancelTts(it)
            _lastBargeIn.update { log -> log?.copy(ttsCancelledAtMillis = now()) }
        }

        // 4. Hermes, so the run stops thinking. An abandoned run keeps calling tools and keeps
        //    costing tokens for an answer that will never be spoken (REQ-13).
        if (runId != null) {
            scope.launch {
                runCatching { agent.stop(runId) }
                    .onFailure { Log.w(TAG, "run $runId did not stop: ${it.message}") }
                _lastBargeIn.update { log -> log?.copy(runStoppedAtMillis = now()) }
                Log.i(TAG, "barge-in: ${_lastBargeIn.value}")
            }
        }

        // The interruption is the next turn. The microphone never stopped, and the dispatcher below
        // opens a fresh VoiceManager turn - the question this reply was answering keeps its old
        // turn_id, so what the speaker is saying right now is the only thing the new turn can be
        // made of (D-18).
        streamingToStt = true
        wake.reset()
        _state.value = ConsoleState.LISTENING
        retireTurn()
        _turn.value = TurnLog(wakeAtMillis = speechAt)
        // Here rather than only on the full [BargeInLog] below: that one waits for Hermes' stop to
        // come back and is skipped entirely when no run was in flight - which is a shape the phantom
        // can take. What fired the gate is logged at the decision, unconditionally, every time.
        Log.i(
            TAG,
            "barge-in -> LISTENING (run $runId, tts $cancelled) level=${trigger.levelDbfs} dBFS " +
                "converged=${trigger.converged} exposure=${trigger.exposureMillis}ms underruns=$underruns",
        )

        // The wake's window, not the knob's: an interrupter is already mid-sentence, and 15 s is
        // the same bound REQ-40 puts on any listening that never hears an endpointed thought.
        startTurn { dispatchWhatIsSaidNext(WAKE_WINDOW_MILLIS) }
    }

    /**
     * VoiceManager's TTS stream, gated on the request it belongs to.
     *
     * The gate is barge-in's third and quietest half. A cancelled request does not go quiet on the
     * wire - the server finishes the chunk it is in, then sends `audio_end` for the request it just
     * killed - so without the check the console would play the tail of the reply it abandoned, and
     * then let that reply's `audio_end` end the turn the interrupter had already started.
     */
    private fun onTtsEvent(event: TtsEvent) {
        when (event) {
            is TtsEvent.Start -> onTtsStart(event)

            // Binary frames carry no request id, so they inherit the verdict on the `audio_start`
            // that opened them - which barge-in revoked.
            //
            // Not synchronized, and that is deliberate: the write below blocks on the device's own
            // consumption rate, and holding the loop's monitor across it would make barge-in wait
            // for the audio it is trying to cancel. It reads the volatile flag instead, and
            // [TtsPlayer.onChunk] drops a chunk whose track has already been torn down - the two
            // together are what make a cancelled reply inaudible.
            is TtsEvent.Audio -> {
                if (!playingTts) {
                    _lastBargeIn.update { it?.copy(droppedAudioBytes = it.droppedAudioBytes + event.pcm.size) }
                    return
                }
                // Speech resuming after a gap of real silence inside one reply re-arms the guard.
                // A run's TTS is one open request_id, so a tool call that runs silent mid-reply emits
                // no new audio_start - but the canceller de-adapts through that quiet and leaks the
                // speech that follows (docs/aec-spike.md, "The eighth sighting"). The state stays
                // SPEAKING across the gap, so only the actual chunk cadence reveals it. Measured on
                // arriving PCM, not on the SPEAKING flag.
                val chunkAtMillis = now()
                val sinceLastChunk = lastAudioChunkAtMillis
                lastAudioChunkAtMillis = chunkAtMillis
                if (sinceLastChunk == 0L) {
                    // The reply's first sound. The one line that proves or kills the onset-lag
                    // diagnosis: a lag past the re-arm window with converged=true here means the
                    // guard spent its whole window on silence and latched before the tablet spoke
                    // (docs/handoff-2026-08-18-aec-dropout.md). Read, not acted on - A1 instruments
                    // and does not fix.
                    val onset = bargeIn.trigger()
                    onsetLagMillis = chunkAtMillis - audioStartAtMillis
                    onsetConverged = onset.converged
                    onsetExposureMillis = onset.exposureMillis
                    Log.i(
                        TAG,
                        "reply onset: lag=${onsetLagMillis}ms after audio_start " +
                            "converged=$onsetConverged exposure=${onsetExposureMillis}ms",
                    )
                } else {
                    val gap = chunkAtMillis - sinceLastChunk
                    replyGapsMillis += gap
                    if (gap >= REARM_ON_PLAYBACK_GAP_MILLIS) bargeIn.onPlaybackStarted()
                }
                replyChunks++
                replyAudioBytes += event.pcm.size
                player.onChunk(event.pcm)
                // The moment the console is audible. Ordered against run.completed, this is REQ-12.
                _turn.update {
                    it.copy(
                        firstAudioAtMillis = it.firstAudioAtMillis ?: now(),
                        audioBytes = it.audioBytes + event.pcm.size,
                    )
                }
            }

            is TtsEvent.End -> onTtsEnd(event)
        }
    }

    /**
     * Synchronized against [onBargeIn]: these two decide whether the console is playing, from two
     * different threads. An `audio_start` that overtook the cancel would re-open the track that
     * barge-in had just torn down, and the abandoned reply would speak after all.
     */
    @Synchronized
    private fun onTtsStart(event: TtsEvent.Start) {
        if (!isCurrent(event.requestId)) return
        // The rate is the turn's, not the device's (REQ-09) - it arrives on the wire.
        player.onAudioStart(event.sampleRate)
        // One resampler per reply, at the rate the tap actually emits (declared / decimation) - a
        // resampler built at the declared rate would mis-time a decimated track's reference (T-09,
        // REQ-04). The reply's End flushes the held-back tail and drops it; the next reply starts
        // fresh even when the track is reused.
        referenceResampler = if (aec != null) ReferenceResampler(player.tapRateHz) else null
        // The new reply starts with a clean reference buffer: any bytes left from a previous reply
        // (e.g. its abandoned tail) must not bleed into this reply's far-end (T-12 self-barge fix).
        referencePacer.clear()
        // This reply's playback meets the canceller with a freshly rebuilt track (the reference is
        // torn down and rebuilt per reply), so re-arm the guard: a canceller that was warm on the
        // last reply leaks this one's onset otherwise, with the guard still latched from before
        // (docs/aec-spike.md, "The fifth and sixth sightings"). Once per reply, here at audio_start;
        // resumes after a mid-reply tool-call silence are caught by the chunk-gap re-arm in onTtsEvent.
        bargeIn.onPlaybackStarted()
        // T-12 self-barge follow-up (B-3): the session-start seed is always null (no playback has run
        // at boot), so no real reply was ever being seeded. A fresh/reused track reports ~0 at
        // audio_start (nothing presented yet) - a real output-path latency only shows once playback is
        // streaming with a backlog - so sample it over the reply's first seconds and apply the peak to
        // the live session, re-anchoring AEC3 on each fresh reference track. Sub-1-ms readings are
        // meaningless and ignored. One shot per reply; a reply change cancels the previous sampler.
        delaySeedJob?.cancel()
        delaySeedJob = scope.launch {
            var best = 0
            repeat(6) {
                delay(500)
                if (!isActive) return@launch
                val seed = player.latencyMillis
                if (seed != null && seed > best) best = seed
                if (best > 0) aec?.setDelaySeedMs(best)
            }
            if (best > 0 && !delaySeedApplied) {
                delaySeedApplied = true
                Log.i(TAG, "AEC delay seed applied: $best ms (live reply path)")
            }
        }
        lastAudioChunkAtMillis = 0L
        // The reply's cadence record starts here, at the event the onset lag is measured from. A
        // reply the operator interrupted never reaches audio_end, so the reset is on this side.
        audioStartAtMillis = now()
        replyChunks = 0
        replyAudioBytes = 0L
        replyGapsMillis.clear()
        onsetLagMillis = 0L
        onsetConverged = false
        onsetExposureMillis = 0L
        playingTts = true
        _state.value = ConsoleState.SPEAKING
    }

    /** The turn's audio is done - if it is still this console's turn. See [isCurrent]. */
    @Synchronized
    private fun onTtsEnd(event: TtsEvent.End) {
        if (!isCurrent(event.requestId)) return
        player.onAudioEnd()
        // T-09: this reply's reference stream ends here. Flush the resampler's held-back tail
        // (zero-padded) into the pacer so the AEC saw the complete played reference, then drop the
        // resampler - the next reply builds a fresh one at its own rate (REQ-03, REQ-04). The
        // tail rides the same paced drain as the live chunks.
        referenceResampler?.flush()?.let { tail ->
            if (tail.isNotEmpty()) referencePacer.append(tail)
        }
        referenceResampler = null
        // T-12 self-barge (A1): the reply's playback shape, on one line - the onset lag and what
        // the guard had done by it, then how the rest of the PCM arrived.
        val cadence = replyCadence(event.requestId)
        lastReplyCadence = cadence
        Log.i(TAG, "reply cadence: ${cadence.summary()}")
        // The one path on which a reply was actually spoken to its end - the only end REQ-40
        // hands the floor back after. A speechless run's endTurn comes from runTurn instead.
        endTurn(repliedAloud = true)
    }

    /**
     * Does this TTS frame belong to the reply the console is currently giving?
     *
     * A frame with no request id is taken at its word: VoiceManager stamps every `audio_start` and
     * `audio_end` with one, and a server that did not could only ever have one turn in flight anyway.
     * A frame that arrives when no turn is in flight has nothing to belong to.
     */
    private fun isCurrent(requestId: String?): Boolean {
        val current = ttsRequestId ?: return false
        return requestId == null || requestId == current
    }

    /**
     * The turn is over: back to the wake gate - unless a spoken reply just finished, in which
     * case the floor is handed straight back for a follow-up (REQ-40).
     */
    @Synchronized
    private fun endTurn(repliedAloud: Boolean = false) {
        playingTts = false
        ttsRequestId = null
        // The turn's own audio must not be what wakes the console for the next one (REQ-05).
        wake.reset()

        // The follow-up window (REQ-40): a reply spoken into an open mic keeps the console
        // listening for the next utterance - same session, no wake word - and each exchange's
        // end re-arms it, because endTurn is where every spoken exchange ends. Only that path:
        // a typed turn ends in the silence it ran in, and a speechless run closes as it always
        // did. The length is the knob's, read now so a settings change owns the next window.
        val window = if (repliedAloud && streamingToStt) followUpWindowMillis() else 0
        if (window > 0) {
            _state.value = ConsoleState.LISTENING
            Log.i(TAG, "turn done -> follow-up window ${window}ms: ${_turn.value.summary()}")
            startTurn { dispatchWhatIsSaidNext(window, followUpCueGraceMillis) }
            return
        }

        // The close beep is the sound of the console stopping listening (REQ-39), so it belongs
        // only to turns that were listening at all: a typed turn ends in the same silence it ran
        // in, and a barge-in never comes through here - the microphone stays on the interrupter.
        if (streamingToStt) cue(LcarsCue.LISTENING_CLOSED)
        streamingToStt = false
        _state.value = ConsoleState.READY
        Log.i(TAG, "turn done -> READY: ${_turn.value.summary()}")
    }

    /**
     * Fire an earcon without trusting it, as [eventTap] is fired: two of the call sites are the
     * capture collector's thread mid-barge-in and the turn's own coroutine, and no chirp is worth
     * a broken loop. The kit's player only enqueues, but the port cannot know what it was handed.
     */
    private fun cue(cue: LcarsCue) {
        runCatching { cues(cue) }
            .onFailure { Log.w(TAG, "cue $cue failed: ${it.message}") }
    }

    override fun close() {
        captureJob = null
        // The AEC leaves the read path before it is torn down: no new frame sees it, the collector
        // is stopped, and only then is the instance closed - through the serialized wrapper, which
        // waits out any in-flight processCapture before the native session is destroyed.
        val closing = aec
        aec = null
        // Everything the loop runs lives in this scope, so this is the only cancel it needs.
        scope.cancel()
        closing?.close()
        // T-10 (REQ-11): the session's AEC cost, on the record at close - the mean and max the
        // on-device budget check reads against the 20 ms frame period. Gated on the AEC having
        // existed (a raw session has no cost to report), not on barge-in being enabled (REQ-13).
        if (closing != null) {
            val meanUs = if (aecFrameCount > 0) aecFrameTimeNs / aecFrameCount / 1_000 else 0
            Log.i(
                TAG,
                "AEC session: $aecFrameCount frames, mean ${meanUs} us, max ${aecMaxNs / 1_000} us",
            )
        }
        player.close()
        capture.close()
        wake.close()
    }

    /**
     * This reply's playback shape as it stands (T-12 self-barge, A1).
     *
     * The median is the plain middle element of the sorted gaps - the upper of the two on an even
     * count, which is the conservative half when the question is how often delivery stalls. A reply
     * of one chunk has no gaps and reports zeros for all three.
     */
    private fun replyCadence(requestId: String?): ReplyCadence {
        val gaps = replyGapsMillis.sorted()
        return ReplyCadence(
            requestId = requestId,
            onsetLagMillis = onsetLagMillis,
            onsetConverged = onsetConverged,
            onsetExposureMillis = onsetExposureMillis,
            chunks = replyChunks,
            audioBytes = replyAudioBytes,
            minGapMillis = gaps.firstOrNull() ?: 0L,
            medianGapMillis = if (gaps.isEmpty()) 0L else gaps[gaps.size / 2],
            maxGapMillis = gaps.lastOrNull() ?: 0L,
            gapsOverRearmThreshold = gaps.count { it >= REARM_ON_PLAYBACK_GAP_MILLIS },
        )
    }

    private fun ReplyCadence.summary() =
        "tts=$requestId onsetLag=${onsetLagMillis}ms onsetConverged=$onsetConverged " +
            "onsetExposure=${onsetExposureMillis}ms chunks=$chunks bytes=${audioBytes}B " +
            "gapMin=${minGapMillis}ms gapMedian=${medianGapMillis}ms gapMax=${maxGapMillis}ms " +
            "gapsOverRearm=$gapsOverRearmThreshold"

    private fun TurnLog.summary() =
        "run=$runId audio=${audioBytes}B firstAudioLatency=${firstAudioLatencyMillis}ms " +
            "spokeBeforeRunCompleted=$spokeBeforeRunCompleted captureEngaged=$captureEngaged"

    private companion object {
        const val TAG = "VoiceLoop"

        /**
         * A gap between playable PCM chunks longer than this reads as a real playback silence inside
         * one reply - a tool call ran, the run's TTS request stayed open but sent nothing - and the
         * speech that resumes after it re-arms the convergence guard (docs/aec-spike.md, "The eighth
         * sighting"). Set well above normal streaming jitter (chunks arrive far faster than this) and
         * well below a tool call's silence (the measured tool gaps ran 6-11 s), so it fires on the
         * gaps that de-adapt the canceller and not on ordinary inter-chunk spacing. The gap is the
         * model's tool-call silence, not the TTS engine's chunking, so it holds across engines -
         * but re-validate the threshold against the active engine's chunk cadence (audiocpp via
         * VoiceManager at the time of writing) if delivery changes.
         */
        const val REARM_ON_PLAYBACK_GAP_MILLIS = 2_000L

        /**
         * REQ-40's bound on listening that was never answered: a wake - or a barge-in - whose
         * floor goes this long without a sign of anyone speaking is back to wake-gated READY.
         * Since T-24 (REQ-22) it bounds silence, measured from the transcript's last movement,
         * not the utterance - a mover holds the floor for as long as the thought takes. A
         * constant, not the settings knob: the knob is the follow-up window's alone.
         */
        const val WAKE_WINDOW_MILLIS = 15_000L

        /**
         * The quiet beat before the follow-up window announces itself (T-23, REQ-21). Long
         * enough to read as the reply landing rather than the console snatching the floor back,
         * short enough to leave the knob's window essentially whole. A constant, like the wake
         * window: sound design, not configuration.
         */
        const val FOLLOW_UP_CUE_GRACE_MILLIS = 600L

        /**
         * How far past its silence bound speech can hold a floor open (T-25, REQ-23) - REQ-40's
         * backstop, applied as headroom: any open window closes within its own bound plus this,
         * whatever the energy gate is saying, so a noise source above the gate that never
         * pauses - a loud room, a vacuum at the wrong distance - cannot hold capture streaming
         * off-device indefinitely. Headroom rather than a flat cap, because a flat cap under
         * the follow-up knob's range (up to 120 s) would silently truncate a long window's
         * silent close. A minute past the bound is far past any utterance this console is
         * asked for, which is exactly what a backstop should be: never the working bound.
         */
        const val WINDOW_CEILING_HEADROOM_MILLIS = 60_000L
    }
}

/**
 * What the loop needs from VoiceManager.
 *
 * A port, not an abstraction for its own sake: it is what lets the ordering guarantee at the heart
 * of REQ-12 be tested on a plain JVM, with no socket and no server. [VoiceManagerClient.asVoiceLink]
 * is the only implementation that ships.
 */
interface VoiceLink {

    /**
     * Every segment the link has heard, across turns. Each is stamped with the conversation turn
     * its audio belongs to - scope by that, via [turnStart], never by a segment-index watermark
     * (D-18).
     */
    val transcript: StateFlow<Transcript>

    /** The TTS stream coming back: Start / Audio / End, in wire order. */
    val tts: Flow<TtsEvent>

    /**
     * Open a new conversation turn and return its id (D-18). The server owns the turn counter -
     * the id comes back on the `turn_started` ack - and every transcript segment from here is
     * stamped with it, which is what fences out a previous turn's stale utterances.
     */
    suspend fun turnStart(): Int

    /** Offer a frame of capture audio: raw 16 kHz mono PCM16 LE, as the recorder produces it. */
    fun sendCapture(frame: ByteArray)

    /** Push text to be spoken under [requestId]; [done] closes the request. */
    fun sendTtsText(requestId: String, text: String, done: Boolean = false)

    /** Stop synthesising [requestId]: the human is talking (REQ-04). */
    fun cancelTts(requestId: String)
}

/** What the loop needs from Hermes. See [VoiceLink] for why this is a port. */
interface Agent {

    /** Start a turn in [sessionId]. Returns the run id its events hang off. */
    suspend fun dispatch(input: String, sessionId: String): String

    /** The run's events, as they happen. Completes when the run reaches a terminal event. */
    fun events(runId: String): Flow<HermesEvent>

    /** Stop the run: the answer it is still writing is one nobody is going to hear (REQ-04). */
    suspend fun stop(runId: String)
}

/** The real VoiceManager link, as the loop sees it. */
fun VoiceManagerClient.asVoiceLink(): VoiceLink = object : VoiceLink {
    override val transcript: StateFlow<Transcript> get() = this@asVoiceLink.transcript
    override val tts: Flow<TtsEvent> get() = this@asVoiceLink.tts

    override suspend fun turnStart(): Int = this@asVoiceLink.turnStart()

    override fun sendCapture(frame: ByteArray) = sendAudio(frame.toPcm16Samples())

    override fun sendTtsText(requestId: String, text: String, done: Boolean) =
        this@asVoiceLink.sendTtsText(requestId, text, done)

    override fun cancelTts(requestId: String) = this@asVoiceLink.cancelTts(requestId)
}

/**
 * The real Hermes gateway, as the loop sees it. Every turn dispatches *in* its session
 * (T-22, REQ-23; REQ-01): [dispatchInSession] sends the utterance and the session id, and the
 * patched gateway (hermes-patch 0002, T-42) assembles the run's context from that session's own
 * stored conversation - the console's memory lives entirely in Hermes. There is no unscoped
 * branch to fall back to; the type is the enforcement (REQ-03).
 */
fun HermesClient.asAgent(): Agent = object : Agent {
    override suspend fun dispatch(input: String, sessionId: String): String =
        this@asAgent.dispatchInSession(input, sessionId)

    override fun events(runId: String): Flow<HermesEvent> = this@asAgent.events(runId)

    override suspend fun stop(runId: String) = this@asAgent.stop(runId)
}

/**
 * Capture bytes to samples: the recorder speaks 16-bit little-endian PCM, the voice client speaks
 * samples. An odd trailing byte is half a sample and is dropped rather than sign-extended into a
 * click.
 */
internal fun ByteArray.toPcm16Samples(): ShortArray {
    val count = size / 2
    val samples = ShortArray(count)
    for (i in 0 until count) {
        samples[i] = (((this[i * 2 + 1].toInt() shl 8)) or (this[i * 2].toInt() and 0xFF)).toShort()
    }
    return samples
}
