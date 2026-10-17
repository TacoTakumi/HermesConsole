package com.hermesconsole.audio

import android.util.Log

/**
 * An open playback track at one fixed sample rate. Backed by AudioTrack on-device; a fake in
 * tests, which is what lets the rate rule below be checked without hardware.
 */
interface AudioSink : AutoCloseable {
    val sampleRateHz: Int
    fun write(pcm: ByteArray)

    /** Drop audio already handed to the device (barge-in). */
    fun stop()

    /**
     * Times the device ran out of audio to play on this track, if it counts them.
     *
     * A sink with no underrun accounting of its own reports none - the default is for the fakes,
     * and the one implementation that can answer is the one that matters.
     */
    val underrunCount: Int get() = 0

    /**
     * The output-path latency of this track in ms, if it can be measured (REQ-05):
     * getTimestamp-derived (CDD 5.6 C-1-1, +-2 ms). Null when the track cannot report a
     * presentation timestamp - the default for the fakes, and the signal that no measurement
     * exists yet (the AEC is simply not seeded; its own delay controller tracks from zero).
     */
    val latencyMillis: Int? get() = null
}

fun interface AudioSinkFactory {
    fun create(sampleRateHz: Int): AudioSink
}

/**
 * TTS playback (REQ-09).
 *
 * The rate is not ours to choose: it arrives on the `audio_start` frame, and it differs by engine
 * (22050 OpenVoice, 24000 VibeVoice). Assuming a constant would play every VibeVoice turn slow and
 * pitched down, so the track is rebuilt whenever the declared rate changes - and reused when it
 * doesn't, so a steady conversation doesn't churn the device.
 *
 * The output route it plays into is configuration (REQ-34) that this class never inspects: the
 * factory hands back a sink already pointed at the configured device.
 */
