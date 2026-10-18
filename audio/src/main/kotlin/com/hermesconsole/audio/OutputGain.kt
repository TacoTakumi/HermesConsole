package com.hermesconsole.audio

/**
 * The console's own playback level, 0..1, shared by every track it builds - speech, announcements
 * and cues - so one setting turns them all down together without touching the device's media
 * volume. Tracks read it on each write, so a change applies to a reply already playing.
 */
class OutputGain(initial: Float = 1f) {

    @Volatile
    var value: Float = initial.coerceIn(0f, 1f)
        private set

    /** Set the level, clamped to 0..1. True when the stored value moved. */
    @Synchronized
    fun set(level: Float): Boolean {
        val clamped = level.coerceIn(0f, 1f)
        if (clamped == value) return false
        value = clamped
        return true
    }
}
