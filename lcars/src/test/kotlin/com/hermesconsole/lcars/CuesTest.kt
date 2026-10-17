package com.hermesconsole.lcars

import com.hermesconsole.audio.OneShotSink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * T-20 / T-45, REQ-39: the cue set and its player live here in the LCARS kit, but every byte of
 * sound leaves through the audio module's output path - the same route and usage TTS plays through,
 * as a [OneShotSink.play] per earcon. What the platform actually does with that path (echo
 * cancellation, REQ-33) is measured on the device; what can be pinned on the JVM is that the cues
 * are real, distinct, bounded sound in REQ-39's shapes - a sequence to open listening, one beep to
 * close it - and that the player hands each one whole to the sink and opens no other way out.
 */
class CuesTest {

    // -- the cue set ---------------------------------------------------------------------------

    @Test
    fun `every cue is a short, audible, bounded sound`() {
        for (cue in LcarsCue.entries) {
            val pcm = LcarsCues.pcm(cue)
            val ms = pcm.size / 2 * 1000 / LcarsCues.SAMPLE_RATE_HZ
            assertTrue(ms in 40..400, "$cue is ${ms}ms; a cue is a beat, not an announcement")

            val peak = peakFullScale(pcm)
            // The transition earcons must carry over a spoken reply; the touch chirp only answers a
            // tap in a quiet room, so its floor is "audible in the room", not "audible over TTS".
            val floor = if (cue == LcarsCue.CONFIRM) 0.05 else 0.15
            assertTrue(peak > floor, "$cue peaks at $peak of full scale - too quiet (floor $floor)")
            assertTrue(peak <= 0.6, "$cue peaks at $peak of full scale - startling, and AEC-hostile")
        }
    }

    @Test
    fun `listening opens with a sequence and closes with a single beep`() {
        // REQ-39's own wording: "a distinct multi-note sequence when listening opens, a single
        // beep when the console stops listening". The shape is the asset - a one-note open or a
        // phrase at the close would be a different vocabulary that still passed every playback test.
        assertTrue(
            noteCount(LcarsCues.pcm(LcarsCue.LISTENING_OPEN)) >= 2,
            "the open cue must be a multi-note sequence",
        )
        assertEquals(
            1,
            noteCount(LcarsCues.pcm(LcarsCue.LISTENING_CLOSED)),
            "the close cue is one beep, not a phrase",
        )
    }

    @Test
    fun `the visit ends on the listening figure inverted`() {
        // REQ-09: the return to the main screen is an audible full stop, and it must not be heard as
        // either listening cue. The close cue's single beep is already "this turn is over", so the
        // end of a visit is a phrase - the open's climb, walked back down. Difference in PCM alone
        // would not catch a cue that merely re-used the open's rise, so the direction is the assert.
        val ended = notePitchesHz(LcarsCues.pcm(LcarsCue.SESSION_ENDED))
        assertTrue(ended.size >= 2, "the visit's full stop is a figure, not a single beep (got ${ended.size} notes)")
        assertTrue(
            ended.zipWithNext().all { (a, b) -> b < a },
            "the session-ended cue must fall throughout; heard $ended Hz",
        )
        assertTrue(
            notePitchesHz(LcarsCues.pcm(LcarsCue.LISTENING_OPEN)).zipWithNext().all { (a, b) -> b > a },
            "the open cue is the rise this one inverts",
        )
    }

    @Test
    fun `no two cues sound alike`() {
        // Distinct by REQ-39's own wording: each of the vocabulary's five voices must be
        // recognisable blind. Identical PCM would pass every playback test and still fail the
        // person listening.
        val cues = LcarsCue.entries
        for (i in cues.indices) {
            for (j in i + 1 until cues.size) {
                assertTrue(
                    !LcarsCues.pcm(cues[i]).contentEquals(LcarsCues.pcm(cues[j])),
                    "${cues[i]} and ${cues[j]} are the same sound",
                )
            }
        }
    }

    // -- the player ----------------------------------------------------------------------------

    @Test
    fun `a cue is played one-shot through the audio path, byte for byte`() {
        val sink = FakeOneShotSink()
        LcarsCuePlayer(sink).use { player ->
            player.play(LcarsCue.CONFIRM)
            awaitTrue("the confirm chirp reaches the sink") { sink.plays.isNotEmpty() }
        }
        val played = sink.plays.single()
        assertEquals(LcarsCues.SAMPLE_RATE_HZ, played.sampleRateHz, "the clip plays at the cue rate")
        assertTrue(
            LcarsCues.pcm(LcarsCue.CONFIRM).contentEquals(played.pcm),
            "what was played is the cue, whole - unresampled and untrimmed",
        )
    }

