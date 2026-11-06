package com.hermesconsole.app

import android.content.Context
import android.media.AudioManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.hermesconsole.audio.AudioConfig
import com.hermesconsole.audio.AudioTrackSinkFactory
import com.hermesconsole.audio.MicPipeline
import com.hermesconsole.audio.PlaybackEdge
import com.hermesconsole.audio.TtsPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Does a reply land on the mic stream where the turn engine is told it does? The console reports
 * each reply's playback start as a position on the mic stream it sends, from the speaker's and the
 * recorder's timestamps plus the echo path delay they leave out. This plays a reply through the
 * console's own player and output path while capturing through its own capture path: half a
 * second of silence, then a chirp. The chirp's position is predicted from the reply's start edge
 * and compared with where the chirp is heard.
 *
 * The turn engine works on 80 ms frames, so an edge within one frame of the truth is as good as
 * exact to it. Run through scripts/connected-test.sh, never a gradle connected run.
 */
@RunWith(AndroidJUnit4::class)
class MicSampleLoopbackTest {

    @get:Rule
    val micPermission: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.RECORD_AUDIO)

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val audioManager: AudioManager
        get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Test
    fun a_reply_lands_within_one_turn_engine_frame_of_its_predicted_place() = runBlocking<Unit> {
        val offsets = atNormalVolume { (1..RUNS).map { measureOnce(leadInMs = LEAD_IN_MS) } }
        Log.i(TAG, "RESULT offsets ms: ${offsets.describe()}")
        val passes = offsets.count { it != null && abs(it) <= LIMIT_MS }
        Log.i(TAG, "VERDICT ${if (passes == RUNS) "PASS" else "FAIL"} ($passes/$RUNS within $LIMIT_MS ms)")
        assertTrue("a reply landed more than $LIMIT_MS ms from its predicted place, or was not heard: $offsets", passes == RUNS)
    }

    /**
     * Information, not a gate: a reply loud from its very first frame. A fresh track ramps its
     * first frames in, so the sound is heard somewhat after the first frame plays; the log shows
     * by how much.
     */
    @Test
    fun report_a_reply_loud_from_its_first_frame() = runBlocking<Unit> {
        val offsets = atNormalVolume { (1..RUNS).map { measureOnce(leadInMs = 0) } }
        Log.i(TAG, "FIRST-FRAME offsets ms: ${offsets.describe()}")
    }

    /**
     * The common case in a session: the second reply on a track that stayed open and ran dry
     * between replies. A silent first reply opens the track, the track idles, then the measured
     * reply plays on it.
     */
    @Test
    fun a_reply_on_a_reused_track_lands_within_one_turn_engine_frame() = runBlocking<Unit> {
        val offsets = atNormalVolume { (1..RUNS).map { measureOnce(leadInMs = LEAD_IN_MS, idleTrackMs = 10_000) } }
        Log.i(TAG, "REUSED offsets ms: ${offsets.describe()}")
        val passes = offsets.count { it != null && abs(it) <= LIMIT_MS }
        Log.i(TAG, "VERDICT reused ${if (passes == RUNS) "PASS" else "FAIL"} ($passes/$RUNS within $LIMIT_MS ms)")
        assertTrue("a reply on a reused track landed more than $LIMIT_MS ms from its predicted place: $offsets", passes == RUNS)
    }

    private suspend fun <T> atNormalVolume(block: suspend () -> T): T {
        val prior = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (max * 0.7).toInt(), 0)
        try {
            return block()
        } finally {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, prior, 0)
        }
    }

    /** Detected chirp onset minus predicted, in ms; null when either is missing. */
    private suspend fun measureOnce(leadInMs: Int, idleTrackMs: Long = 0): Double? = withContext(Dispatchers.Default) {
        val config = AudioConfig()
        val mic = MicPipeline(audioManager, config)
        mic.start()
        val captured = ShortArrayBuilder()
        val reader = launch(Dispatchers.IO) { mic.frames().collect { captured.append(it) } }

        // A second of room before the reply: the floor the onset is measured against.
        delay(1_000)

        val start = AtomicReference<PlaybackEdge?>(null)
        val player = TtsPlayer(AudioTrackSinkFactory(audioManager, config), config)
        player.edgeConsumer = { edge ->
            if (edge.event == PlaybackEdge.Event.START && edge.requestId == "chirp") start.compareAndSet(null, edge)
        }
        if (idleTrackMs > 0) {
            // A silent reply opens the track; then the track runs dry while the console waits.
            player.onAudioStart(CHIRP_RATE, "opener")
            player.onChunk(ByteArray(CHIRP_RATE / 2))
            player.onAudioEnd("opener")
            val until = System.nanoTime() + (idleTrackMs + 500) * 1_000_000
            while (System.nanoTime() < until) {
                player.pollEdges()
                delay(20)
            }
        }
        player.onAudioStart(CHIRP_RATE, "chirp")
        val poller = launch {
            while (start.get() == null) {
                player.pollEdges()
                delay(5)
            }
        }
        val writer = launch(Dispatchers.IO) {
            player.onChunk(ByteArray(CHIRP_RATE * 2 * leadInMs / 1_000))
            player.onChunk(chirp())
            player.onChunk(ByteArray(CHIRP_RATE)) // half a second of silence behind it
            player.onAudioEnd("chirp")
        }
        delay(2_000)
        poller.cancelAndJoin()
        writer.cancelAndJoin()

        val edge = start.get()
        // What the console would send as the start's mic_sample (the clock is the identity here:
        // every recorded sample was sent), moved on by the reply's silent lead-in.
        val predicted = edge?.timeNs?.let { mic.recordIndexAt(it) }?.plus(leadInMs * 16.0)
        player.stopImmediately()
        reader.cancelAndJoin()
        mic.close()

        val detected = onset(captured.toArray())
        Log.i(TAG, "lead-in $leadInMs ms: predicted ${predicted?.let { "%.0f".format(it) }}, detected $detected")
        if (predicted == null || detected == null) null else (detected - predicted) / (config.sampleRateHz / 1_000.0)
    }

    /**
     * The first 5 ms window 20 dB over the room's level in the capture's first second, and over
     * -50 dBFS.
     */
    private fun onset(samples: ShortArray): Int? {
        val window = 80
        fun levelAt(i: Int): Double {
            var sum = 0.0
            for (k in i until i + window) sum += samples[k].toDouble() * samples[k]
            val rms = sqrt(sum / window) / Short.MAX_VALUE
            return 20 * log10(max(rms, 1e-9))
        }
        val roomEnd = 16_000
        if (samples.size < roomEnd + window) return null
        val room = (window until roomEnd - window step window).map(::levelAt).sorted().let { it[it.size / 2] }
        val threshold = max(room + 20, -50.0)
        var i = roomEnd
        while (i + window <= samples.size) {
            if (levelAt(i) >= threshold) return i
            i += window / 4
        }
        Log.w(TAG, "no onset: room ${"%.1f".format(room)} dBFS, threshold ${"%.1f".format(threshold)} dBFS")
        return null
    }

    /** 300 ms linear sweep 500 Hz to 4 kHz at -2 dBFS, 16-bit LE, at [CHIRP_RATE]. */
    private fun chirp(): ByteArray {
        val n = CHIRP_RATE * 3 / 10
        val out = ByteArray(n * 2)
        val f0 = 500.0
        val f1 = 4_000.0
        val duration = n.toDouble() / CHIRP_RATE
        for (i in 0 until n) {
            val t = i.toDouble() / CHIRP_RATE
            val phase = 2 * PI * (f0 * t + (f1 - f0) * t * t / (2 * duration))
            val v = (sin(phase) * 0.8 * Short.MAX_VALUE).toInt()
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun List<Double?>.describe() = joinToString { it?.let { v -> "%.1f".format(v) } ?: "none" }

    private class ShortArrayBuilder {
        private var data = ShortArray(16_000 * 8)
        private var size = 0

        @Synchronized
        fun append(frame: ByteArray) {
            val count = frame.size / 2
            if (size + count > data.size) data = data.copyOf(max(data.size * 2, size + count))
            for (k in 0 until count) {
                data[size + k] = ((frame[2 * k + 1].toInt() shl 8) or (frame[2 * k].toInt() and 0xFF)).toShort()
            }
            size += count
        }

        @Synchronized
        fun toArray(): ShortArray = data.copyOf(size)
    }

    private companion object {
        const val TAG = "MicSampleLoopback"
        const val RUNS = 5
        const val LIMIT_MS = 80.0
        const val LEAD_IN_MS = 500
        const val CHIRP_RATE = 24_000
    }
}
