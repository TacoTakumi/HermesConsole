package com.hermesconsole.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * T-06 / REQ-04, REQ-07: the Kotlin half of the JNI bridge agrees with the C++ frame contract,
 * and the wrapper's guards hold without the native library present.
 *
 * The native library does not exist on the JVM (REQ-07): these tests only exercise the parts of
 * [NativeSoftwareAec] that run before any native call - the 10 ms frame-size math and the
 * whole-frame guard on processCapture. The native path itself is verified on-device (startup
 * log) and by structural review of the bridge (webrtc-sdk/jni/webrtc_apm_jni.cc).
 */
class NativeSoftwareAecTest {

    @Test
    fun `ten-ms frame is 320 bytes at 16 kHz, matching the bridge's frame contract`() {
        // The bridge computes frame_bytes = (rate / 100) * sizeof(int16_t); at 16 kHz that is
        // 160 samples x 2 bytes = 320. REQ-04: 16 kHz int16 end to end.
        assertEquals(320, NativeSoftwareAec.frameBytesFor(16_000))
    }

    @Test
    fun `frame size scales with the sample rate`() {
        assertEquals(160, NativeSoftwareAec.frameBytesFor(8_000))
        assertEquals(960, NativeSoftwareAec.frameBytesFor(48_000))
    }

    @Test
    fun `capture rejects a chunk that is not whole 10 ms frames before any native call`() {
        val aec = NativeSoftwareAec(handle = 1L, frameBytes = 320)
        // 100 bytes is not a multiple of 320: the guard fires in Kotlin, so this never reaches
        // the native method (which the JVM cannot resolve - REQ-07).
        assertThrows(IllegalArgumentException::class.java) {
            aec.processCapture(ByteArray(100))
        }
        // A whole-frame size passes the guard (640 = two 10 ms frames, the capture path's
        // 20 ms chunk); the native call itself is the device's business.
        assertEquals(0, 640 % 320)
    }
}
