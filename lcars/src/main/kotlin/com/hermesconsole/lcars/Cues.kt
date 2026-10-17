package com.hermesconsole.lcars

import androidx.compose.runtime.staticCompositionLocalOf
import com.hermesconsole.audio.OneShotSink
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The console's earcons (REQ-39): the audible vocabulary is exactly these voices. Dispatch is
 * deliberately silent - the person just spoke, and the console answering their utterance with a
 * beep reads as being talked over - and the wake has no voice of its own: its acknowledgement
 * folds into the open sequence, which fires when the STT turn actually opens. The kit owns what
 * the cues sound like; who fires them is the shell's business.
 *
 * REQ-39 fixed the set at five, all of them inside a turn. [SESSION_ENDED] is the sixth and the
 * first that speaks about the visit rather than the turn (wall-session-scoping REQ-09), which is
 * why it earns a voice at all: nothing in the five says "that conversation is over".
 */
enum class LcarsCue {

    /** Listening opened: the console heard its name and the floor is now yours - one figure for both. */
    LISTENING_OPEN,

    /** Listening closed: the console has stopped listening for this turn. */
    LISTENING_CLOSED,

    /** The visit is over: the console went idle in a conversation and returned to the main screen. */
    SESSION_ENDED,

    /** Barge-in accepted: the console was spoken over, and it yielded. */
    BARGE_IN,

    /** Error: the run died, out loud - the console never fails silently into READY. */
    ERROR,

    /** Approval required: a question took the wall, and it expires - never silently (REQ-10). */
    APPROVAL_REQUIRED,

    /** A touch registered. */
    CONFIRM,
}

/**
 * The cue set itself: PCM16 mono at [SAMPLE_RATE_HZ], synthesised rather than shipped.
 *
 * Synthesis is the asset. The screen-canon LCARS sounds are licensed material, so the kit speaks
 * in its own dialect: short pure-tone figures with raised-cosine edges. Each cue is a different
 * melodic shape, so they are distinct blind - and a pure tone is also the friendliest possible
 * signal to REQ-33, carrying none of the formant structure a wake-word model or an STT partial
 * could mistake for speech.
 */
object LcarsCues {

    /** One fixed rate for every cue - the track is built once and never rebuilt (unlike TTS, the rate is ours to choose). */
    const val SAMPLE_RATE_HZ = 22_050

    /** The cue's sound, ready for [AudioSink.write]. Owned by the kit: treat as immutable. */
    fun pcm(cue: LcarsCue): ByteArray = bank.getValue(cue)

    /** ~-10 dBFS: readable over a reply mid-sentence without startling a quiet room. */
    private const val AMPLITUDE = 0.30

    /**
     * The touch chirp sits well below the rest (~-20 dBFS). Unlike the transition earcons it never
     * plays over a spoken reply - it answers a tap in a quiet room - so it need not carry over TTS,
     * and a confirm that undersells itself reads better than one that competes with the voice.
     */
    private const val TOUCH_AMPLITUDE = 0.10

    /** Raised-cosine edges this long; a square-edged sine is heard as a click, not a note. */
    private const val RAMP_MS = 8

    private const val GAP_MS = 12

    private val bank: Map<LcarsCue, ByteArray> = mapOf(
        // Three rising steps: the old wake pair climbing into the go-ahead ping - "I heard my
        // name, and the floor is yours" as one figure (REQ-39).
        LcarsCue.LISTENING_OPEN to notes(740.0 to 70, 988.0 to 70, 1319.0 to 110),
        // One settled note, a fourth below the open's peak: the floor is closed.
        LcarsCue.LISTENING_CLOSED to notes(988.0 to 90),
        // The open's climb walked back down, landing longer than it left: the visit's full stop.
        // Same three steps, opposite direction - the shape says "this closes what that opened",
        // and no other cue in the bank descends across three notes.
        LcarsCue.SESSION_ENDED to notes(1319.0 to 70, 988.0 to 70, 740.0 to 130),
        // The opening steps inverted: "you have the floor".
        LcarsCue.BARGE_IN to notes(988.0 to 60, 740.0 to 80),
        // Low and falling, longer than the rest: unmistakably "no".
        LcarsCue.ERROR to notes(330.0 to 110, 262.0 to 150),
        // The only figure in the bank that turns - a drop, then a rise past where it began: a
        // question mark in sound, for the one pane that asks and will not wait forever.
        LcarsCue.APPROVAL_REQUIRED to notes(1175.0 to 60, 880.0 to 60, 1319.0 to 110),
        // The key chirp, softer than the voice-loop earcons above (it plays in quiet, on a tap).
        LcarsCue.CONFIRM to notes(1568.0 to 45, amplitude = TOUCH_AMPLITUDE),
    )

