package com.hermesconsole.audio

/**
 * Runtime configuration for the audio path.
 *
 * The capture format is the one the VoiceManager wire expects: raw 16 kHz mono 16-bit LE PCM.
 * [inputSource] is deliberately a value, not a constant (REQ-03): pointing the console at a
 * USB mic is a config change, and the voice loop - which only ever sees [AudioCapture] - never
 * learns that it happened.
 */
data class AudioConfig(
    val sampleRateHz: Int = 16_000,
    val channelCount: Int = 1,
    val bitsPerSample: Int = 16,
    /** 20 ms of audio: 320 frames x 2 bytes. The chunk the capture stream is emitted in. */
    val chunkBytes: Int = 640,
    val inputSource: InputSource = InputSource.BUILTIN_MIC,
    val outputRoute: OutputRoute = OutputRoute.BUILTIN_SPEAKER,
    /**
     * Platform noise suppression on the capture session.
     *
     * VOICE_COMMUNICATION brings its own, and it is aggressive: measured on the SM-X210, room tone
     * on this path has a mean of -105 dBFS - non-speech is gated to near-digital-zero. That is good
     * for a phone call and questionable for a keyword spotter, whose word begins with a low-energy
     * plosive that a gate is happy to throw away.
     *
     * Separate from the echo canceller on purpose. The AEC is what barge-in rests on (REQ-05) and is
     * never negotiable; this is not.
     */
    val noiseSuppression: Boolean = true,
    /**
     * DISABLED by default: a tested and refuted hypothesis, kept as a toggle, not a fix.
     *
     * The idea was that a 48 kHz AudioTrack (audiocpp's native-rate output) took a direct output path
     * that bypassed the mixer and the AcousticEchoCanceler's reference tap, while lower-rate engines
     * (OpenVoice 22050, VibeVoice 24000) were resampled through the mixer and cancelled. When true,
     * a declared rate at or above [ttsMixerRateCeilingHz] is halved before the track, forcing it back
     * through the mixer.
     *
     * The A/B refuted it (docs/aec-spike.md, "The ninth: the sample-rate bypass, refuted"): with the
     * downsample engaged (48000 -> 24000, confirmed in the log), the console still self-barged at the
     * same level (-24 to -27 dBFS) as at native 48 kHz. Playing at exactly VibeVoice's 24 kHz through
     * the mixer did not restore cancellation, so the sample rate is not the cause. The differentiator
     * left is the audio content - the dots DC offset and onset transient - which is a source-side fix
     * (VoiceManager onset shaping), not this. Left in place, default off, so the negative result is
     * legible and the toggle is there if a future device genuinely does have a native-rate bypass.
     */
    val forceTtsThroughMixer: Boolean = false,
    /** At or above this declared TTS rate, halve before playback (see [forceTtsThroughMixer]). */
    val ttsMixerRateCeilingHz: Int = 32_000,
    /**
     * Run the audio path in communication mode so the hardware AEC gets a downlink reference.
     *
     * The SM-X210's hardware AcousticEchoCanceler stopped cancelling after the ZEF -> ZGC OTA
     * (docs/aec-spike.md, "The tenth: the OTA broke the AEC"): self-check reports enabled but echo
     * return loss is ~0, so the console barges in on its own TTS regardless of engine, rate, or
     * content. The likely cause is that the canceller, bound to the VOICE_COMMUNICATION capture path,
     * only receives a reference when the *playback* is on the communication path too - and ours plays
     * USAGE_ASSISTANT in MODE_NORMAL, which the post-OTA platform may no longer feed it.
     *
     * When true, playback uses USAGE_VOICE_COMMUNICATION and the app sets MODE_IN_COMMUNICATION while
     * capturing, the textbook configuration for the hardware canceller. This is the "phone call"
     * audio mode and changes volume-stream and routing behaviour, so it is a toggle.
     *
     * DEFAULT OFF: tested 2026-08-16 and it did NOT restore cancellation - the leak stayed at -25 dBFS
     * and the console still self-barged (docs/aec-spike.md, "The tenth"). Reverted to the pre-OTA
     * media-path config. Kept as a toggle only because the reference-path idea is sound in principle
     * and a future OS might feed it correctly. The live mitigation is instead the half-duplex stopgap
     * (VoiceLoop.bargeInEnabled=false) until software AEC lands.
     */
    val useCommunicationAudioMode: Boolean = false,
) {
    /** What a byte count of captured audio is worth in time. */
    val bytesPerSecond: Int get() = sampleRateHz * channelCount * (bitsPerSample / 8)
}