    @Test
    fun `every cue is its own one-shot, played whole and in order`() {
        // The one-shot is the fix: an earcon is loaded and played as one clip, not streamed into a
        // shared track that starves between chirps. Two queued cues are two plays, each the full
        // clip, in the order they were asked for - never chunked, never coalesced.
        val sink = FakeOneShotSink()
        LcarsCuePlayer(sink).use { player ->
            player.play(LcarsCue.LISTENING_OPEN)
            player.play(LcarsCue.ERROR)
            awaitTrue("both cues reach the sink") { sink.plays.size == 2 }
        }
        assertEquals(
            listOf(LcarsCue.LISTENING_OPEN, LcarsCue.ERROR).map { LcarsCues.pcm(it).toList() },
            sink.plays.map { it.pcm.toList() },
            "each cue is played once, whole, in the order it was queued",
        )
    }

    @Test
    fun `close closes the sink`() {
        val sink = FakeOneShotSink()
        val player = LcarsCuePlayer(sink)
        player.play(LcarsCue.LISTENING_OPEN)
        awaitTrue("the cue reaches the sink") { sink.plays.isNotEmpty() }
        player.close()
        awaitTrue("the sink is closed") { sink.closed }
    }

    // -- fakes ---------------------------------------------------------------------------------

    private class FakeOneShotSink : OneShotSink {
        data class Played(val pcm: ByteArray, val sampleRateHz: Int)

        val plays = CopyOnWriteArrayList<Played>()

        @Volatile
        var closed = false

        override fun play(pcm: ByteArray, sampleRateHz: Int) {
            // Copy: the caller owns the cue's bytes and treats them as immutable, so pin a snapshot.
            plays += Played(pcm.copyOf(), sampleRateHz)
        }

        override fun close() {
            closed = true
        }
    }

    /** The player is a worker thread by design; tests wait for it rather than reaching into it. */
    private fun awaitTrue(what: String, timeoutMs: Long = 2_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail<Unit>("timed out waiting until $what")
            Thread.sleep(10)
        }
    }

    private fun noteCount(pcm: ByteArray): Int = notePitchesHz(pcm).size

    /**
     * The pitch of each note, in order. Notes are found as audible bursts: 4 ms RMS windows against
     * a floor well under a note's level (~0.21 RMS at the bank's amplitude) and well over the
     * inter-note gaps, which are pure zeros. Pitch is then zero crossings - a pure tone at f crosses
     * zero 2f times a second, and every note in the bank is a pure tone - which is coarse, and needs
     * to be no better: what the tests ask of it is whether a figure rises or falls.
     */
    private fun notePitchesHz(pcm: ByteArray): List<Double> {
        val window = LcarsCues.SAMPLE_RATE_HZ * 4 / 1000
        val pitches = mutableListOf<Double>()
        var start = -1
        var i = 0
        while ((i + window) * 2 <= pcm.size) {
            val audible = rms(pcm, i, window) > 0.02
            if (audible && start < 0) start = i
            if (!audible && start >= 0) {
                pitches += pitchHz(pcm, start, i - start)
                start = -1
            }
            i += window
        }
        if (start >= 0) pitches += pitchHz(pcm, start, pcm.size / 2 - start)
        return pitches
    }

    private fun rms(pcm: ByteArray, from: Int, count: Int): Double {
        var sum = 0.0
        for (s in from until from + count) sum += sampleAt(pcm, s).toDouble() * sampleAt(pcm, s)
        return kotlin.math.sqrt(sum / count) / Short.MAX_VALUE
    }

    private fun pitchHz(pcm: ByteArray, from: Int, count: Int): Double {
        var crossings = 0
        for (s in from + 1 until from + count) {
            if ((sampleAt(pcm, s) >= 0) != (sampleAt(pcm, s - 1) >= 0)) crossings++
        }
        return crossings.toDouble() / 2 * LcarsCues.SAMPLE_RATE_HZ / count
    }

    private fun sampleAt(pcm: ByteArray, index: Int): Short =
        ((pcm[index * 2 + 1].toInt() shl 8) or (pcm[index * 2].toInt() and 0xFF)).toShort()

    private fun peakFullScale(pcm: ByteArray): Double {
        var peak = 0
        for (i in 0 until pcm.size / 2) {
            val sample = ((pcm[i * 2 + 1].toInt() shl 8) or (pcm[i * 2].toInt() and 0xFF)).toShort()
            if (abs(sample.toInt()) > peak) peak = abs(sample.toInt())
        }
        return peak.toDouble() / Short.MAX_VALUE
    }
}
