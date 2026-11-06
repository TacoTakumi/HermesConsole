package com.hermesconsole.audio

/**
 * One edge of a reply's playback at the speaker: [Event.START] when its first frame is presented,
 * [Event.STOP] when its last frame played or when it was stopped early. [timeNs] is the edge's
 * CLOCK_MONOTONIC time (null when the sink cannot say); [playedFrames] counts the reply's frames
 * that played, at [playedRateHz], which is below [declaredRateHz] when playback is decimated.
 */
data class PlaybackEdge(
    val requestId: String,
    val event: Event,
    val timeNs: Long?,
    val playedFrames: Long,
    val playedRateHz: Int,
    val declaredRateHz: Int,
) {
    enum class Event { START, STOP }
}

/**
 * The replies written to one sink, as frame spans on its written-frame line, and the edges they
 * have reached. Replies follow each other on one track, so a span starts where the previous one's
 * writes ended. Every method is called under the player's tracker lock and never during a write.
 */
internal class PlaybackEdgeTracker {

    private class Span(
        val requestId: String,
        val startFrame: Long,
        val declaredRateHz: Int,
        var endFrame: Long? = null,
        var started: Boolean = false,
    )

    private val spans = ArrayDeque<Span>()

    fun begin(requestId: String, startFrame: Long, declaredRateHz: Int) {
        spans.addLast(Span(requestId, startFrame, declaredRateHz))
    }

    fun end(requestId: String?, endFrame: Long) {
        spans.lastOrNull { it.endFrame == null && (requestId == null || it.requestId == requestId) }?.endFrame = endFrame
    }

    /** The edges [sink]'s presented position has reached since the last poll, in order. */
    fun poll(sink: AudioSink): List<PlaybackEdge> {
        val out = mutableListOf<PlaybackEdge>()
        val presented = sink.framesPresented
        while (spans.isNotEmpty()) {
            val span = spans.first()
            val end = span.endFrame
            // A reply that sent no audio never played.
            if (end != null && end == span.startFrame) {
                spans.removeFirst()
                continue
            }
            if (!span.started) {
                if (presented <= span.startFrame) break
                span.started = true
                out += span.edge(PlaybackEdge.Event.START, sink, span.startFrame)
            }
            if (end == null || presented < end) break
            out += span.edge(PlaybackEdge.Event.STOP, sink, end)
            spans.removeFirst()
        }
        return out
    }

    /**
     * Playback is being cut: every span that reached the speaker stops where the presented
     * position is now, and the rest never played. Called before the sink is stopped.
     */
    fun cut(sink: AudioSink): List<PlaybackEdge> {
        val out = poll(sink).toMutableList()
        val presented = sink.framesPresented
        for (span in spans) {
            if (presented <= span.startFrame) continue
            if (!span.started) out += span.edge(PlaybackEdge.Event.START, sink, span.startFrame)
            val stopAt = minOf(presented, span.endFrame ?: sink.framesWritten)
            out += span.edge(PlaybackEdge.Event.STOP, sink, stopAt)
        }
        spans.clear()
        return out
    }

    private fun Span.edge(event: PlaybackEdge.Event, sink: AudioSink, atFrame: Long) = PlaybackEdge(
        requestId = requestId,
        event = event,
        timeNs = sink.presentationTimeOf(atFrame),
        playedFrames = if (event == PlaybackEdge.Event.START) 0 else (atFrame - startFrame).coerceAtLeast(0),
        playedRateHz = sink.sampleRateHz,
        declaredRateHz = declaredRateHz,
    )
}
