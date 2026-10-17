package com.hermesconsole.app

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.hermesconsole.audio.AudioConfig
import com.hermesconsole.audio.AudioTrackSink
import com.hermesconsole.audio.MicPipeline
import com.hermesconsole.audio.SILENCE_DBFS
import com.hermesconsole.audio.rmsDbfs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Calibrates the barge-in threshold against the two things it must never confuse: the room
 * (forced-air fan, ambient) and a person speaking at the wall mount.
 *
 * The hardware gate proves the tablet's own TTS is cancelled to about -75 dBFS. That is only half
 * the answer. The threshold must also sit ABOVE whatever the empty room produces and BELOW a person
 * at ~2 m - otherwise barge-in either never fires (REQ-04 dead on arrival) or fires at the furnace.
 *
 * This test assumes the human will miss the cue, because that is what happened three times running:
 * it plays an unmistakable spoken cue, proves through the microphone that the cue was actually
 * audible, measures the empty room, and refuses to report a speech number unless the recording
 * plainly contains speech.
 */
@RunWith(AndroidJUnit4::class)
class SpeechThresholdCalibrationTest {

    @get:Rule
    val micPermission: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.RECORD_AUDIO)

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun calibrate_the_barge_in_threshold_against_the_room_and_a_person(): Unit = runBlocking {
        val cue = InstrumentationRegistry.getInstrumentation().context.assets
            .open("tts60.pcm").use { it.readBytes() }
            .copyOf(CUE_SECONDS * TTS_RATE * 2) // the first couple of sentences

        ActivityScenario.launch(MainActivity::class.java).use {
            delay(1_500)
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (max * 0.7).toInt(), 0)

            // 1. THE CUE - and proof it was audible. If the tablet's own voice does not reach its
            //    own unprocessed mic, the human heard nothing either, and everything after this
            //    would be a measurement of an empty room reported as a measurement of a person.
            Log.i(TAG, "=== CUE: the tablet is speaking. Start talking when it stops. ===")
            val cueHeard = playAndListenUnprocessed(cue)
            Log.i(TAG, "cue measured at the mic: ${"%.1f".format(cueHeard)} dBFS")
            assertTrue(
                "The spoken cue measured only ${"%.1f".format(cueHeard)} dBFS at the tablet's own " +
                    "mic: the speakers were not audible, so the human never got the cue and any " +
                    "'speech' below would be room noise. Check the volume and re-run.",
                cueHeard > CUE_AUDIBLE_DBFS,
            )

            val mic = MicPipeline(audioManager, AudioConfig())
            assertTrue("AEC must be on: this is the production capture path", mic.start().enabled)

            // 2. THE ROOM, nobody speaking: the floor the threshold has to clear.
            Log.i(TAG, "=== QUIET: measuring the empty room for ${AMBIENT_MS / 1000} s - do not speak ===")
            val ambient = listen(mic, AMBIENT_MS, label = "ambient")

            // 3. THE PERSON.
            Log.i(TAG, "=== SPEAK NOW for ${SPEECH_MS / 1000} s (about 2 m away, normal voice) ===")
            val speech = listen(mic, SPEECH_MS, label = "speech")
            mic.close()
            AudioTrackSink(TTS_RATE).use { it.write(beepPcm()); Thread.sleep(400) }

            Log.i(TAG, "---")
            Log.i(TAG, "AMBIENT room: peak=${"%.1f".format(ambient.peak)} dBFS voiced=${ambient.voiced}/${ambient.frames}")
            Log.i(TAG, "HUMAN speech: peak=${"%.1f".format(speech.peak)} dBFS voiced=${speech.voiced}/${speech.frames}")
            Log.i(TAG, "SWEEP ambient detections: ${ambient.sweep.report()}")
            Log.i(TAG, "SWEEP speech  detections: ${speech.sweep.report()}")

            // Did a person actually speak? A recording barely above the empty room is not a speech
            // recording, whatever its peak says.
            assertTrue(
                "This recording does not contain speech (voiced ${speech.voiced} vs ${ambient.voiced} " +
                    "in the empty room; peak ${"%.1f".format(speech.peak)} vs " +
                    "${"%.1f".format(ambient.peak)} dBFS ambient). Nobody spoke - there is nothing to " +
                    "calibrate here, and the threshold must NOT be tuned from this run.",
                speech.voiced > ambient.voiced * 2 && speech.peak > ambient.peak + 6,
            )
            Log.i(TAG, "VERDICT speech captured - the sweeps above decide the threshold")
        }
    }

    private data class Listened(
        val frames: Int,
        val voiced: Int,
        val peak: Double,
        val sweep: GateSweep,
    )

    private suspend fun listen(mic: MicPipeline, durationMs: Long, label: String): Listened {
        val sweep = GateSweep()
        var frames = 0
        var voiced = 0
        var peak = SILENCE_DBFS
        var secondPeak = SILENCE_DBFS
        var second = 0
        val started = System.currentTimeMillis()
        val deadline = started + durationMs

        withContext(Dispatchers.IO) {
            try {
                mic.frames().collect { frame ->
                    val level = rmsDbfs(frame)
                    frames++
                    if (level > peak) peak = level
                    if (level > secondPeak) secondPeak = level
                    if (level > SILENCE_DBFS + 1) voiced++
                    sweep.accept(frame)

                    val elapsed = ((System.currentTimeMillis() - started) / 1000).toInt()
                    if (elapsed > second) {
                        Log.i(TAG, "  [$label] t=${second}s peak=${"%.1f".format(secondPeak)} dBFS")
                        second = elapsed
                        secondPeak = SILENCE_DBFS
                    }
                    if (System.currentTimeMillis() >= deadline) throw Stop()
                }
            } catch (stop: Stop) {
                // window closed
            }
        }
        return Listened(frames, voiced, peak, sweep)
    }

    /** Play [pcm] out of the speakers and report the loudest thing the raw mic heard while it did. */
    private suspend fun playAndListenUnprocessed(pcm: ByteArray): Double = withContext(Dispatchers.IO) {
        val minBuf = AudioRecord.getMinBufferSize(
            16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, 16_000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4,
        ).apply { startRecording() }

        val sink = AudioTrackSink(TTS_RATE)
        val player = Thread { sink.write(pcm) }.apply { start() }

        var peak = SILENCE_DBFS
        val buffer = ByteArray(640)
        val deadline = System.currentTimeMillis() + CUE_SECONDS * 1000L
        while (System.currentTimeMillis() < deadline) {
            val read = record.read(buffer, 0, buffer.size)
            if (read <= 0) break
            val level = rmsDbfs(buffer.copyOf(read))
            if (level > peak) peak = level
        }
        player.join(2_000)
        sink.close()
        record.stop()
        record.release()
        peak
    }

    /** A short tone marking the end of the speech window. */
    private fun beepPcm(): ByteArray {
        val samples = TTS_RATE / 4
        val pcm = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val fade = minOf(1.0, minOf(i, samples - i) / (TTS_RATE * 0.02))
            val v = (0.35 * fade * Short.MAX_VALUE *
                kotlin.math.sin(2 * Math.PI * 440 * i / TTS_RATE)).toInt().toShort()
            pcm[i * 2] = (v.toInt() and 0xFF).toByte()
            pcm[i * 2 + 1] = ((v.toInt() shr 8) and 0xFF).toByte()
        }
        return pcm
    }

    private class Stop : RuntimeException()

    private companion object {
        const val TAG = "SpeechCal"
        const val TTS_RATE = 24_000
        const val CUE_SECONDS = 6
        const val AMBIENT_MS = 5_000L
        const val SPEECH_MS = 20_000L

        /** The cue must be at least this loud at the tablet's own mic to count as audible. */
        const val CUE_AUDIBLE_DBFS = -40.0
    }
}
