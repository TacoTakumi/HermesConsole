package com.hermesconsole.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * One wall console per process. A shell launch and a HOME launch can each create a MainActivity,
 * and two consoles in one process answer every wake word twice, over each other.
 */
class ConsoleSlotTest {

    private val older = Any()
    private val newer = Any()

    @Test
    fun `a claim over another owner evicts it once`() {
        val slot = ConsoleSlot()
        var evictions = 0
        slot.claim(older) { evictions++ }

        slot.claim(newer) {}

        assertEquals(1, evictions)
    }

    @Test
    fun `the new owner waits until the evicted owner has released`() = runTest {
        val slot = ConsoleSlot()
        slot.claim(older) {}
        slot.claim(newer) {}

        val waited = async { slot.awaitRetired() }
        repeat(3) { yield() }
        assertFalse(waited.isCompleted, "the older console has not closed yet")

        slot.release(older)
        waited.await()
        assertTrue(waited.isCompleted)
    }

    @Test
    fun `a release by an evicted owner leaves the new owner in place`() {
        val slot = ConsoleSlot()
        var newerEvicted = false
        slot.claim(older) {}
        slot.claim(newer) { newerEvicted = true }
        slot.release(older)

        // A third claim must evict the newer owner: it still holds the slot.
        slot.claim(Any()) {}

        assertTrue(newerEvicted)
    }

    @Test
    fun `a repeat claim by the current owner evicts nothing`() = runTest {
        val slot = ConsoleSlot()
        var evictions = 0
        slot.claim(older) { evictions++ }

        slot.claim(older) { evictions++ }

        assertEquals(0, evictions)
        slot.awaitRetired() // nothing to wait for
    }

    /** A console that records when it was closed, against the slot's own events. */
    private class FakeConsole(val events: MutableList<String>) : AutoCloseable {
        override fun close() {
            events += "closed"
        }
    }

    @Test
    fun `a release during a build waits for the cancelled build to close its console`() = runTest {
        val slot = ConsoleSlot()
        val events = mutableListOf<String>()
        val console = FakeConsole(events)
        val starting = CompletableDeferred<Unit>()
        slot.claim(older) {}

        val build = launch {
            slot.startOwned(older, console) {
                starting.complete(Unit)
                CompletableDeferred<Unit>().await() // mic.start and console.start, still running
            }
        }
        starting.await()
        slot.claim(newer) {}
        slot.release(older) // onDestroy, mid-build: nothing published to close yet
        val waited = async { slot.awaitRetired(); events += "newer starts" }
        repeat(3) { yield() }
        assertFalse(waited.isCompleted, "the half-built console is still running")

        build.cancel()
        waited.await()
        assertEquals(listOf("closed", "newer starts"), events)
    }

    @Test
    fun `a build that fails closes its console and rethrows`() = runTest {
        val slot = ConsoleSlot()
        val events = mutableListOf<String>()
        slot.claim(older) {}

        assertThrows<IllegalStateException> {
            slot.startOwned(older, FakeConsole(events)) { error("mic would not start") }
        }

        assertEquals(listOf("closed"), events)
    }

    @Test
    fun `a completed build leaves the release to the owner`() = runTest {
        val slot = ConsoleSlot()
        val events = mutableListOf<String>()
        slot.claim(older) {}
        val console = slot.startOwned(older, FakeConsole(events)) {}
        slot.claim(newer) {}

        slot.release(older)
        slot.awaitRetired()

        assertTrue(events.isEmpty(), "closing a published console is onDestroy's job")
        assertEquals(FakeConsole::class, console::class)
    }
}
