package com.hermesconsole.audio

/**
 * A recording [SoftwareAec] for JVM tests (REQ-07).
 *
 * Passes capture through unchanged - a copy, so a caller that mutates its own buffer cannot alias
 * the record - and records every reference chunk, seed and close verbatim. Lives in the main
 * source set so both the :audio seam tests and the :app voice-loop wiring tests drive the same
 * double; it is a test double only, never instantiated on the device path ([NativeSoftwareAec]
 * is the production implementation).
 */
class FakeSoftwareAec : SoftwareAec {
    val captureInputs = mutableListOf<ByteArray>()
    val referenceInputs = mutableListOf<ByteArray>()
    var delaySeedMs: Int? = null
    var closed = false

    override fun processCapture(frame: ByteArray): ByteArray {
        captureInputs += frame
        return frame.copyOf()
    }

    override fun processReference(pcm: ByteArray) {
        referenceInputs += pcm
    }

    override fun setDelaySeedMs(ms: Int) {
        delaySeedMs = ms
    }

    override fun close() {
        closed = true
    }
}