    private fun notes(vararg tones: Pair<Double, Int>, amplitude: Double = AMPLITUDE): ByteArray {
        val gap = ByteArray(2 * (SAMPLE_RATE_HZ * GAP_MS / 1000))
        val parts = buildList {
            tones.forEachIndexed { index, (freqHz, ms) ->
                if (index > 0) add(gap)
                add(tone(freqHz, ms, amplitude))
            }
        }
        val pcm = ByteArray(parts.sumOf { it.size })
        var offset = 0
        for (part in parts) {
            part.copyInto(pcm, offset)
            offset += part.size
        }
        return pcm
    }

    private fun tone(freqHz: Double, ms: Int, amplitude: Double): ByteArray {
        val samples = SAMPLE_RATE_HZ * ms / 1000
        val ramp = min(SAMPLE_RATE_HZ * RAMP_MS / 1000, samples / 2)
        val pcm = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val envelope = when {
                i < ramp -> 0.5 - 0.5 * cos(PI * i / ramp)
                i >= samples - ramp -> 0.5 - 0.5 * cos(PI * (samples - 1 - i) / ramp)
                else -> 1.0
            }
            val sample = (sin(2 * PI * freqHz * i / SAMPLE_RATE_HZ) * envelope * amplitude * Short.MAX_VALUE).toInt()
            pcm[i * 2] = (sample and 0xFF).toByte()
            pcm[i * 2 + 1] = (sample shr 8).toByte()
        }
        return pcm
    }
}

/**
 * Plays cues through the audio module's output path - the same route and usage TTS plays through,
 * which is the whole of REQ-33: a cue leaves the tablet on the route the echo canceller is already
 * cancelling, so it can no more trip the barge-in or wake detectors than the console's own voice can
 * (T-15). Each cue is a [OneShotSink.play], not a stream write: an earcon is a 45-250 ms clip, and
 * streaming those into one long-lived track starved it between taps - the mixer disabled the track
 * and the restart transient ate the chirp (measured on the SM-X210). The kit instantiates no
 * platform playback machinery of its own (REQ-39 - and a source scan enforces it); how the clip is
 * physically played, and where it goes, is the sink's decision (REQ-34), never this class's.
 *
 * One daemon worker owns playback: [play] only enqueues, so a cue costs its caller nothing -
 * [OneShotSink.play] blocks until the clip has played out, and the callers are a Compose click
 * handler and the voice loop's capture thread, neither of which may wait on a speaker. Cues queue
 * behind each other rather than mixing; they are fractions of a second long, and two transitions
 * close enough to overlap read better as a phrase anyway.
 */
class LcarsCuePlayer(private val sink: OneShotSink) : AutoCloseable {

    private val queue = LinkedBlockingQueue<LcarsCue>()

    private val worker = thread(name = "lcars-cues", isDaemon = true) {
        try {
            while (true) playOne(queue.take())
        } catch (_: InterruptedException) {
            // close() - the only thing that interrupts this thread.
        } finally {
            sink.close()
        }
    }

    /** Sound [cue], soon. Non-blocking, from any thread. */
    fun play(cue: LcarsCue) {
        queue.offer(cue)
    }

    private fun playOne(cue: LcarsCue) {
        try {
            sink.play(LcarsCues.pcm(cue), LcarsCues.SAMPLE_RATE_HZ)
        } catch (_: Exception) {
            // A cue is decoration: a failed one is dropped, never fatal.
        }
    }

    override fun close() {
        worker.interrupt()
    }
}

/**
 * How the kit's own components sound their touches: every enabled [LcarsSegment] and [LcarsPill]
 * click plays [LcarsCue.CONFIRM] through whatever the shell provides here. The default is silence,
 * so previews, tests and shells without an audio path need do nothing.
 */
val LocalLcarsCues = staticCompositionLocalOf<(LcarsCue) -> Unit> { {} }
