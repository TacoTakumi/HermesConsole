package com.hermesconsole.audio

import android.util.Log

/**
 * The WebRTC AEC3 implementation of the [SoftwareAec] seam (REQ-07, REQ-09, REQ-10, D-02).
 *
 * Loads libwebrtc_apm.so - arm64-v8a only (REQ-09) - and drives the AudioProcessing module
 * through the JNI bridge in `webrtc-sdk/jni/webrtc_apm_jni.cc`. The bridge is a static JNI
 * surface (`init`/`processCapture`/`processReference`/`setDelayMs`/`close`); each instance is
 * one APM session identified by an opaque native handle.
 *
 * Device only: the library does not exist on the JVM, so unit tests run against
 * [FakeSoftwareAec] and this class is only exercised where the native side is reachable
 * (REQ-07). The APM is not thread-safe; callers route every call through
 * [SerializedSoftwareAec] (REQ-06).
 */
class NativeSoftwareAec internal constructor(
    private val handle: Long,
    private val frameBytes: Int,
) : SoftwareAec {

    private var closed = false

    override fun processCapture(frame: ByteArray): ByteArray {
        check(!closed) { "AEC session already closed" }
        // The bridge buffers a trailing partial frame, but a capture chunk that is not whole
        // 10 ms frames would leave the tail of the output buffer unwritten, and the barge-in
        // detector would eat garbage (REQ-04). The capture path emits 20 ms chunks
        // (AudioConfig.chunkBytes = 640 = two 10 ms frames at 16 kHz), so whole-frame input is
        // the contract here; the check keeps a wiring slip from degrading the detector silently.
        require(frame.size % frameBytes == 0) {
            "capture frame must be whole 10 ms frames ($frameBytes bytes); got ${frame.size}"
        }
        val out = ByteArray(frame.size)
        processCapture(handle, frame, out)
        return out
    }

    override fun processReference(pcm: ByteArray) {
        check(!closed) { "AEC session already closed" }
        processReference(handle, pcm)
    }

    override fun setDelaySeedMs(ms: Int) {
        check(!closed) { "AEC session already closed" }
        setDelayMs(handle, ms)
    }

    override fun close() {
        if (!closed) {
            closed = true
            close(handle)
        }
    }

    companion object {
        const val TAG = "NativeSoftwareAec"

        /** The APM runs 16 kHz int16 end to end (REQ-04). */
        const val SAMPLE_RATE_HZ = 16_000

        /** Bytes in one 10 ms int16 mono frame at [sampleRateHz] - the APM's native frame. */
        fun frameBytesFor(sampleRateHz: Int): Int = sampleRateHz / 100 * 2

        /**
         * Create one software-AEC session at [sampleRateHz] (REQ-04). Loads the library on first
         * use and fails loudly - IllegalStateException - when the load or the APM init fails, so a
         * device that cannot run software AEC is visible in the log rather than silently degrading
         * barge-in. One session per capture; the caller owns [SoftwareAec.close].
         */
        fun create(sampleRateHz: Int = SAMPLE_RATE_HZ): NativeSoftwareAec {
            ensureLoaded()
            val handle = `init`(sampleRateHz)
            check(handle != 0L) {
                "AEC init failed at ${sampleRateHz} Hz; see logcat (tag $TAG)"
            }
            Log.i(TAG, "AEC session created: $sampleRateHz Hz, " +
                "${frameBytesFor(sampleRateHz)} bytes per 10 ms frame")
            return NativeSoftwareAec(handle, frameBytesFor(sampleRateHz))
        }

        /**
         * The startup probe (REQ-09): prove the library loads and a 16 kHz APM initializes on this
         * device, log the verdict, and close the probe session immediately. Called once at app
         * startup; the per-capture-session AEC is created separately when capture starts (T-08).
         */
        fun startupProbe(): Boolean = try {
            create(SAMPLE_RATE_HZ).close()
            Log.i(TAG, "libwebrtc_apm loaded; 16 kHz AEC3 APM initialized")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "software AEC startup probe failed: ${t.message}", t)
            false
        }

        @Volatile
        private var loaded = false

        private fun ensureLoaded() {
            if (loaded) return
            System.loadLibrary("webrtc_apm")
            loaded = true
        }

        // The JNI bridge in webrtc-sdk/jni/webrtc_apm_jni.cc. Static native methods on
        // NativeSoftwareAec (jclass receivers), matching the bridge's exported symbols.
        @JvmStatic private external fun `init`(sampleRateHz: Int): Long
        @JvmStatic private external fun processCapture(handle: Long, input: ByteArray, output: ByteArray)
        @JvmStatic private external fun processReference(handle: Long, input: ByteArray)
        @JvmStatic private external fun setDelayMs(handle: Long, ms: Int)
        @JvmStatic private external fun close(handle: Long)
    }
}
