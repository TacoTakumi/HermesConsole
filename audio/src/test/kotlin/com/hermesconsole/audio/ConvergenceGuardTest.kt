package com.hermesconsole.audio

import kotlin.math.PI
import kotlin.math.sin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The convergence guard (T-32, REQ-05/REQ-04).
 *
 * A cold echo canceller leaks the tablet's own voice - measured on the SM-X210 at up to -15 dBFS,
 * which is human-speech loud (docs/aec-spike.md). Without this guard the console interrupts itself
 * on its first sentence after every boot. The guard suppresses the *action*, not the detection, and
 * only for as long as the canceller is cold.
 */
class ConvergenceGuardTest {

    private fun tone(amplitude: Double, samples: Int = 320): ByteArray {
        val bytes = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val v = (amplitude * Short.MAX_VALUE * sin(2 * PI * 440 * i / 16_000)).toInt().toShort()
            bytes[i * 2] = (v.toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v.toInt() shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private fun silence(samples: Int = 320) = ByteArray(samples * 2)

    /** Frames of playback exposure, at 20 ms each: what actually converges the canceller. */
    private fun ConvergenceGuard.expose(ms: Long) {
        repeat((ms / 20).toInt()) { accept(silence(), playing = true) }
    }

    @Test
    fun `a fresh capture session starts cold`() {
        assertFalse(ConvergenceGuard().isConverged)
    }

    @Test
    fun `the canceller converges on playback exposure, not on wall-clock time`() {
        // An open microphone in a silent room teaches the canceller nothing: it has no echo to
        // model until the console speaks. Hours of quiet listening leave it exactly as cold.
        val guard = ConvergenceGuard()
        repeat(10_000) { guard.accept(silence(), playing = false) } // 200 s of quiet capture

        assertFalse(guard.isConverged, "quiet capture must not be mistaken for convergence")
    }

    @Test
    fun `the guard lifts once the window of playback has been heard`() {
        val guard = ConvergenceGuard()
        guard.expose(ConvergenceGuard.DEFAULT_WINDOW_MS - 20)
        assertFalse(guard.isConverged, "still inside the convergence window")

        guard.accept(silence(), playing = true)
        assertTrue(guard.isConverged, "the window has been fully exposed to echo")
    }

    @Test
    fun `convergence latches for the life of the capture session`() {
        val guard = ConvergenceGuard()
        guard.expose(ConvergenceGuard.DEFAULT_WINDOW_MS)
        assertTrue(guard.isConverged)

        repeat(10_000) { guard.accept(silence(), playing = false) } // a long quiet stretch
        assertTrue(guard.isConverged, "a converged canceller does not go cold while the session lives")
    }

    @Test
    fun `restarting capture cools the canceller again`() {
        // The AEC's model belongs to the record session. A route change, a service restart, or a
        // recovered mic hands us a new session that has learned nothing - and leaks exactly as the
        // first one did.
        val guard = ConvergenceGuard()
        guard.expose(ConvergenceGuard.DEFAULT_WINDOW_MS)
        assertTrue(guard.isConverged)

        guard.onCaptureStarted()
        assertFalse(guard.isConverged, "a new record session starts cold")
    }

    @Test
    fun `the console does not barge in on its own leaking echo while the canceller is cold`() {
        // The leak, in miniature: speech-loud energy on the capture stream while the console is
        // the only thing making a sound. The gate sees it; the console must not act on it.
        val detector = BargeInDetector()
        val leak = tone(0.5) // well above the -35 dBFS gate, as the measured leak was

        assertTrue(SpeechGate().let { g -> (1..5).map { g.accept(leak) }.last() }, "this is gate-tripping loud")
        assertFalse(
            (1..500).any { detector.accept(leak, playing = true) },
            "barge-in fired inside the convergence window: the console interrupted itself",
        )
    }

    @Test
    fun `barge-in fires on a person once the canceller has converged - in the same turn`() {
        // The guard is scoped to the window, not to the turn. A person who speaks over the tail of
        // a long answer is still heard.
        val detector = BargeInDetector()
        val speech = tone(0.5)

        detector.accept(silence(), playing = true) // one frame of playback to start the clock
        repeat((ConvergenceGuard.DEFAULT_WINDOW_MS / 20).toInt()) {
            detector.accept(silence(), playing = true) // the console talking, cancelled to silence
        }

        val fired = (1..SpeechGate.DEFAULT_CONSECUTIVE_FRAMES).map { detector.accept(speech, playing = true) }
        assertTrue(fired.any { it }, "a converged canceller must not suppress a real barge-in")
    }

    @Test
    fun `the detector reports what tripped it - the level, and the canceller's state when it did`() {
        // What the guard suppressed and what the gate measured are two different facts, and the
        // phantom is only classifiable if the log carries both: a trip at -12 dBFS on a canceller
        // with 10 s of exposure is a person, the same trip at 100 ms of exposure is the leak.
        val detector = BargeInDetector(guard = ConvergenceGuard(windowMs = 100))
        val speech = tone(0.5)

        detector.accept(silence(), playing = true) // 20 ms of exposure: still cold
        assertFalse(detector.trigger().converged)
        assertEquals(20L, detector.trigger().exposureMillis)

        repeat(4) { detector.accept(silence(), playing = true) } // 100 ms: converged
        val fired = (1..SpeechGate.DEFAULT_CONSECUTIVE_FRAMES).map { detector.accept(speech, playing = true) }
        assertTrue(fired.last(), "the gate must actually have fired for the trigger to describe one")

        val trigger = detector.trigger()
        assertEquals(rmsDbfs(speech), trigger.levelDbfs, 0.001, "the level on the frame that fired")
        assertTrue(trigger.converged)
        assertEquals(100L, trigger.exposureMillis, "exposure stops being counted once it has done its job")
    }

    @Test
    fun `the window covers the leak as measured, twice over`() {
        // The leak's last detection was at 5.4 s (docs/aec-spike.md). The window is double that:
        // enough margin to be insurance, little enough that the first answer after a restart is
        // not left uninterruptible for half a minute.
        assertEquals(10_000L, ConvergenceGuard.DEFAULT_WINDOW_MS)
    }

    @Test
    fun `a reply onset re-arms a converged canceller`() {
        // The playback track is rebuilt before every reply, so a canceller warm on the last reply
        // meets a fresh reference on the next and leaks its onset (docs/aec-spike.md, "The fifth and
        // sixth sightings"). The guard must go cold again at that onset, not stay latched from before.
        val guard = ConvergenceGuard()
        guard.expose(ConvergenceGuard.DEFAULT_WINDOW_MS)
        assertTrue(guard.isConverged)

        guard.onPlaybackStarted()
        assertFalse(guard.isConverged, "a new reply's playback meets a fresh reference: re-arm")
    }

    @Test
    fun `a re-arm re-trusts on the short window, not the full boot window`() {
        // A warm canceller is re-adapting a reference it already half-knows, so the re-arm costs the
        // short window, not the ten-second cold-boot one.
        val guard = ConvergenceGuard()
        guard.expose(ConvergenceGuard.DEFAULT_WINDOW_MS)
        guard.onPlaybackStarted()

        guard.expose(ConvergenceGuard.DEFAULT_REARM_WINDOW_MS - 20)
        assertFalse(guard.isConverged, "still inside the short re-arm window")
        guard.accept(silence(), playing = true)
        assertTrue(guard.isConverged, "the re-arm window, not the boot window, re-trusts the canceller")
    }

    @Test
    fun `a reply onset before the first convergence does not restart the cold-boot window`() {
        // Before the canceller has ever converged, exposure is the cold-boot window accumulating.
        // The first reply's own onset must not reset it to the shorter re-arm window - the boot
        // leak needs the full ten seconds of cover.
        val guard = ConvergenceGuard()
        guard.expose(ConvergenceGuard.DEFAULT_WINDOW_MS / 2)
        guard.onPlaybackStarted() // no-op: nothing has converged yet
        guard.expose(ConvergenceGuard.DEFAULT_WINDOW_MS / 2)

        assertTrue(guard.isConverged, "the cold-boot window must accumulate across the first reply's onset")
    }

    @Test
    fun `the console does not barge in on a reply's onset leak after a re-arm`() {
        // The phantom's exact shape: warm canceller, new reply, leak at the onset. The re-arm must
        // suppress it for the length of the onset window even though the guard was converged a
        // moment ago.
        val detector = BargeInDetector()
        detector.accept(silence(), playing = true)
        repeat((ConvergenceGuard.DEFAULT_WINDOW_MS / 20).toInt()) { detector.accept(silence(), playing = true) }

        detector.onPlaybackStarted() // the next reply begins
        val leak = tone(0.5) // gate-tripping loud, as the measured leak was
        val onsetFrames = (ConvergenceGuard.DEFAULT_REARM_WINDOW_MS / 20).toInt() - 5
        assertFalse(
            (1..onsetFrames).any { detector.accept(leak, playing = true) },
            "barge-in fired on the reply's onset leak: the re-arm did not cover it",
        )
    }

    @Test
    fun `barge-in fires on a person once past the re-arm window of a new reply`() {
        // The re-arm buys back interruptibility: past the short onset window, a person speaking over
        // the reply is heard again.
        val detector = BargeInDetector()
        detector.accept(silence(), playing = true)
        repeat((ConvergenceGuard.DEFAULT_WINDOW_MS / 20).toInt()) { detector.accept(silence(), playing = true) }

        detector.onPlaybackStarted()
        repeat((ConvergenceGuard.DEFAULT_REARM_WINDOW_MS / 20).toInt()) { detector.accept(silence(), playing = true) }

        val speech = tone(0.5)
        val fired = (1..SpeechGate.DEFAULT_CONSECUTIVE_FRAMES).map { detector.accept(speech, playing = true) }
        assertTrue(fired.any { it }, "past the re-arm window a real interruption must still be heard")
    }

    @Test
    fun `the re-arm window covers the warm onset leak and no more`() {
        // Warm onset leaks measured at 1.3-1.7 s (docs/aec-spike.md). 2 s covers them; every second
        // beyond is REQ-04 off at the top of every reply, so it is kept as tight as the leak allows.
        // A 4 s trial was reverted: exposure freezes at the window once converged, so a logged
        // exposure did not mean the leak had outlasted 2 s (docs/aec-spike.md, "The seventh sighting").
        assertEquals(2_000L, ConvergenceGuard.DEFAULT_REARM_WINDOW_MS)
    }

    @Test
    fun `a cold canceller holds speech back while the speaker is audible`() {
        // The guard exists because the speaker's voice leaks back through a cold canceller. With
        // the speaker audible, a silent-speaker check that answers no changes nothing.
        val detector = BargeInDetector()
        val speech = tone(0.5)

        assertFalse(
            (1..50).any { detector.accept(speech, playing = true, speakerSilent = { false }) },
            "a cold canceller with the speaker audible must still hold barge-in back",
        )
    }

    @Test
    fun `a cold canceller does not hold speech back while the speaker is silent`() {
        // No sound from the speaker, no echo for the canceller to leak: the guard only adds delay.
        // The person is heard from the first word, cold canceller or not.
        val detector = BargeInDetector()
        val speech = tone(0.5)

        val fired = (1..SpeechGate.DEFAULT_CONSECUTIVE_FRAMES).map {
            detector.accept(speech, playing = true, speakerSilent = { true })
        }
        assertTrue(fired.last(), "with the speaker silent, the first sustained speech must be acted on")
        assertFalse(detector.trigger().converged, "the bypass does not pretend the canceller converged")
        assertEquals(
            SpeechGate.DEFAULT_CONSECUTIVE_FRAMES * 20L,
            detector.trigger().exposureMillis,
            "the guard keeps counting exposure while it is bypassed",
        )
    }

    @Test
    fun `the silent-speaker check runs only on speech the guard would hold back`() {
        // The check reads the device volume - a binder call - and the frame loop runs at 50 Hz. It
        // is asked only when there is speech to act on and the guard alone stands in the way.
        val detector = BargeInDetector()
        var asked = 0
        val silent = { asked++; true }

        repeat(100) { detector.accept(silence(), playing = true, speakerSilent = silent) } // 2 s: cold
        assertEquals(0, asked, "no speech, nothing to act on: the check must not run")

        repeat(SpeechGate.DEFAULT_CONSECUTIVE_FRAMES) { detector.accept(tone(0.5), playing = true, speakerSilent = silent) }
        assertEquals(1, asked, "speech on a cold canceller is what the check is for")

        repeat((ConvergenceGuard.DEFAULT_WINDOW_MS / 20).toInt()) {
            detector.accept(silence(), playing = true, speakerSilent = silent)
        }
        assertTrue(detector.isConverged)
        val before = asked
        repeat(SpeechGate.DEFAULT_CONSECUTIVE_FRAMES) { detector.accept(tone(0.5), playing = true, speakerSilent = silent) }
        assertEquals(before, asked, "a converged canceller needs no bypass: the check must not run")
    }
}
