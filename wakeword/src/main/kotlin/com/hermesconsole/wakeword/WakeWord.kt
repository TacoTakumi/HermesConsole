package com.hermesconsole.wakeword

import kotlinx.coroutines.flow.Flow

/** The phrase the console listens for. */
const val WAKE_PHRASE: String = "hey computer"

/** The wake phrase, and when it was heard. */
data class WakeWord(val phrase: String, val atMillis: Long)

/**
 * Something that listens to the capture stream and says when the wake phrase was spoken.
 *
 * The seam exists because the engine behind it is not settled. The phrase "computer" could not be
 * made to work on sherpa-onnx's KeywordSpotter at 2 m - four live runs and every knob the library
 * has (D-14a) - and "hey computer" clears the bar but not comfortably: it holds up when spoken
 * toward the tablet and slips when the speaker turns away. If that proves too fragile in daily use,
 * the answer is a different engine (Porcupine ships "computer" as a built-in keyword), not another
 * threshold.
 *
 * So the console depends on this, never on the engine. Everything above it sees raw PCM going in
 * and a [WakeWord] coming out; swapping what happens in between should not be felt anywhere else.
 */
interface WakeWordSource : AutoCloseable {

    /**
     * Emits a [WakeWord] every time the phrase is heard in [frames].
     *
     * [frames] are raw 16 kHz mono PCM16 little-endian, as the capture path produces them. An
     * implementation takes the stream as it comes and asks nothing about its provenance.
     */
    fun detections(frames: Flow<ByteArray>): Flow<WakeWord>

    /** Forget what has been heard so far - e.g. after a turn, so its audio can't retrigger. */
    fun reset()
}
