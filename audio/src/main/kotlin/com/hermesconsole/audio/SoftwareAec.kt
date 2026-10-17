package com.hermesconsole.audio

/**
 * The software echo-canceller seam (REQ-07, D-01).
 *
 * Cancels the console's own TTS from the capture stream in software, independent of the
 * OTA-broken hardware AcousticEchoCanceler. The barge-in detector consumes [processCapture]'s
 * output; the exact PCM written to the output sink reaches the canceller through
 * [processReference]; the APM's delay controller is seeded from a measured output-latency
 * estimate via [setDelaySeedMs].
 *
 * Implementations are not thread-safe: the reference (TTS stream) and the capture (mic flow)
 * arrive from different threads. Callers must serialize every call - [SerializedSoftwareAec] is
 * the wrapper that does it (REQ-06).
 *
 * [FakeSoftwareAec] is the JVM test double; [NativeSoftwareAec] (device only) is the WebRTC AEC3
 * implementation behind JNI. REQ-07: unit tests run on the JVM without the native library.
 */
interface SoftwareAec : AutoCloseable {
    /**
     * Cancel the console's own voice from one 16 kHz int16 mono capture frame (20 ms).
     *
     * Returns the cancelled frame, which the caller feeds to the barge-in detector. The returned
     * buffer is freshly owned by the caller; the input may be discarded after the call.
     */
    fun processCapture(frame: ByteArray): ByteArray

    /**
     * Feed one chunk of the far-end reference: the exact PCM written to the output sink,
     * resampled to 16 kHz int16 mono (REQ-03, REQ-04).
     */
    fun processReference(pcm: ByteArray)

    /**
     * Seed the APM's delay controller with the measured output-path latency in ms (REQ-05).
     * Called before capture starts; no hardcoded delay constant exists in the path.
     */
    fun setDelaySeedMs(ms: Int)

    override fun close()
}
