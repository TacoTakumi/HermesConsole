package com.hermesconsole.app

import com.hermesconsole.hermesclient.HermesMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * T-43 / REQ-23: entering a session decides what the conversation pane opens showing.
 *
 * The pane's opening content is a claim about *which* session was entered and *when*: a session
 * with a past opens on that past, a session created a moment ago opens empty, and a fetch still
 * in flight for a session already left must never land on the wall of the one entered after it.
 * The fetch itself is [com.hermesconsole.hermesclient.HermesClient.sessionMessages]'s business,
 * filtering included - here it is a port, so every one of those claims is provable on the JVM.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionBacklogTest {

    private val aLines = listOf(
        HermesMessage("user", "the codeword is TANGO51"),
        HermesMessage("assistant", "OK."),
    )
    private val bLines = listOf(
        HermesMessage("user", "what codeword was given?"),
        HermesMessage("assistant", "NONE"),
    )

    /** The gateway's stored histories, as the fetch port sees them - gateable, failable. */
    private class StoredHistories {
        val histories = mutableMapOf<String, List<HermesMessage>>()
        val fetched = mutableListOf<String>()
        var failure: Exception? = null
        private val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

        /** Make [sessionId]'s next fetch hang until [open] - a slow gateway, on command. */
        fun gate(sessionId: String) {
            gates[sessionId] = CompletableDeferred()
        }

        fun open(sessionId: String) {
            gates.remove(sessionId)?.complete(Unit)
        }

        suspend fun fetch(sessionId: String): List<HermesMessage> {
            fetched += sessionId
            gates[sessionId]?.await()
            failure?.let { throw it }
            return histories[sessionId].orEmpty()
        }
    }

    /** The gateway's create endpoint, as the factory port sees it - countable, gateable. */
    private class CreatedSessions {
        var created = 0
        private var gate: CompletableDeferred<Unit>? = null

        /** Make the next create hang until [open] - a slow gateway, on command. */
        fun gate() {
            gate = CompletableDeferred()
        }

        fun open() {
            gate?.complete(Unit)
            gate = null
        }

        suspend fun create(): String {
            created++
            gate?.await()
            return "made-$created"
        }
    }

    private val stored = StoredHistories()
    private val factory = CreatedSessions()
    private var cleared = 0

    private fun TestScope.backlog() = SessionBacklog(
        fetch = stored::fetch,
        clearWall = { cleared++ },
        scope = backgroundScope,
    )

    @Test
    fun `a MEDIA line is never shown as history text, and the history goes to the wall restore`() = runTest {
        val history = listOf(
            HermesMessage("user", "Show me a picture."),
            HermesMessage("assistant", "Here it is.\n\nMEDIA:https://x/y.png"),
            HermesMessage("user", "Again."),
            HermesMessage("assistant", "MEDIA:https://x/z.png"),
        )
        stored.histories["a"] = history
        val restored = mutableListOf<List<HermesMessage>>()
        val pane = SessionBacklog(fetch = stored::fetch, restoreWall = { restored += it }, scope = backgroundScope)

        pane.select("a")
        runCurrent()

        assertEquals(
            listOf(
                HermesMessage("user", "Show me a picture."),
                HermesMessage("assistant", "Here it is.\n\n"),
                HermesMessage("user", "Again."),
            ),
            pane.backlog.value,
            "the tag line goes, and a line that was only a tag goes with it",
        )
        assertEquals(listOf(history), restored, "the restore sees the stored history whole")
    }

    @Test
    fun `entering a session restores its stored spoken history`() = runTest {
        stored.histories["a"] = aLines
        val pane = backlog()
        assertNull(pane.backlog.value, "before any entry, the attract state owns the wall")

        pane.select("a")
        runCurrent()

        assertEquals("a", pane.current.value)
        assertEquals(aLines, pane.backlog.value)
        assertEquals(1, cleared, "entry replaces the wall's content - the stale turn must be told")
    }

    @Test
    fun `a freshly created session opens to an empty pane, and nothing is fetched for it`() = runTest {
        stored.histories["a"] = aLines
        val pane = backlog()
        pane.select("a")
        runCurrent()

        pane.opened("fresh")
        runCurrent()

        assertEquals("fresh", pane.current.value)
        assertEquals(emptyList<HermesMessage>(), pane.backlog.value)
        assertEquals(listOf("a"), stored.fetched, "a session created a moment ago has no past to fetch")
    }

    @Test
    fun `a fetch for a session already left never lands on the next session's wall`() = runTest {
        stored.histories["a"] = aLines
        stored.histories["b"] = bLines
        stored.gate("a")
        val pane = backlog()

        pane.select("a")
        runCurrent()
        pane.select("b")
        runCurrent()
        assertEquals(bLines, pane.backlog.value)

        stored.open("a")
        runCurrent()

        assertEquals(bLines, pane.backlog.value, "A's slow history must not overwrite B's wall")
        assertEquals("b", pane.current.value)
    }

    @Test
    fun `a failed fetch leaves the entered session's pane empty, never the last session's lines`() = runTest {
        stored.histories["a"] = aLines
        val pane = backlog()
        pane.select("a")
        runCurrent()
        assertEquals(aLines, pane.backlog.value)

        stored.failure = IOException("gateway down")
        pane.select("b")
        runCurrent()

        assertEquals(emptyList<HermesMessage>(), pane.backlog.value, "A's lines must not stand in for B's")
        assertEquals("b", pane.current.value, "the entry stands; only the history is missing")
    }

    @Test
    fun `with no session entered, currentOrCreate makes one and enters it`() = runTest {
        val pane = backlog()

        val id = pane.currentOrCreate(factory::create)
        runCurrent()

        assertEquals("made-1", id)
        assertEquals("made-1", pane.current.value)
        assertEquals(emptyList<HermesMessage>(), pane.backlog.value, "a session made this moment has no past")
        assertEquals(1, cleared, "creation enters the session - the wall must be told")
    }

    @Test
    fun `a later call returns the session already made, and the factory is not asked again`() = runTest {
        val pane = backlog()
        val first = pane.currentOrCreate(factory::create)

        val second = pane.currentOrCreate(factory::create)

        assertEquals(first, second)
        assertEquals(1, factory.created, "the visit's session exists now; later turns reuse it")
    }

    @Test
    fun `with a session entered, currentOrCreate returns it and creates nothing`() = runTest {
        stored.histories["a"] = aLines
        val pane = backlog()
        pane.select("a")
        runCurrent()

        assertEquals("a", pane.currentOrCreate(factory::create))
        assertEquals(0, factory.created, "the entered session is the scope; there is nothing to make")
    }

    @Test
    fun `selecting a stored session restores its lines and scopes the turns that follow to it`() = runTest {
        // REQ-13 (T-05), read as the one scenario it is: the ready screen's tap on a stored
        // conversation both fills the pane with its past and aims every later dispatch at it -
        // the provider finds the selection and has nothing to create.
        stored.histories["a"] = aLines
        val pane = backlog()

        pane.select("a")
        runCurrent()

        assertEquals(aLines, pane.backlog.value, "the pane holds the session's N stored lines")
        assertEquals("a", pane.currentOrCreate(factory::create), "the next dispatch carries the selection")
        assertEquals("a", pane.currentOrCreate(factory::create), "and so does every turn after it")
        assertEquals(0, factory.created, "a selected session is the scope; nothing is made")
    }

    @Test
    fun `a wake with nothing entered opens an empty pane and makes no session`() = runTest {
        // T-06, REQ-04/REQ-05: the loop leaving READY is the visit beginning - the pane opens
        // now, empty, before the speaker has finished the utterance. The session itself waits
        // for the first dispatch: a wake that never becomes one leaves the store untouched.
        val pane = backlog()

        pane.woken()
        runCurrent()

        assertEquals(emptyList<HermesMessage>(), pane.backlog.value, "the pane opens on the wake itself")
        assertNull(pane.current.value, "the visit's session waits for the first dispatch")
        assertEquals(0, factory.created, "a wake that never dispatches makes nothing")
        assertEquals(emptyList<String>(), stored.fetched, "an empty pane has no past to fetch")
        assertEquals(0, cleared, "no session was entered; there is nothing on the wall to clear")
    }

    @Test
    fun `a wake mid-session leaves the entered session's pane alone`() = runTest {
        // The composition root reports every departure from READY; with a session entered, that
        // departure is a turn in an open conversation, not a visit beginning - nothing moves.
        stored.histories["a"] = aLines
        val pane = backlog()
        pane.select("a")
        runCurrent()

        pane.woken()
        runCurrent()

        assertEquals(aLines, pane.backlog.value, "the entered session's restored lines hold the wall")
        assertEquals("a", pane.current.value, "the entered session stays the next turn's scope")
    }

    @Test
    fun `the visit ending puts the attract state back on the wall`() = runTest {
        // T-08, REQ-07: idleness in a session is the visit over, and both halves of entry go with
        // it. The pane returns to null, not empty - empty is a session standing open with nothing
        // in it, which is precisely the state a wake word opens (T-06) and the opposite of this.
        stored.histories["a"] = aLines
        val pane = backlog()
        pane.select("a")
        runCurrent()

        pane.ended()
        runCurrent()

        assertNull(pane.current.value, "the left session is no longer where the next turn lands")
        assertNull(pane.backlog.value, "the attract state owns the wall again, not an empty pane")
    }

    @Test
    fun `the visit ending takes the conversation off the wall, not only out of the store`() = runTest {
        // T-14, REQ-07: "the ready screen is shown" is a condition across two owners - this store's
        // pane AND the loop's turn log - so a return that empties only the store hands back a wall
        // still rendering the last visitor's exchange, over an attract state that believes it is
        // showing. This is the M-02 wall run's one failing exit item, in the shape it failed in.
        stored.histories["a"] = aLines
        val pane = backlog()
        pane.select("a")
        runCurrent()
        assertEquals(1, cleared, "entry still replaces the wall's content, exactly as before")

        pane.ended()
        runCurrent()

        assertEquals(2, cleared, "and the return hands it back: the loop's turn goes with the pane")
    }

    @Test
    fun `a pane opened by a wake that made nothing is taken off the wall by the return too`() = runTest {
        // REQ-14: the visit that never produced a session still put someone's words on the wall -
        // the loop's live turn is exactly where they are, since there is no back-log to hold them.
        val pane = backlog()
        pane.woken()
        runCurrent()

        pane.ended()
        runCurrent()

        assertNull(pane.backlog.value, "the attract state owns the wall again")
        assertEquals(1, cleared, "and the words that never became a session leave with it")
        assertEquals(0, factory.created, "nothing was ever created; nothing is created on the way out")
    }

    @Test
    fun `a visit after the idle return gets its own session, not the one that ended`() = runTest {
        // T-11, REQ-11: the wall is shared, and the whole point of the return is that the next
        // person to speak to it starts somewhere clean. Two visits, two sessions - and the second
        // visit's turns must all land in the second, never trailing back into the first.
        val pane = backlog()
        val first = pane.currentOrCreate(factory::create)
        val firstAgain = pane.currentOrCreate(factory::create)

        pane.ended()
        runCurrent()

        val second = pane.currentOrCreate(factory::create)
        val secondAgain = pane.currentOrCreate(factory::create)

        assertEquals(first, firstAgain, "one visit, one session - every turn of it lands together")
        assertEquals(second, secondAgain, "and the same holds for the visit after the return")
        assertNotEquals(first, second, "the returned-to wall must not hand the next visit the last one's session")
        assertEquals(2, factory.created, "two visits are two creates: the id is remade, not remembered")
    }

    @Test
    fun `two concurrent callers get one session, not two`() = runTest {
        factory.gate()
        val pane = backlog()

        val ids = mutableListOf<String>()
        launch { ids += pane.currentOrCreate(factory::create) }
        launch { ids += pane.currentOrCreate(factory::create) }
        runCurrent()

        factory.open()
        runCurrent()

        assertEquals(listOf("made-1", "made-1"), ids)
        assertEquals(1, factory.created, "one visit, one session - however the dispatches race")
    }
}
