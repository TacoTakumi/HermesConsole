package com.hermesconsole.app

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicLong
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * T-59: the barge-in record has to outlive the log buffer it used to live in.
 *
 * Measured on the SM-X210 on 2026-08-05: logcat's `main` ring is 5 MiB, which on this device is
 * about 90 minutes, and it cannot be enlarged - `logcat -G 16M` clamps to 5 MiB and
 * `setprop persist.logd.size` is denied. The phantom self-barge-in of 08-04 20:39 was therefore
 * unreadable by the next morning, which is the precise case T-58's instrumentation was built for.
 *
 * So the journal is exercised the way the requirement means it: over a real Preferences DataStore
 * file, killed, and reopened cold. What a *second* journal reads back is what a human standing at
 * the wall after an unattended night actually gets. An in-memory fake would pass while persisting
 * nothing.
 */
class BargeInJournalTest {

    @TempDir
    lateinit var dir: File

    /** A journal over the given scope, so a test can kill it - DataStore refuses two on one file. */
    private fun journalIn(scope: CoroutineScope): BargeInJournal =
        BargeInJournal(
            PreferenceDataStoreFactory.create(scope = scope) {
                File(dir, "barge_ins.preferences_pb")
            },
        )

    private val phantom = PersistedBargeIn(
        atMillis = 1_754_340_000_000L,
        levelDbfs = -33.5,
        converged = true,
        exposureMillis = 12_400,
        playbackUnderruns = 4,
        runId = "run-abc",
    )

