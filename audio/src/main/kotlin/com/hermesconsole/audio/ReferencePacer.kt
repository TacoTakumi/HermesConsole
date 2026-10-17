package com.hermesconsole.audio

/**
 * Smooths the TTS reference tap into a fixed-cadence feed for the AEC (T-12 self-barge fix).
 *
 * The TTS tap delivers the played PCM in network-cadence chunks whose size and spacing can jitter.
 * Fed straight to the APM, a gap between chunks looks to AEC3 like the far-end claiming silence
 * then restarting speech, and it briefly leaks the echo that is still in the room during the gap -
 * the isolated mid-reply bursts that self-barged the console on the SM-X210
 * (docs/projects/software-aec/t12-self-barge.md). The pacer buffers the tapped bytes and hands
 * them out one fixed-size frame per call, zero-padding any partial trailing frame, so the
 * reference stream the AEC sees is contiguous - the only discontinuity that remains is a genuine
 * silence, which the echo genuinely lacks too.
 *
 * Paced from the capture loop (one frame per capture frame, both 20 ms at the 16 kHz APM rate),
 * the reference advances in real time with capture, so the smosting never drifts from what is
 * actually in the room.
 *
 * Threading: [append] runs on the TTS thread (the reference consumer), [drainFrame] and [clear]
 * on the capture thread; all three synchronize on one lock, so the buffer is safe under the same
 * construct that serializes the APM itself (REQ-06).
 */
class ReferencePacer {

    private val lock = Any()
    private var buf: ByteArray = ByteArray(0)
    private var start = 0

    /** Reference bytes buffered and not yet drained - the lead the pacer holds against TTS jitter. */
    val bufferedBytes: Int get() = synchronized(lock) { buf.size - start }

    /** Queue tapped reference bytes (16 kHz int16) for the pacer to hand out in fixed frames. */
    fun append(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        synchronized(lock) {
            val live = buf.size - start
            val next = ByteArray(live + pcm.size)
            System.arraycopy(buf, start, next, 0, live)
            System.arraycopy(pcm, 0, next, live, pcm.size)
            buf = next
            start = 0
        }
    }

    /**
     * Remove the next [frameBytes] of buffered reference, zero-padding to a full frame if the
     * buffer ran short. Returns exactly [frameBytes] contiguous bytes. On a genuinely empty buffer
     * (nothing playing, or the tail after a reply) this is all zeros - the caller may feed it to
     * keep the stream contiguous or skip it; skipping when there has never been reference is the
     * harmless fire-and-forget case.
     */
    fun drainFrame(frameBytes: Int): ByteArray {
        val out = ByteArray(frameBytes)
        synchronized(lock) {
            val available = buf.size - start
            if (available > 0) {
                val n = minOf(available, frameBytes)
                System.arraycopy(buf, start, out, 0, n)
                start += n
                if (start == buf.size) {
                    buf = ByteArray(0)
                    start = 0
                }
            }
        }
        return out
    }

    /** Drop whatever is buffered (called when a reply's reference track is rebuilt). */
    fun clear() {
        synchronized(lock) {
            buf = ByteArray(0)
            start = 0
        }
    }

    companion object {
        /** One 20 ms frame at the APM's 16 kHz int16 rate. */
        const val FRAME_BYTES: Int = 16_000 * 2 / 50
    }
}