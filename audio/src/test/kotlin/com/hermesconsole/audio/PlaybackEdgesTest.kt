package com.hermesconsole.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A reply's playback edges, as a turn engine needs them: one start when its first frame is
 * presented, one stop when its last frame played or when it is stopped early, with the frames that
 * really played. Driven through [TtsPlayer] over a sink whose presented position the test sets.
 */
class PlaybackEdgesTest {

    /** Presents frames at 1 ms per 24 frames, so frame f is presented at f / 24 kHz seconds. */
    private class ClockedSink(override val sampleRateHz: Int) : AudioSink {
        override var framesWritten = 0L
        var presented = 0L
        override val framesPresented: Long get() = presented
        override fun presentationTimeOf(framePosition: Long): Long = framePosition * 1_000_000_000L / sampleRateHz
        override fun write(pcm: ByteArray) { framesWritten += pcm.size / 2 }
        override fun stop() = Unit
        override fun close() = Unit
    }

    private val sinks = mutableListOf<ClockedSink>()
    private val edges = mutableListOf<PlaybackEdge>()
    private val player = TtsPlayer({ rate -> ClockedSink(rate).also { sinks += it } }).also { p ->
        p.edgeConsumer = { edges += it }
    }
    private val sink get() = sinks.last()

    private fun frames(n: Int) = ByteArray(n * 2)

    @Test
    fun `a reply played to the end gives one start then one stop with every frame`() {
        player.onAudioStart(24_000, "r-1")
        player.onChunk(frames(2_400))
        player.pollEdges()
        assertTrue(edges.isEmpty(), "nothing presented yet")

        sink.presented = 1
        player.pollEdges()
        player.onChunk(frames(2_400))
        player.onAudioEnd("r-1")
        player.pollEdges()
        sink.presented = 4_800
        player.pollEdges()
        player.pollEdges()

        assertEquals(
            listOf(
                PlaybackEdge("r-1", PlaybackEdge.Event.START, timeNs = 0, playedFrames = 0, playedRateHz = 24_000, declaredRateHz = 24_000),
                PlaybackEdge("r-1", PlaybackEdge.Event.STOP, timeNs = 200_000_000, playedFrames = 4_800, playedRateHz = 24_000, declaredRateHz = 24_000),
            ),
            edges,
        )
    }

    @Test
    fun `a reply stopped mid-play gives one start and one stop with what played`() {
        player.onAudioStart(24_000, "r-1")
        player.onChunk(frames(4_800))
        sink.presented = 1_200
        player.pollEdges()
        player.stopImmediately()
        player.pollEdges()

        assertEquals(listOf(PlaybackEdge.Event.START, PlaybackEdge.Event.STOP), edges.map { it.event })
        assertEquals(1_200L, edges.last().playedFrames)
        assertEquals(50_000_000L, edges.last().timeNs)
    }

    @Test
    fun `a reply stopped before its first frame was presented gives neither edge`() {
        player.onAudioStart(24_000, "r-1")
        player.onChunk(frames(4_800))
        player.stopImmediately()
        player.pollEdges()
        assertTrue(edges.isEmpty())
    }

    @Test
    fun `a reply that started between polls still gets its start before its stop`() {
        player.onAudioStart(24_000, "r-1")
        player.onChunk(frames(4_800))
        sink.presented = 600
        player.stopImmediately()
        assertEquals(listOf(PlaybackEdge.Event.START, PlaybackEdge.Event.STOP), edges.map { it.event })
        assertEquals(600L, edges.last().playedFrames)
    }

    @Test
    fun `back to back replies on one track each get their own span`() {
        player.onAudioStart(24_000, "r-1")
        player.onChunk(frames(2_400))
        player.onAudioEnd("r-1")
        player.onAudioStart(24_000, "r-2")
        player.onChunk(frames(2_400))
        player.onAudioEnd("r-2")
        sink.presented = 4_800
        player.pollEdges()

        assertEquals(
            listOf("r-1" to PlaybackEdge.Event.START, "r-1" to PlaybackEdge.Event.STOP, "r-2" to PlaybackEdge.Event.START, "r-2" to PlaybackEdge.Event.STOP),
            edges.map { it.requestId to it.event },
        )
        assertEquals(100_000_000L, edges[2].timeNs, "r-2 starts where r-1 ended")
    }

    @Test
    fun `a halved reply reports its played rate and its declared rate`() {
        val halving = TtsPlayer({ rate -> ClockedSink(rate).also { sinks += it } }, AudioConfig(forceTtsThroughMixer = true))
        halving.edgeConsumer = { edges += it }
        halving.onAudioStart(48_000, "r-1")
        halving.onChunk(frames(4_800))
        halving.onAudioEnd("r-1")
        sink.presented = 2_400
        halving.pollEdges()

        val stop = edges.last()
        assertEquals(2_400L, stop.playedFrames)
        assertEquals(24_000, stop.playedRateHz)
        assertEquals(48_000, stop.declaredRateHz)
        assertEquals(4_800L, replySamples(stop.playedFrames, stop.playedRateHz, stop.declaredRateHz))
    }

    @Test
    fun `a reply with no request id is not tracked`() {
        player.onAudioStart(24_000)
        player.onChunk(frames(2_400))
        sink.presented = 2_400
        player.onAudioEnd()
        player.pollEdges()
        assertTrue(edges.isEmpty())
    }

    @Test
    fun `a cut on another thread cannot deliver a stop before the start being delivered`() {
        val inStart = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val seen = java.util.Collections.synchronizedList(mutableListOf<PlaybackEdge.Event>())
        player.edgeConsumer = { edge ->
            if (edge.event == PlaybackEdge.Event.START) {
                inStart.countDown()
                release.await(2, java.util.concurrent.TimeUnit.SECONDS)
            }
            seen += edge.event
        }
        player.onAudioStart(24_000, "r-1")
        player.onChunk(frames(4_800))
        sink.presented = 1_200

        val poller = Thread { player.pollEdges() }.apply { start() }
        assertTrue(inStart.await(2, java.util.concurrent.TimeUnit.SECONDS))
        val cutter = Thread { player.stopImmediately() }.apply { start() }
        cutter.join(300)
        release.countDown()
        poller.join(2_000)
        cutter.join(2_000)

        assertEquals(listOf(PlaybackEdge.Event.START, PlaybackEdge.Event.STOP), seen.toList())
    }
}
