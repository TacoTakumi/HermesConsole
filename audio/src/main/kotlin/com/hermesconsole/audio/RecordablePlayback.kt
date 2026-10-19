package com.hermesconsole.audio

import android.media.AudioAttributes

/**
 * Whether the console's own playback - speech, announcements and cues - can be recorded by a
 * screen capture, shared by every track it builds. Read when a track is built, so a change reaches
 * the next cue at once and speech with its next track.
 *
 * Off by default. Android's playback capture (scrcpy --audio-dup) takes only media, game and
 * unknown usages, so USAGE_ASSISTANT never reaches a recording. On, the console plays as
 * USAGE_MEDIA for a demo take.
 */
class RecordablePlayback(initial: Boolean = false) {

    @Volatile
    var enabled: Boolean = initial
}

/**
 * The usage a console track plays with. Communication mode wins: the hardware AEC needs that path
 * (see [AudioConfig.useCommunicationAudioMode]). Otherwise media when recordable, else assistant.
 */
fun playbackUsage(communication: Boolean, recordable: Boolean): Int = when {
    communication -> AudioAttributes.USAGE_VOICE_COMMUNICATION
    recordable -> AudioAttributes.USAGE_MEDIA
    else -> AudioAttributes.USAGE_ASSISTANT
}
