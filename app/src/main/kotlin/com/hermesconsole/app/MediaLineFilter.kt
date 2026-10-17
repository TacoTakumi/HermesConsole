package com.hermesconsole.app

/**
 * Takes Hermes's `MEDIA:<target>` lines out of a streaming reply.
 *
 * Hermes's own media convention puts a `MEDIA:` tag on a line of its own, and a model sometimes
 * uses it instead of `render_artifact`. Spoken, the line reads a URL to the room; shown, it is a
 * raw tag. This filter sits between the deltas and both: it passes every other character through
 * as it arrives, holds back at most the six characters of a line that could still turn out to be
 * `MEDIA:`, and hands each tag's target out separately for the artifact router to show.
 *
 * One instance per run. Pure and not thread-safe - the loop feeds it from the one coroutine that
 * collects the run's events.
 */
internal class MediaLineFilter {

    /** What one step lets through: the text to show and speak, and the targets of finished tags. */
    data class Output(val text: String, val media: List<String>)

    private val held = StringBuilder()
    private val target = StringBuilder()
    private var atLineStart = true
    private var inTag = false

    fun feed(delta: String): Output {
        val text = StringBuilder()
        val media = mutableListOf<String>()
        for (c in delta) step(c, text, media)
        return Output(text.toString(), media)
    }

    /** The run is over: release a held line start, and finish a tag the stream ended inside. */
    fun finish(): Output {
        val out = if (inTag) {
            Output("", listOfNotNull(target.toString().trim().ifEmpty { null }))
        } else {
            Output(held.toString(), emptyList())
        }
        held.clear()
        target.clear()
        inTag = false
        atLineStart = true
        return out
    }

    private fun step(c: Char, text: StringBuilder, media: MutableList<String>) {
        when {
            inTag -> if (c == '\n') {
                target.toString().trim().ifEmpty { null }?.let(media::add)
                target.clear()
                inTag = false
                atLineStart = true
            } else {
                target.append(c)
            }

            atLineStart -> {
                if (c == '\n') {
                    text.append(held).append(c)
                    held.clear()
                    return
                }
                held.append(c)
                val probe = held.trimStart(' ', '\t')
                when {
                    probe.isEmpty() -> Unit
                    probe.length < TAG.length && TAG.startsWith(probe) -> Unit
                    probe.toString() == TAG -> {
                        held.clear()
                        inTag = true
                    }
                    else -> {
                        text.append(held)
                        held.clear()
                        atLineStart = false
                    }
                }
            }

            else -> {
                text.append(c)
                if (c == '\n') atLineStart = true
            }
        }
    }

    private companion object {
        const val TAG = "MEDIA:"
    }
}

/** A whole stored text through the filter at once: its text without MEDIA: lines, and their targets. */
internal fun splitMediaLines(text: String): MediaLineFilter.Output {
    val filter = MediaLineFilter()
    val body = filter.feed(text)
    val tail = filter.finish()
    return MediaLineFilter.Output(body.text + tail.text, body.media + tail.media)
}
