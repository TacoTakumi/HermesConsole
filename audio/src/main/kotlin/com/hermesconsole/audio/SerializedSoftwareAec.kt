package com.hermesconsole.audio

/**
 * Serializes every call to a [SoftwareAec] through one monitor (REQ-06).
 *
 * The APM is not thread-safe, and the reference and capture cross threads: TtsPlayer's reference
 * consumer fires on the playback thread while capture frames arrive on the record flow. This
 * wrapper funnels both through a single lock, so the delegate never sees two calls at once.
 *
 * A JVM monitor rather than a coroutine Mutex because the hot callers are non-suspend: the capture
 * flow's read loop and the TTS collector both call in from regular functions. The lock is held
 * only for the delegate call itself, and the capture path's 20 ms frame period is far larger than
 * either call, so this cannot starve capture.
 */
class SerializedSoftwareAec(private val delegate: SoftwareAec) : SoftwareAec {

    @Synchronized
    override fun processCapture(frame: ByteArray): ByteArray = delegate.processCapture(frame)

    @Synchronized
    override fun processReference(pcm: ByteArray) = delegate.processReference(pcm)

    @Synchronized
    override fun setDelaySeedMs(ms: Int) = delegate.setDelaySeedMs(ms)

    @Synchronized
    override fun close() = delegate.close()
}
