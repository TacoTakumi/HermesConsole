package com.hermesconsole.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack

/**
 * The device-side playback track: raw PCM16 mono out of the configured route, at the rate the
 * wire declared. Constructed per rate by [AudioTrackSinkFactory] - never reconfigured in place,
 * because AudioTrack's rate is fixed at build time.
 */
class AudioTrackSink(
    override val sampleRateHz: Int,
    preferredDevice: AudioDeviceInfo? = null,
    // USAGE_VOICE_COMMUNICATION puts playback on the comm path so the hardware AEC references it
    // (docs/aec-spike.md "The tenth"); USAGE_ASSISTANT is the pre-OTA media-path default.
    usage: Int = AudioAttributes.USAGE_ASSISTANT,
    private val gain: OutputGain = OutputGain(),
) : AudioSink {

    private val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRateHz)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
        )
        .setBufferSizeInBytes(minBufferBytes(sampleRateHz) * 4)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
        .apply {
            preferredDevice?.let { setPreferredDevice(it) }
            play()
        }

    /** Total frames written to the track since it was built, for the latency math (REQ-05). */
    private var framesWritten = 0L

    private val latencyEstimator = OutputLatencyEstimator()

    /** The gain last set on the track; a new track plays at unity until the first write. */
    private var appliedGain = 1f

    /** Blocking write: the device's own consumption rate is the backpressure. */
    override fun write(pcm: ByteArray) {
        // Read the shared gain per write, so a level change reaches a reply already playing.
        val level = gain.value
        if (level != appliedGain) {
            track.setVolume(level)
            appliedGain = level
        }
        val written = track.write(pcm, 0, pcm.size)
        // AudioTrack's byte[] write returns BYTES accepted, but the latency math and getTimestamp's
        // framePosition are both in FRAMES; 16-bit mono is 2 bytes per frame, so halve before
        // accumulating or the estimate runs 2x rich (T-12 self-barge follow-up, B-1). A short or
        // failed write contributes only what the device actually took, so the count never outruns
        // the device's presentation clock.
        if (written > 0) framesWritten += written / BYTES_PER_FRAME
    }

    /**
     * Frames the device has actually played out, and where they went.
     *
     * Diagnostics, but load-bearing ones: they are how the AEC gate proves that sound really came
     * out of the speakers. A gate that "passes" because playback silently never happened is worse
     * than a failing one.
     */
    val framesPlayed: Int get() = track.playbackHeadPosition
    val routedDeviceName: String? get() = track.routedDevice?.productName?.toString()

    /**
     * Times the mixer ran dry on this track since it was built.
     *
     * Diagnostic for the phantom barge-in: a starved MODE_STREAM track is restarted from underrun,
     * and every restart is a hard onset for the echo canceller to re-learn - the worst input there
     * is for the thing whose failure looks like somebody talking (docs/aec-spike.md).
     */
    override val underrunCount: Int get() = track.underrunCount

    /**
     * The output-path latency in ms (REQ-05, D-04): the frames still in flight to the listener
     * (written - presented) at the track's rate. getTimestamp is the CDD 5.6 C-1-1 measurement
     * (+-2 ms); it reports only once the device has presented audio, so a track that has not
     * played yet measures nothing - which is the "no measurement exists" signal the delay seed
     * reads (null: the AEC is simply not seeded; its own controller tracks from zero).
     */
    override val latencyMillis: Int?
        get() = runCatching {
            val ts = AudioTimestamp()
            if (track.getTimestamp(ts)) {
                latencyEstimator
                    .estimateLatencyMs(
                        framesWritten,
                        sampleRateHz,
                        AudioTimestampSnapshot(ts.framePosition, ts.nanoTime),
                    )
                    .toInt()
            } else {
                null
            }
        }.getOrNull()

    override fun stop() {
        // pause + flush drops what is already queued; without the flush, barge-in would still
        // play out the buffered tail of the sentence we were told to abandon.
        track.pause()
        track.flush()
    }

    override fun close() {
        track.release()
    }

    private companion object {
        /** 16-bit mono is 2 bytes per frame; write(byte[]) reports bytes, the latency math wants frames. */
        const val BYTES_PER_FRAME = 2

        fun minBufferBytes(sampleRateHz: Int): Int = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
    }
}

/**
 * Builds tracks pointed at the configured output route (REQ-34). The route is resolved here, so
 * [TtsPlayer] - and the voice loop above it - never learn where the audio actually goes.
 */
class AudioTrackSinkFactory(
    private val audioManager: AudioManager,
    private val config: AudioConfig = AudioConfig(),
    private val gain: OutputGain = OutputGain(),
) : AudioSinkFactory {

    override fun create(sampleRateHz: Int): AudioSink =
        AudioTrackSink(sampleRateHz, preferredOutputDevice(), playbackUsage(), gain)

    // Communication-path playback when the AEC needs a downlink reference (see AudioConfig).
    private fun playbackUsage(): Int =
        if (config.useCommunicationAudioMode) AudioAttributes.USAGE_VOICE_COMMUNICATION
        else AudioAttributes.USAGE_ASSISTANT

    fun availableOutputs(): List<AudioDeviceDescriptor> =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.toDescriptor() }

    // The route TTS plays into (REQ-34), resolved the one shared way so cues land on the very same
    // device - same echo-cancellation coverage, provably (see OneShotSink.preferredOutputDevice).
    private fun preferredOutputDevice(): AudioDeviceInfo? = audioManager.preferredOutputDevice(config)
}
