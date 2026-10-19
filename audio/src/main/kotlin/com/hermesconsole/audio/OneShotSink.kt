package com.hermesconsole.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/**
 * One-shot playback of a short PCM16-mono clip, start to finish - the model a UI earcon needs,
 * unlike [AudioSink]'s open stream.
 *
 * The console's earcons are 45-250 ms one-shots. Streamed into one long-lived [AudioSink] they
 * starve it: between taps the track runs dry and the mixer disables it (measured on the SM-X210 -
 * `prepareTracks_l BUFFER TIMEOUT: remove track ... due to underrun`), so the next tap has to
 * restart a disabled track, and the restart transient swallows the whole clip - the chirp is heard
 * only when the restart happens to be quick. A one-shot has no stream to starve: the clip is loaded
 * whole and played to its end.
 *
 * A fake in tests; [AudioTrackOneShotSink] on-device - same output route and usage as [TtsPlayer]'s
 * track, so an earcon is echo-cancelled exactly as TTS is (REQ-33) and can no more make the console
 * hear itself than its own voice can.
 */
interface OneShotSink : AutoCloseable {

    /** Play [pcm] (PCM16 mono at [sampleRateHz]) once, to completion, on the configured route. */
    fun play(pcm: ByteArray, sampleRateHz: Int)

    /** Nothing is held between plays, so the default is a no-op; a device impl may override. */
    override fun close() = Unit
}

/**
 * The device-side one-shot: a MODE_STATIC [AudioTrack] built per clip, loaded with the whole PCM,
 * played to the end, then released.
 *
 * MODE_STATIC rather than MODE_STREAM is the fix: the clip is resident before playback begins, so
 * there is nothing to underrun and no disabled-track restart to eat a 45 ms earcon (the failure the
 * streaming path showed on the SM-X210). Route and usage are resolved the one shared way
 * ([preferredOutputDevice]) so an earcon and a spoken reply cannot land on different routes - which
 * would mean different echo-cancellation coverage, and is the whole reason cues never took a
 * low-latency side path like SoundPool (REQ-33/REQ-34).
 */
class AudioTrackOneShotSink(
    private val audioManager: AudioManager,
    private val config: AudioConfig = AudioConfig(),
    private val gain: OutputGain = OutputGain(),
    private val recordable: RecordablePlayback = RecordablePlayback(),
) : OneShotSink {

    override fun play(pcm: ByteArray, sampleRateHz: Int) {
        if (pcm.isEmpty()) return
        val recordable = recordable.enabled
        // A screen recording's playback capture runs behind the speaker: releasing the track when
        // its head reaches the end cut the last 120-150 ms of every cue from the recording (and
        // from what the recording host plays), while the speaker had all of it. Trailing silence
        // gives the capture time to catch up, so the release cuts only silence. Recordable only,
        // so the speaker path is exactly the clip.
        val clip = if (recordable) pcm + ByteArray(2 * (sampleRateHz * RECORDABLE_TAIL_MS / 1000)) else pcm
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // Never the communication path: cues stayed on the assistant usage through
                    // that toggle's test, so only the recordable switch moves them.
                    .setUsage(playbackUsage(communication = false, recordable = recordable))
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
            // Exactly the clip: a MODE_STATIC buffer is the sample, not a streaming window.
            .setBufferSizeInBytes(clip.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            audioManager.preferredOutputDevice(config)?.let(track::setPreferredDevice)
            // A track per clip, so the shared gain is read fresh for every play.
            track.setVolume(gain.value)
            // Load the whole clip, then play it as one unit - no second write, nothing to run dry.
            track.write(clip, 0, clip.size)
            track.play()
            awaitPlayout(track, frames = clip.size / 2, sampleRateHz = sampleRateHz)
        } finally {
            // Release only after playout: releasing a still-playing MODE_STATIC track cuts the clip.
            track.release()
        }
    }

    /**
     * Hold the caller until the clip has played out, so [AudioTrack.release] does not cut it short.
     * Bounded: a cold output path can take a beat before the head starts advancing, and a path that
     * never starts must not wedge the cue worker, so the wait is capped at the clip plus a margin.
     */
    private fun awaitPlayout(track: AudioTrack, frames: Int, sampleRateHz: Int) {
        val clipMs = frames * 1_000L / sampleRateHz
        val ceilingAt = System.currentTimeMillis() + clipMs + PLAYOUT_MARGIN_MS
        while (track.playbackHeadPosition < frames && System.currentTimeMillis() < ceilingAt) {
            Thread.sleep(POLL_MS)
        }
    }

    private companion object {
        const val PLAYOUT_MARGIN_MS = 250L
        const val POLL_MS = 5L
        const val RECORDABLE_TAIL_MS = 250
    }
}

/**
 * The configured output device (REQ-34), resolved the one way for every track the console builds -
 * TTS and cues alike - so a spoken reply and an earcon cannot end up on different routes, and thus
 * under different echo-cancellation coverage. Null means "let the platform route it".
 */
internal fun AudioManager.preferredOutputDevice(config: AudioConfig): AudioDeviceInfo? {
    val devices = getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    val selected = selectOutputDevice(config.outputRoute, devices.map { it.toDescriptor() }) ?: return null
    return devices.firstOrNull { it.id == selected.id }
}
