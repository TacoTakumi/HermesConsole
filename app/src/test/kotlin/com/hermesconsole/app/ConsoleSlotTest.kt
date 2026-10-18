package com.hermesconsole.app

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
}
