package com.hermesconsole.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class VoiceCommandTest {

    @Test
    fun `each phrase on its own is its command`() {
        assertEquals(VoiceCommand.STOP_LISTENING, voiceCommandOf("stop listening"))
        assertEquals(VoiceCommand.END_SESSION, voiceCommandOf("end session"))
        assertEquals(VoiceCommand.END_SESSION, voiceCommandOf("end the session"))
        assertEquals(VoiceCommand.END_SESSION, voiceCommandOf("goodbye"))
    }

    @Test
    fun `a computer address before and please after still match`() {
        assertEquals(VoiceCommand.STOP_LISTENING, voiceCommandOf("Computer, stop listening please."))
        assertEquals(VoiceCommand.END_SESSION, voiceCommandOf("Computer, end session please."))
        assertEquals(VoiceCommand.END_SESSION, voiceCommandOf("Computer, end the session please."))
        assertEquals(VoiceCommand.END_SESSION, voiceCommandOf("Computer, goodbye please."))
    }

    @Test
    fun `a hey computer address still matches`() {
        assertEquals(VoiceCommand.STOP_LISTENING, voiceCommandOf("Hey computer, stop listening."))
        assertEquals(VoiceCommand.END_SESSION, voiceCommandOf("hey computer goodbye"))
    }

    @Test
    fun `case punctuation and spacing do not matter`() {
        assertEquals(VoiceCommand.STOP_LISTENING, voiceCommandOf("Stop listening!"))
        assertEquals(VoiceCommand.END_SESSION, voiceCommandOf("END SESSION."))
        assertEquals(VoiceCommand.END_SESSION, voiceCommandOf("  End   the session?  "))
        assertEquals(VoiceCommand.END_SESSION, voiceCommandOf("Goodbye."))
    }

    @Test
    fun `text that only contains a phrase is not a command`() {
        assertNull(voiceCommandOf("how do I stop listening to podcasts"))
        assertNull(voiceCommandOf("goodbye to all that"))
    }

    @Test
    fun `empty and blank text is not a command`() {
        assertNull(voiceCommandOf(""))
        assertNull(voiceCommandOf("   "))
        assertNull(voiceCommandOf("computer please"))
    }
}
