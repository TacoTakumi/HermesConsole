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
}
