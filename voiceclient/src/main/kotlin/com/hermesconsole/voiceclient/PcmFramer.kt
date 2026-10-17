package com.hermesconsole.voiceclient

/**
 * Cuts a stream of PCM samples into fixed-size frames for the wire.
 *
 * Capture hands us whatever buffer size the recorder happened to fill; VoiceManager wants frames of
 * a configured size. This is the only place that decides what a frame looks like on the wire, and
 * what it writes is exactly the audio: 16-bit little-endian samples, no container header of any
 * kind. A partial frame is held until the next samples complete it.
 *
 * Not thread-safe: the client serialises calls onto the send path.
 */
internal class PcmFramer(
    private val chunkBytes: Int,
    private val onFrame: (ByteArray) -> Unit,
) {
    private val frame = ByteArray(chunkBytes)
    private var filled = 0

    fun offer(samples: ShortArray) {
        for (sample in samples) {
            val value = sample.toInt()
            frame[filled++] = (value and 0xFF).toByte()
            frame[filled++] = ((value shr 8) and 0xFF).toByte()
            if (filled == chunkBytes) {
                onFrame(frame.copyOf())
                filled = 0
            }
        }
    }

    /** Drop any partial frame - on a (re)connect, stale audio from the last link is not wanted. */
    fun reset() {
        filled = 0
    }
}
