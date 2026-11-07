package com.hermesconsole.app

/** A spoken phrase the console acts on itself instead of sending it to Hermes as a turn. */
internal enum class VoiceCommand { STOP_LISTENING, END_SESSION }

private val PUNCTUATION = Regex("[^\\p{L}\\p{N}\\s]")
private val WHITESPACE = Regex("\\s+")

private val PHRASES = mapOf(
    listOf("stop", "listening") to VoiceCommand.STOP_LISTENING,
    listOf("end", "session") to VoiceCommand.END_SESSION,
    listOf("end", "the", "session") to VoiceCommand.END_SESSION,
    listOf("goodbye") to VoiceCommand.END_SESSION,
)

/**
 * The command [text] is, or null. Only the whole turn counts: case and punctuation are ignored,
 * and an optional leading "computer" or "hey computer" and a trailing "please" are dropped.
 */
internal fun voiceCommandOf(text: String): VoiceCommand? {
    var words = PUNCTUATION.replace(text.lowercase(), "").trim().split(WHITESPACE)
    words = when {
        words.take(2) == listOf("hey", "computer") -> words.drop(2)
        words.firstOrNull() == "computer" -> words.drop(1)
        else -> words
    }
    if (words.lastOrNull() == "please") words = words.dropLast(1)
    return PHRASES[words]
}
