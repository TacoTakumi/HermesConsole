package com.hermesconsole.audio

import kotlinx.coroutines.flow.Flow

/**
 * The only capture surface the voice loop is allowed to see.
 *
 * Everything below it - which device is selected, whether an AcousticEchoCanceler is attached,
 * whether the mic is built in or hanging off USB - is configuration (REQ-03). The loop just
 * reads frames.
 */
interface AudioCapture : AutoCloseable {
    val config: AudioConfig

    /** Raw PCM chunks of [AudioConfig.chunkBytes], in the configured format. */
    fun frames(): Flow<ByteArray>

    /**
     * Frames the recorder has delivered since capture started, at the end of the frame most
     * recently emitted by [frames]. Zero for a capture that does not count them.
     */
    val framesRead: Long get() = 0

    /**
     * The recorder's (fractional) frame index captured at [timeNs] on CLOCK_MONOTONIC, or null
     * when the capture cannot say. Lets a speaker edge be placed on the recorded stream.
     */
    fun recordIndexAt(timeNs: Long): Double? = null
}