class TtsPlayer(
    private val sinkFactory: AudioSinkFactory,
    val config: AudioConfig = AudioConfig(),
) : AutoCloseable {

    /**
     * Volatile because barge-in comes from another thread than playback. The console is written to
     * by the TTS stream and torn down by the microphone, and the two happen at once by definition:
     * the interruption arrives while a chunk is being played.
     */
    @Volatile
    private var sink: AudioSink? = null

    /** The rate declared on the last `audio_start` (before any downsample), or null when none is open. */
    private var declaredRateHz: Int? = null

    /** How much the declared rate was decimated to build the open track. 1 = played as declared. */
    @Volatile
    private var decimation: Int = 1

    /** The rate of the open track, or null when nothing is open. This is the played rate, post-downsample. */
    val currentSampleRateHz: Int?
        get() = sink?.sampleRateHz

    /**
     * The rate of the bytes handed to [referenceConsumer] - the played rate, post-downsample
     * (REQ-03, REQ-04). The reference resampler that converts the tap to the APM's 16 kHz must be
     * built at this rate, not the declared one: a decimated track feeds the consumer at
     * declaredRate/decimation, and a resampler built at the wrong rate would mis-time the reference
     * for the AEC's delay controller. Zero when no track is open - the consumer is never called
     * then either.
     */
    val tapRateHz: Int
        get() = declaredRateHz?.div(decimation) ?: 0

    /**
     * How often the device starved this track, or null when there is no track to ask.
     *
     * Null rather than zero, because the difference is the whole point: "the mixer never ran dry"
     * and "there was nothing left to ask" are different findings, and only one of them clears the
     * starved-track suspect for a phantom barge-in (docs/aec-spike.md). Whatever the device answers
     * with is swallowed - this is a diagnostic being read on the path that has to stop the speaker,
     * and it does not get to delay that or throw on the way.
     */
    val underrunCount: Int?
        get() = sink?.let { runCatching { it.underrunCount }.getOrNull() }

    /**
     * The output-path latency of the open track in ms, if it can be measured (REQ-05):
     * getTimestamp-derived (CDD 5.6 C-1-1, +-2 ms). Null when no track is open or the track cannot
     * yet report a presentation timestamp - the signal that no measurement exists, which the AEC
     * seed reads at capture start (the AEC is simply not seeded; its own controller tracks from
     * zero).
     */
    val latencyMillis: Int?
        get() = sink?.latencyMillis

    /** An `audio_start` frame arrived declaring [sampleRateHz]. */
    fun onAudioStart(sampleRateHz: Int) {
        // Reuse the track when the declared rate repeats - the common case in a steady conversation.
        // Compared against the *declared* rate, not the sink's rate, which may have been decimated.
        if (sink != null && declaredRateHz == sampleRateHz) return
        declaredRateHz = sampleRateHz
        decimation = decimationFor(sampleRateHz)
        sink?.close()
        val playedRateHz = sampleRateHz / decimation
        sink = sinkFactory.create(playedRateHz)
        Log.i(TAG, "audio_start declared=${sampleRateHz}Hz played=${playedRateHz}Hz decimation=$decimation")
    }

    /**
     * How much to decimate a declared TTS rate before playback, to keep it off the device's native
     * output path so the AEC can reference it (see [AudioConfig.forceTtsThroughMixer]). A native-rate
     * track escapes the mixer and defeats echo cancellation; a decimated one is upsampled back through
     * it. Only high rates are touched, and only by an integer factor that divides evenly.
     */
    private fun decimationFor(sampleRateHz: Int): Int =
        if (config.forceTtsThroughMixer && sampleRateHz >= config.ttsMixerRateCeilingHz && sampleRateHz % 2 == 0) 2 else 1

    /**
     * Play a chunk of the turn - unless the turn was cancelled out from under it (REQ-04).
     *
     * A chunk with no track to play on is dropped rather than fatal, and that is barge-in, not
     * defensiveness: VoiceManager keeps sending for a moment after `tts_cancel` (it finishes the
     * chunk it is in), so the audio of an abandoned reply necessarily arrives *after* the track that
     * was playing it has been flushed and released. Playing it would be the console talking over the
     * person who just interrupted it; throwing on it would kill the stream for every turn after.
     *
     * The write can also lose the race outright - the track released between the read below and the
     * write itself - and AudioTrack answers that with an [IllegalStateException]. Same chunk, same
     * verdict, one thread later.
     *
     * Every byte written to the sink is also handed to [referenceConsumer] (REQ-03), so the AEC sees
     * the exact played PCM. The reference is fed *before* the write so the consumer is always called,
     * even if the write fails (the track was torn down mid-chunk). The consumer is fire-and-forget
     * from TtsPlayer's perspective - it never blocks playback.
     */
    fun onChunk(pcm: ByteArray) {
        val open = sink ?: return
        val out = if (decimation > 1) decimateBy2(pcm) else pcm
        referenceConsumer?.invoke(out)
        try {
            open.write(out)
        } catch (e: IllegalStateException) {
            Log.i(TAG, "dropped ${out.size} B: the track was stopped mid-write (barge-in)", e)
        }
    }

    /**
     * Halve the rate of a PCM16 mono little-endian buffer by averaging adjacent sample pairs.
     *
     * The average is a one-tap low-pass, enough anti-aliasing for dots' narrowband content (which
     * rolls off well below the new 12 kHz Nyquist). A trailing odd sample, if any, is dropped; chunks
     * are whole 20 ms frames in practice, so this does not arise on the live path.
     */
    private fun decimateBy2(pcm: ByteArray): ByteArray {
        val outSamples = (pcm.size / 2) / 2
        val out = ByteArray(outSamples * 2)
        for (i in 0 until outSamples) {
            val a = (pcm[4 * i].toInt() and 0xFF) or (pcm[4 * i + 1].toInt() shl 8)
            val b = (pcm[4 * i + 2].toInt() and 0xFF) or (pcm[4 * i + 3].toInt() shl 8)
            val avg = (a + b) / 2
            out[2 * i] = (avg and 0xFF).toByte()
            out[2 * i + 1] = ((avg shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** The turn's `audio_end` arrived. The track stays open: the next turn usually reuses the rate. */
    fun onAudioEnd() = Unit

    /**
     * The exact PCM written to the sink is also delivered here (REQ-03), so the AEC sees the played
     * reference. Set by the voice loop at construction time. Default null: no consumer, which is
     * the existing behaviour and the test-suite default.
     */
    var referenceConsumer: ((ByteArray) -> Unit)? = null

    /** Barge-in: the user spoke over us. Kill the audio now, don't drain it. */
    @Synchronized
    fun stopImmediately() {
        sink?.run { stop(); close() }
        sink = null
        declaredRateHz = null
        decimation = 1
    }

    override fun close() = stopImmediately()

    private companion object {
        const val TAG = "TtsPlayer"
    }
}
