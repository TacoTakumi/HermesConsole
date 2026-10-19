package com.hermesconsole.audio

import android.media.AudioAttributes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** The usage every console track plays with, and the switch that makes it recordable. */
class RecordablePlaybackTest {

    @Test
    fun `a new switch is off`() {
        assertFalse(RecordablePlayback().enabled)
    }

    @Test
    fun `the console plays as the assistant unless asked otherwise`() {
        assertEquals(AudioAttributes.USAGE_ASSISTANT, playbackUsage(communication = false, recordable = false))
    }

    @Test
    fun `recordable playback is media, which playback capture takes`() {
        assertEquals(AudioAttributes.USAGE_MEDIA, playbackUsage(communication = false, recordable = true))
    }

    @Test
    fun `communication mode keeps its path either way`() {
        // The hardware AEC's reference rides the communication usage; a demo switch must not move it.
        assertEquals(AudioAttributes.USAGE_VOICE_COMMUNICATION, playbackUsage(communication = true, recordable = false))
        assertEquals(AudioAttributes.USAGE_VOICE_COMMUNICATION, playbackUsage(communication = true, recordable = true))
    }
}
