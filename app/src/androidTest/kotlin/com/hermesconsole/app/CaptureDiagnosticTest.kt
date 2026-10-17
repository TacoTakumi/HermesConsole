package com.hermesconsole.app

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.hermesconsole.audio.rmsDbfs
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Diagnostic spike, not a gate: why does the capture stream read as digital silence?
 *
 * Opens the same 16 kHz mono stream from several audio sources, with and without an attached
 * echo canceller, and reports what each one actually delivers. Kept because "the mic returns
 * zeros" is a failure mode worth being able to re-diagnose in one command.
 */
@RunWith(AndroidJUnit4::class)
class CaptureDiagnosticTest {

    @get:Rule
    val micPermission: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.RECORD_AUDIO)

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun which_source_actually_delivers_audio() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            delay(1_500)

            probe("VOICE_COMMUNICATION + AEC", MediaRecorder.AudioSource.VOICE_COMMUNICATION, aec = true)
            probe("VOICE_COMMUNICATION, no AEC", MediaRecorder.AudioSource.VOICE_COMMUNICATION, aec = false)
            probe("VOICE_RECOGNITION", MediaRecorder.AudioSource.VOICE_RECOGNITION, aec = false)
            probe("DEFAULT", MediaRecorder.AudioSource.DEFAULT, aec = false)

            // The one thing MicPipeline does that the probes above do not: pin the capture to a
            // specific AudioDeviceInfo. The voice-communication path does its own routing, so
            // overriding it may be what is handing us a dead stream.
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            audioManager.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
                .filter { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC }
                .forEach { device ->
                    probe(
                        "VOICE_COMMUNICATION + AEC, pinned to device ${device.id}",
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        aec = true,
                        preferred = device,
                    )
                }
        }
    }

    private fun probe(
        label: String,
        source: Int,
        aec: Boolean,
        preferred: android.media.AudioDeviceInfo? = null,
    ) {
        val minBuf = AudioRecord.getMinBufferSize(
            16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = AudioRecord(
            source, 16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4,
        )
        preferred?.let { record.setPreferredDevice(it) }
        var canceler: AcousticEchoCanceler? = null
        if (aec) {
            canceler = AcousticEchoCanceler.create(record.audioSessionId)?.apply { setEnabled(true) }
        }

        val initialized = record.state == AudioRecord.STATE_INITIALIZED
        record.startRecording()
        val recording = record.recordingState == AudioRecord.RECORDSTATE_RECORDING

        val buffer = ByteArray(640)
        var peak = -120.0
        var nonZeroFrames = 0
        var shortReads = 0
        val started = System.currentTimeMillis()
        repeat(100) { // 100 x 20 ms = 2 s
            val read = record.read(buffer, 0, buffer.size)
            if (read < buffer.size) shortReads++
            if (read > 0) {
                val level = rmsDbfs(buffer.copyOf(read))
                if (level > peak) peak = level
                if (level > -119.0) nonZeroFrames++
            }
        }
        val elapsed = System.currentTimeMillis() - started

        Log.i(
            TAG,
            "$label: init=$initialized recording=$recording routedTo=${record.routedDevice?.id} " +
                "peak=${"%.1f".format(peak)} dBFS nonZeroFrames=$nonZeroFrames/100 " +
                "shortReads=$shortReads elapsed=${elapsed}ms (2000ms = realtime)",
        )

        canceler?.release()
        record.stop()
        record.release()
    }

    private companion object {
        const val TAG = "CaptureDiag"
    }
}
