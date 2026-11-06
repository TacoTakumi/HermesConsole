package com.hermesconsole.audio

import kotlin.math.max
import kotlin.math.roundToLong

private const val NANOS_PER_SECOND = 1_000_000_000.0

/**
 * The CLOCK_MONOTONIC time at which [frame] is presented (or captured), from one reported frame
 * position and its time. AudioTrack.getTimestamp and AudioRecord.getTimestamp both report such a
 * pair on the same clock.
 */
fun timeOfFrame(frame: Long, anchorFrame: Long, anchorNs: Long, rateHz: Int): Long =
    anchorNs + ((frame - anchorFrame) * NANOS_PER_SECOND / rateHz).roundToLong()

/** The (fractional) frame position at [timeNs], from one reported frame position and its time. */
fun frameAtTime(timeNs: Long, anchorFrame: Long, anchorNs: Long, rateHz: Int): Double =
    anchorFrame + (timeNs - anchorNs) * rateHz / NANOS_PER_SECOND

/** A reply's played frames, counted at the rate its audio_start declared (playback may be decimated). */
fun replySamples(playedFrames: Long, playedRateHz: Int, declaredRateHz: Int): Long =
    playedFrames * declaredRateHz / playedRateHz

/**
 * Maps a recorder frame index to an index on the mic stream the client sent. The client calls
 * [anchor] after each frame it sends, with the recorder's frame count at the end of that frame and
 * the samples sent so far; recording that was not sent (between sessions, or while the link was
 * down) then shifts the offset, so it never counts.
 */
class MicSampleClock {

    @Volatile
    private var offset: Long? = null

    fun anchor(recordFramesEnd: Long, sentSamplesEnd: Long) {
        offset = sentSamplesEnd - recordFramesEnd
    }

    /** Forget the anchor: a new connection counts its own stream from zero. */
    fun reset() {
        offset = null
    }

    /** The sent-stream index of [recordIndex], never negative; null before any frame was sent. */
    fun micSampleAt(recordIndex: Double): Long? = offset?.let { max(0L, recordIndex.roundToLong() + it) }
}
