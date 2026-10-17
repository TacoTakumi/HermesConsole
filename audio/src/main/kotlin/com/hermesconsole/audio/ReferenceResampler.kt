package com.hermesconsole.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Resamples the TTS reference from the engine's rate (22050 / 24000 / 48000 Hz) down to the
 * APM's 16 kHz (REQ-03, REQ-04, D-05). Pure Kotlin and JVM-testable by design: this is the only
 * rate conversion in the reference path, and the native build ships just the APM.
 *
 * Band-limited streaming resampler: each output sample interpolates the input with a
 * Blackman-windowed sinc kernel whose cutoff sits at the output Nyquist (8 kHz), so downsampling
 * cannot alias. Streaming: [resample] emits every output sample whose kernel window is fully
 * inside the input fed so far and keeps [RADIUS] samples of history, so 20 ms chunk boundaries
 * cost nothing; [flush] emits the tail with zero padding at stream end. One instance per capture
 * session, fed chunks in order.
 *
 * Output positions are tracked with an integer output counter, not accumulated floats: the
 * "is this output inside the input" test is `next * sourceRateHz < fed * 16000` in Longs, so the
 * total output count is exactly `fed * 16000 / sourceRateHz` for every rate (REQ-03's sample-count
 * acceptance) with no drift over a long session.
 */
class ReferenceResampler(private val sourceRateHz: Int) {

    init {
        require(sourceRateHz >= TARGET_RATE_HZ) { "sourceRateHz $sourceRateHz is below the $TARGET_RATE_HZ target" }
    }

    /** Input samples per output sample. */
    private val ratio = sourceRateHz.toDouble() / TARGET_RATE_HZ

    /** Kernel cutoff in cycles per input sample: the output Nyquist, 2*fc. */
    private val amp = TARGET_RATE_HZ.toDouble() / sourceRateHz

    /** Blackman window weight per tap offset k in 0..2*RADIUS (tap i = k - RADIUS). */
    private val windowWeights = FloatArray(2 * RADIUS + 1) { k ->
        val x = (k - RADIUS).toDouble() / RADIUS
        (0.42 + 0.5 * cos(PI * x) + 0.08 * cos(2 * PI * x)).toFloat()
    }

    /** Input samples (mono, float) available for interpolation, oldest first. */
    private var buffer = FloatArray(0)

    /** Absolute input index of [buffer]'s first sample. */
    private var bufferStart = 0

    /** Absolute index of the next output sample to emit. */
    private var next = 0L

    /** Total input samples ever fed, in absolute terms. */
    private var totalIn = 0L

    /**
     * Resample one PCM chunk (int16 mono LE). Returns the output samples whose kernel window is
     * fully inside the input fed so far; [RADIUS] samples of history are kept for the next call.
     */
    fun resample(pcm: ByteArray): ByteArray {
        // A 16 kHz tap is already at the APM's rate: pass it through untouched (REQ-04's "only rate
        // conversion"). Identity by construction, not by luck of the kernel - and it is also what
        // lets the wiring test assert byte-for-byte equality between the tapped reference and what
        // the AEC receives at the native rate. The stream state is skipped too, so a later [flush]
        // emits nothing: everything the input held was already emitted.
        if (sourceRateHz == TARGET_RATE_HZ) return pcm.copyOf()
        val chunk = toFloatSamples(pcm)
        val combined = FloatArray(buffer.size + chunk.size)
        buffer.copyInto(combined)
        chunk.copyInto(combined, buffer.size)
        buffer = combined
        totalIn += chunk.size

        val out = ArrayList<Float>()
        while (floor(nextInputPosition(next)).toInt() + RADIUS < bufferStart + buffer.size) {
            out += sampleAt(nextInputPosition(next))
            next++
        }

        // Keep exactly the window the next output still needs; drop the rest.
        val keepFrom = (floor(nextInputPosition(next)).toInt() - RADIUS).coerceAtLeast(bufferStart)
        val keepRelative = keepFrom - bufferStart
        bufferStart = keepFrom
        buffer = buffer.copyOfRange(keepRelative, buffer.size)
        return toBytes(out)
    }

    /**
     * Emit the remaining output samples (the stream's tail, zero-padded at the end) and reset.
     * Call once at reference-stream end.
     */
    fun flush(): ByteArray {
        val out = ArrayList<Float>()
        while (next * sourceRateHz.toLong() < totalIn * TARGET_RATE_HZ) {
            out += sampleAt(nextInputPosition(next))
            next++
        }
        reset()
        return toBytes(out)
    }

    /** Drop all state. */
    fun reset() {
        buffer = FloatArray(0)
        bufferStart = 0
        next = 0L
        totalIn = 0L
    }

    /** The absolute input position (in input-sample units) of output sample [n]. */
    private fun nextInputPosition(n: Long): Double = n * ratio

    /** The interpolated value at absolute input position [p], zero-padding outside the fed input. */
    private fun sampleAt(p: Double): Float {
        val center = floor(p).toInt()
        val frac = p - center
        var acc = 0.0
        var tapSum = 0.0
        for (k in 0..2 * RADIUS) {
            val i = k - RADIUS
            val idx = center + i - bufferStart
            val input = if (idx in buffer.indices) buffer[idx].toDouble() else 0.0
            val t = frac - i
            val kernel = if (t == 0.0) amp else amp * sinc(amp * t) * windowWeights[k]
            tapSum += kernel
            acc += input * kernel
        }
        // Normalize by the actual tap sum so DC gain is unity whatever the rate and window.
        return (acc / tapSum).toFloat()
    }

    private fun sinc(x: Double): Double {
        val px = PI * x
        return if (x == 0.0) 1.0 else sin(px) / px
    }

    private fun toFloatSamples(pcm: ByteArray): FloatArray {
        val samples = pcm.size / 2
        val out = FloatArray(samples)
        for (i in 0 until samples) {
            val s = ((pcm[i * 2 + 1].toInt() shl 8) or (pcm[i * 2].toInt() and 0xFF)).toShort()
            out[i] = s / 32768f
        }
        return out
    }

    private fun toBytes(samples: List<Float>): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val clamped = samples[i].coerceIn(-1f, 1f)
            val s = (clamped * Short.MAX_VALUE).roundToInt().toShort()
            out[i * 2] = (s.toInt() and 0xFF).toByte()
            out[i * 2 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    companion object {
        const val TARGET_RATE_HZ: Int = 16_000

        /**
         * Kernel radius in taps each side of the center. 32 taps each way keeps the passband
         * ripple under ~0.01 dB and the stopband far below the +-1 dB level bar REQ-03 sets, and
         * costs ~1M sinc evaluations/s at 16 kHz output - trivial for the TTS reference path.
         */
        const val RADIUS: Int = 32
    }
}