    @Test
    fun `a journal reopened over the same file reads what the last one wrote`() = runBlocking {
        // The whole point, and the thing logcat could not do: survive the process dying. cancelAndJoin,
        // not cancel - DataStore frees its claim on the file when the scope's job completes.
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        journalIn(scope).record(phantom)
        scope.coroutineContext[Job]!!.cancelAndJoin()

        val reopened = CoroutineScope(Job() + Dispatchers.IO)
        try {
            assertEquals(listOf(phantom), journalIn(reopened).recent())
        } finally {
            reopened.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    @Test
    fun `the ring keeps the most recent entries newest first`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        try {
            val journal = journalIn(scope)
            (1..BargeInJournal.CAPACITY + 2).forEach { n ->
                journal.record(phantom.copy(atMillis = n.toLong(), runId = "run-$n"))
            }

            val recent = journal.recent()
            assertEquals(BargeInJournal.CAPACITY, recent.size)
            // Newest first, and the two oldest have fallen off the end.
            assertEquals(
                (BargeInJournal.CAPACITY + 2 downTo 3).map { "run-$it" },
                recent.map { it.runId },
            )
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    @Test
    fun `a phantom overnight is not overwritten by a real interruption the next morning`() = runBlocking {
        // The failure this task exists to prevent. One entry would have kept only the 09:00 barge-in
        // and thrown away the 03:00 one - and the 03:00 one is the whole investigation.
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        try {
            val journal = journalIn(scope)
            journal.record(phantom.copy(atMillis = 3_000, runId = "overnight-phantom"))
            journal.record(phantom.copy(atMillis = 9_000, runId = "morning-person"))

            assertTrue(journal.recent().any { it.runId == "overnight-phantom" })
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    @Test
    fun `no track means no underrun count, and null survives the round trip as null`() = runBlocking {
        // Zero and "there was nothing left to ask" are different findings, and only one of them
        // clears the starved-track suspect. A store that flattened null to 0 would fabricate
        // evidence for the fix we are trying to choose between.
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        try {
            val journal = journalIn(scope)
            journal.record(phantom.copy(playbackUnderruns = null))
            assertNull(journal.recent().single().playbackUnderruns)

            journal.record(phantom.copy(playbackUnderruns = 0))
            assertEquals(0, journal.recent().first().playbackUnderruns)
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    @Test
    fun `a fresh journal has nothing to report`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        try {
            assertTrue(journalIn(scope).recent().isEmpty())
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    @Test
    fun `a recorded barge-in carries no credential`() = runBlocking {
        // REQ-28. The journal is read off the device by a human with adb, so it is a place a key
        // could escape to - and the type is where that is fenced, not each call site.
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        try {
            journalIn(scope).record(phantom)
            val file = File(dir, "barge_ins.preferences_pb").readBytes().decodeToString()
            assertFalse(file.contains("key", ignoreCase = true), "no key material in the journal file")
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    @Test
    fun `the loop's barge-ins are journalled as they happen, and the empty start is not one`() = runBlocking {
        // The seam is the flow, not the console: VoiceLoop must not learn about persistence (it has
        // no business holding a DataStore), so the composition root subscribes and writes. Tested
        // here rather than through WallConsole because this is the whole of the behaviour.
        val recorded = mutableListOf<PersistedBargeIn>()
        val source = MutableStateFlow<BargeInLog?>(null)
        val clock = AtomicLong(1_000)
        val scope = CoroutineScope(Job() + Dispatchers.IO)

        try {
            scope.journalBargeIns(source, now = { clock.get() }) { recorded += it }
            withTimeout(2_000) {
                while (scope.coroutineContext[Job]!!.children.none()) yield()
            }

            // One at a time, and awaited: the source is a StateFlow and therefore conflated, so two
            // barge-ins closer together than the writer drains would collapse into one. Real ones
            // are a whole turn apart, but the test says which behaviour it is relying on rather
            // than racing and hoping.
            source.value = BargeInLog(speechAtMillis = 1, runId = "run-1", levelDbfs = -20.0)
            withTimeout(2_000) { while (recorded.isEmpty()) yield() }

            clock.set(2_000)
            source.value = BargeInLog(speechAtMillis = 2, runId = "run-2", levelDbfs = -30.0)
            withTimeout(2_000) { while (recorded.size < 2) yield() }
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }

        // Two barge-ins, two entries - and the null the flow starts at is not a barge-in.
        assertEquals(listOf("run-1", "run-2"), recorded.map { it.runId })
        assertEquals(listOf(1_000L, 2_000L), recorded.map { it.atMillis }, "each stamped when it landed")
        assertEquals(-20.0, recorded.first().levelDbfs)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `one barge-in updated in flight is journalled once, not once per update`() = runTest {
        // Measured on the wall, 2026-08-05: a single barge-in left two entries 72 ms apart,
        // identical in every persisted field. VoiceLoop.onBargeIn publishes the log three times -
        // the decision, then a copy carrying tts_cancel's stamp, then one carrying Hermes' stop -
        // and each is a distinct value the collector cannot tell from a fresh interruption. At two
        // or three slots per event a ring of five holds two barge-ins, so the 09:00 person evicts
        // the 03:00 phantom, which is the single eviction CAPACITY was chosen to prevent.
        val recorded = mutableListOf<PersistedBargeIn>()
        val source = MutableStateFlow<BargeInLog?>(null)
        val clock = AtomicLong(1_000)
        // Unconfined: every value is delivered as it is assigned, so conflation cannot be what
        // hides the duplicate. The bug reproduces here whether or not the device got lucky.
        val scope = CoroutineScope(Job() + UnconfinedTestDispatcher(testScheduler))

        try {
            scope.journalBargeIns(source, now = { clock.get() }) { recorded += it }

            val decision = BargeInLog(speechAtMillis = 7, runId = "run-1", levelDbfs = -20.0)
            source.value = decision
            clock.set(2_000)
            source.value = decision.copy(ttsCancelledAtMillis = 11)
            clock.set(3_000)
            source.value = decision.copy(ttsCancelledAtMillis = 11, runStoppedAtMillis = 31)

            // A genuinely separate interruption still earns its own slot.
            clock.set(4_000)
            source.value = BargeInLog(speechAtMillis = 900, runId = "run-2", levelDbfs = -30.0)
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }

        assertEquals(listOf("run-1", "run-2"), recorded.map { it.runId })
        assertEquals(
            listOf(1_000L, 4_000L),
            recorded.map { it.atMillis },
            "stamped when the barge-in landed, not when its last stop came back",
        )
    }

    @Test
    fun `a barge-in log becomes an entry stamped with the wall clock`() {
        // BargeInLog's own timestamps come from the loop's injected clock, which in production is
        // elapsed-time-ish and means nothing to a person reading a server log. The entry carries
        // wall-clock millis so the console's record and VoiceManager's can be laid side by side -
        // which is exactly the join that sighting three needed and did not have.
        val log = BargeInLog(
            speechAtMillis = 42,
            runId = "run-abc",
            requestId = "req-1",
            levelDbfs = -33.5,
            converged = true,
            exposureMillis = 12_400,
            playbackUnderruns = 4,
        )

        assertEquals(phantom, log.toEntry(atMillis = 1_754_340_000_000L))
    }
}
