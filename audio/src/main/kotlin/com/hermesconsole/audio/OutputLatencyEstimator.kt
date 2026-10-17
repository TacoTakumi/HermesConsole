package com.hermesconsole.audio

/**
 * One sample of the output path's timing, exactly as AudioTrack.getTimestamp(AudioTimestamp)
 * reports it. CDD 5.6 C-1-1 pins framePosition (frames the device has presented) and nanoTime
 * (the monotonic time that position was reached) to +-2 ms on Android - the standard way to
 * measure the output path (D-04).
 */
data class AudioTimestampSnapshot(
    val framePosition: Long,
    val nanoTime: Long,
)

/**
 * Derives the output-path latency (playback buffer + DAC) from AudioTrack presentation timestamps
 * (REQ-05, D-04). The value seeds the APM's delay controller so convergence starts near the right
 * offset; AEC3's own controller tracks the rest. No hardcoded delay constant exists in the path -
 * every value comes from this measurement.
 *
 * The classic technique (Oboe/AAudio): the distance between the frames *written* to the track and
 * the frames the device has *presented* (getTimestamp's framePosition) is the audio still in flight
 * to the listener; divided by the sample rate that span is the output latency. Pure and
 * JVM-testable: the decimal math is the whole estimator, and the device only supplies the numbers.
 */
class OutputLatencyEstimator {

    /** Output latency in ms for a track that has [framesWritten] frames enqueued and presents [snapshot.framePosition]. */
    fun estimateLatencyMs(framesWritten: Long, sampleRateHz: Int, snapshot: AudioTimestampSnapshot): Double {
        if (sampleRateHz <= 0) return 0.0
        val inFlight = (framesWritten - snapshot.framePosition).coerceAtLeast(0)
        return inFlight * 1000.0 / sampleRateHz
    }
}