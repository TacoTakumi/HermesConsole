package com.hermesconsole.app

import android.util.Log
import com.hermesconsole.audio.OneShotSink
import com.hermesconsole.voiceclient.TtsEvent
import com.hermesconsole.voiceclient.VoiceManagerClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream

/**
 * The approval's voice (REQ-11): a rising approval says so out loud - one short fixed line - so
 * the eyes-free case hears the question the cue only points at.
 *
 * Deliberately OUTSIDE [VoiceLoop]: the loop's TTS request is live from dispatch, and its audio
 * flips the console to SPEAKING - which arms barge-in. An announcement spoken through the loop
 * during a stalled approval would turn the operator's "deny" into a barge-in that kills the run
 * instead of answering it. So the line is synthesized once through the announcer's own
 * VoiceManager link, cached, and played through the cue path's one-shot sink: no loop state
 * moves, no reply audio is touched, and a barge-in has nothing to cancel.
 *
 * The line itself is chosen so the console can never answer its own question: "approval" matches
 * neither of [ApprovalController]'s decision words, and the controller's test pins that.
 *
 * Every failure is the voice's alone: a dead backend logs, plays nothing, and is retried on the
 * next rise - the cue (REQ-10) already sounded, and the pane is on the wall either way.
 */
class ApprovalAnnouncer(
    /** A fresh TTS-only link per synthesis - never the loop's own (its tts flow has one consumer). */
    private val synths: () -> AnnouncementSynth,
    /** The cue path's output: one-shot, echo-cancelled, the same route every earcon takes (REQ-33). */
    private val sink: OneShotSink,
    private val scope: CoroutineScope,
    /** Is a reply speaking right now? Then the announcement stays silent: cue-only (REQ-11). */
    private val speaking: () -> Boolean,
) {

    /** The line, synthesized at [cachedRateHz], once it has been. */
    @Volatile
    private var cachedPcm: ByteArray? = null

    @Volatile
    private var cachedRateHz: Int = 0

    /** Single-flight: a second rise while the first synthesis runs must not open a second link. */
    @Volatile
    private var synthesizing = false

    /** Say the line - from the cache when it is warm, else synthesize first, skipping nothing but a live reply. */
    fun speak() {
        if (speaking()) return
        cachedPcm?.let { return sink.play(it, cachedRateHz) }

        synchronized(this) {
            if (synthesizing) return
            synthesizing = true
        }
        scope.launch {
            try {
                val (pcm, rateHz) = synthesize() ?: return@launch
                cachedPcm = pcm
                cachedRateHz = rateHz
                if (!speaking()) sink.play(pcm, rateHz)
            } finally {
                synthesizing = false
            }
        }
    }

    /** The line as PCM at its wire rate, or null for any reason at all - the voice is never worth a throw. */
    private suspend fun synthesize(): Pair<ByteArray, Int>? = runCatching {
        synths().use { synth ->
            withTimeout(SYNTH_TIMEOUT_MS) {
                val audio = ByteArrayOutputStream()
                var rateHz = 0
                // Send first, then collect: the client buffers its tts events unbounded, so the
                // reply cannot outrun the collector.
                synth.send(REQUEST_ID, LINE, done = true)
                synth.tts
                    .takeWhile { it !is TtsEvent.End }
                    .collect { event ->
                        when (event) {
                            is TtsEvent.Start -> rateHz = event.sampleRate
                            is TtsEvent.Audio -> {
                                audio.write(event.pcm)
                                rateHz = event.sampleRate
                            }
                            is TtsEvent.End -> Unit
                        }
                    }
                check(audio.size() > 0) { "tts stream ended with no audio" }
                audio.toByteArray() to rateHz
            }
        }
    }.getOrElse { failure ->
        // A cancellation that is genuinely ours - the console closing - must propagate; only the
        // synthesis's own failures (timeout included) are swallowed into a skipped voice.
        if (failure is CancellationException && !currentCoroutineContext().isActive) throw failure
        Log.w(TAG, "approval announcement synthesis failed: ${failure.message}")
        null
    }

    companion object {
        /**
         * The whole announcement. Contains neither decision word on purpose: the console must
         * never be able to answer the question it is asking (pinned by the controller's test).
         */
        const val LINE = "Approval required."

        private const val REQUEST_ID = "approval-announcement"
        private const val SYNTH_TIMEOUT_MS = 15_000L
        private const val TAG = "ApprovalAnnouncer"
    }
}

/** What the announcer needs from a voice backend: the TTS stream, and the words to put on it. */
interface AnnouncementSynth : AutoCloseable {
    val tts: Flow<TtsEvent>
    fun send(requestId: String, text: String, done: Boolean)
}

/** A [VoiceManagerClient] as the announcer's synth: connected on open, gone for good on close. */
fun VoiceManagerClient.asAnnouncementSynth(): AnnouncementSynth = object : AnnouncementSynth {
    init {
        connect()
    }

    override val tts: Flow<TtsEvent> get() = this@asAnnouncementSynth.tts

    override fun send(requestId: String, text: String, done: Boolean) =
        sendTtsText(requestId, text, done)

    override fun close() = shutdown()
}
