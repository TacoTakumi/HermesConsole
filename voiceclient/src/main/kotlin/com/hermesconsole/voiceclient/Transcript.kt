package com.hermesconsole.voiceclient

/**
 * One utterance segment as VoiceManager currently understands it.
 *
 * [index] is the identity, not the position: VoiceManager may revise a segment it has already sent,
 * and it says so by reusing the index. Text is therefore replaced, never accumulated.
 */
data class TranscriptSegment(
    val index: Int,
    val text: String,
    /** VoiceManager will not revise this segment again. */
    val isFinal: Boolean,
    /** VoiceManager thinks the turn ended here - provisionally, unless [isFinal]. */
    val endpointed: Boolean,
    /**
     * The conversation turn this utterance belongs to - the turn open when its audio was received.
     * 0 on a connection that never opened a turn, so it is always readable (D-18).
     */
    val turnId: Int = 0,
)

/**
 * What the speaker has said this turn, as far as VoiceManager knows right now.
 *
 * Held as segments keyed by index rather than as a string, so a reopened turn can rewrite what it
 * already said instead of appending a duplicate (REQ-07).
 */
data class Transcript(val segments: List<TranscriptSegment> = emptyList()) {

    /** The whole turn, rendered. */
    val text: String get() = segments.joinToString(" ") { it.text }.trim()

    /** Every segment has settled - VoiceManager will not revise this turn again. */
    val isFinal: Boolean get() = segments.isNotEmpty() && segments.all { it.isFinal }

    /**
     * VoiceManager has called the end of the turn. Provisional while [isFinal] is false: the
     * console may act on it, but must be ready for the turn to reopen (D-08).
     */
    val endpointed: Boolean get() = segments.lastOrNull()?.endpointed == true

    /**
     * Apply an update. A segment whose index we have already seen is *replaced* - that is the
     * endpoint-hold's whole contract. A new index is inserted in index order.
     */
    internal fun upsert(segment: TranscriptSegment): Transcript {
        val revised = segments.filterNot { it.index == segment.index } + segment
        return Transcript(revised.sortedBy { it.index })
    }
}
