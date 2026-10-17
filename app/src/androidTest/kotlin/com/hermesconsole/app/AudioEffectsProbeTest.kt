package com.hermesconsole.app

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.hermesconsole.audio.SILENCE_DBFS
import com.hermesconsole.audio.rmsDbfs
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What voice processing does this tablet actually have, and what is bound to our capture session?
 *
 * Read-only, and needs nobody. It exists because T-33 spent a long time tuning a software gain to
 * compensate for a capture stream that arrives too quiet for the wake-word model - and Android has
 * an effect for exactly that (AutomaticGainControl), which nobody had checked for. A fixed gain
 * cannot track a speaker whose level changes between sessions; hardware AGC can.
 *
 * NoiseSuppressor is the other half of the question. VOICE_COMMUNICATION applies noise suppression
 * whether or not we ask for it, and suppression tuned to make a phone call intelligible is not
 * obviously kind to a 3.3M keyword spotter.
 */
@RunWith(AndroidJUnit4::class)
class AudioEffectsProbeTest {

    @get:Rule
    val micPermission: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.RECORD_AUDIO)

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val audioManager: AudioManager
        get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Test
    fun report_what_voice_processing_this_device_offers() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            delay(1_500)

            Log.i(TAG, "=== VOICE PROCESSING ON THIS DEVICE ===")
            Log.i(TAG, "AcousticEchoCanceler.isAvailable = ${AcousticEchoCanceler.isAvailable()}")
            Log.i(TAG, "AutomaticGainControl.isAvailable  = ${AutomaticGainControl.isAvailable()}")
            Log.i(TAG, "NoiseSuppressor.isAvailable       = ${NoiseSuppressor.isAvailable()}")

            // A capture session opened exactly as the console opens it, so the effects below are the
            // ones the wake word actually lives with - not a hypothetical session.
            @Suppress("MissingPermission")
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                16_000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                AudioRecord.getMinBufferSize(
                    16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                ) * 4,
            )
            val session = record.audioSessionId

            // What the platform will let us bind to OUR session. VOICE_COMMUNICATION brings its own
            // processing whether or not we ask; these say whether any of it is ours to control.
            val aec = runCatching { AcousticEchoCanceler.create(session) }.getOrNull()
            val agc = runCatching { AutomaticGainControl.create(session) }.getOrNull()
            val ns = runCatching { NoiseSuppressor.create(session) }.getOrNull()
            Log.i(TAG, "on our VOICE_COMMUNICATION session:")
            Log.i(TAG, "  AEC created=${aec != null} enabled=${aec?.enabled}")
            Log.i(TAG, "  AGC created=${agc != null} enabled=${agc?.enabled}")
            Log.i(TAG, "  NS  created=${ns != null} enabled=${ns?.enabled}")

            record.startRecording()
            val buffer = ByteArray(640)
            var peak = SILENCE_DBFS
            var sum = 0.0
            var n = 0
            repeat(50) { // 1 s of room tone: the level the wake word has to work with, on the record
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val level = rmsDbfs(buffer.copyOf(read))
                    peak = maxOf(peak, level)
                    sum += level
                    n++
                }
            }
            Log.i(TAG, "room tone over ${n * 20} ms: peak=${"%.1f".format(peak)} dBFS " +
                "mean=${"%.1f".format(if (n > 0) sum / n else SILENCE_DBFS)} dBFS")

            aec?.release()
            agc?.release()
            ns?.release()
            record.stop()
            record.release()
        }
    }

    private companion object {
        const val TAG = "AudioProbe"
    }
}
