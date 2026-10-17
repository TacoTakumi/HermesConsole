package com.hermesconsole.voiceclient

/**
 * The TTS stream, as one ordered sequence.
 *
 * The sample rate is not a property of the client, it is a property of the *turn* - VoiceManager
 * declares it in audio_start and it can change between turns (REQ-09). So it rides on the events
 * themselves rather than on a side channel: a rate published separately from its PCM can be read
 * late, and the first chunks of a new turn then play out at the previous turn's rate.
 */
sealed interface TtsEvent {

    /** A turn begins at [sampleRate]. The consumer (re)creates its output device here. */
    data class Start(val requestId: String?, val sampleRate: Int) : TtsEvent

    /**
     * A chunk of the turn's PCM, tagged with the rate it was declared under, so the consumer can
     * never play it at any other.
     */
    class Audio(val pcm: ByteArray, val sampleRate: Int) : TtsEvent

    /** The turn's audio is complete. */
    data class End(val requestId: String?) : TtsEvent
}
