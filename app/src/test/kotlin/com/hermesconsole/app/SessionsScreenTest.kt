package com.hermesconsole.app

import com.hermesconsole.hermesclient.HermesSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The sessions list offers stored conversations and a fresh one - and nothing else.
 *
 * Written as T-04 / REQ-02 of wall-session-scoping for the ready screen; the list moved to its
 * own full-screen pane (demo-readines-ui T-09, REQ-02) and every claim rode along unchanged.
 * The unscoped row is gone with the mode it offered, and the claim is structural, not cosmetic:
 * the row list is decided off-screen in [sessionRows], so what the wall renders is exactly what
 * these tests hold - one create row, one row per stored session, and every session row leading
 * to a scoped conversation.
 */
class SessionsScreenTest {

    private val stored = listOf(
        HermesSession(id = "s-1", title = "engineering log", messageCount = 6, preview = "warp core status"),
        HermesSession(id = "s-2", title = null, messageCount = 0),
    )

    @Test
    fun `one new-session row plus one row per stored session - nothing else`() {
        val rows = sessionRows(stored, currentSessionId = null)

        assertEquals(stored.size + 1, rows.size, "no row exists that is neither create nor a stored session")
        assertNull(rows.first().sessionId, "the first row creates - it enters no existing session")
        assertEquals(listOf("s-1", "s-2"), rows.drop(1).map { it.sessionId })
    }

    @Test
    fun `an empty gateway still offers the one move that always exists - a fresh conversation`() {
        val rows = sessionRows(emptyList(), currentSessionId = null)

        assertEquals(1, rows.size)
        assertNull(rows.single().sessionId)
    }

    @Test
    fun `every row past the first enters a scoped conversation`() {
        val rows = sessionRows(stored, currentSessionId = null)

        assertTrue(rows.drop(1).all { it.sessionId != null }, "a session row with no id would be unscoped by another name")
    }

    @Test
    fun `the entered session's row says the console is speaking there`() {
        val rows = sessionRows(stored, currentSessionId = "s-1")

        val current = rows.single { it.sessionId == "s-1" }
        assertTrue(current.current)
        assertTrue(current.detail.startsWith("speaking here"), "the row must say so, not just colour itself")
        assertTrue(rows.filterNot { it.sessionId == "s-1" }.none { it.current })
    }

    // -- T-11 / REQ-11: rows are named for people - a raw id never renders ----------------------

    @Test
    fun `a titled session shows its title, an untitled one a date-time label - never the raw id`() {
        val spoken = HermesSession(id = "api_9f3", title = null, messageCount = 4, lastActive = EPOCH)

        assertEquals("engineering log", sessionDisplayName(stored[0], UTC), "a set title wins")
        assertEquals("Aug 1 2026 09:30", sessionDisplayName(spoken, UTC), "an untitled row is named by when it was last alive")
    }

    @Test
    fun `an untitled session never spoken in still gets words, not its id`() {
        val fresh = HermesSession(id = "run_abc", title = null, messageCount = 0, lastActive = null)

        assertEquals("untitled session", sessionDisplayName(fresh, UTC))
    }

    @Test
    fun `no row label ever equals the session id, whatever the fixture's shape`() {
        val awkward = listOf(
            HermesSession(id = "s-1", title = "engineering log", messageCount = 6),
            HermesSession(id = "s-2", title = null, messageCount = 0, lastActive = EPOCH),
            HermesSession(id = "s-3", title = "", messageCount = 1),
            HermesSession(id = "s-4", title = "   ", messageCount = 1),
        )

        val rows = sessionRows(awkward, currentSessionId = null)

        rows.drop(1).forEachIndexed { i, row ->
            assertTrue(row.label != awkward[i].id, "row ${awkward[i].id} leaked its id as a label: ${row.label}")
            assertTrue(row.label.isNotBlank(), "a row must still be readable")
        }
    }

    @Test
    fun `the screen's rows use the display-name function - one naming rule, everywhere`() {
        val spoken = HermesSession(id = "api_9f3", title = null, messageCount = 4, lastActive = EPOCH)

        val rows = sessionRows(listOf(spoken), currentSessionId = null, zone = UTC)

        assertEquals(sessionDisplayName(spoken, UTC), rows[1].label)
    }

    // -- T-12 / REQ-14: console-made sessions are born titled -----------------------------------

    @Test
    fun `a console-made title is the creation moment, in the rows' own label shape`() {
        val moment = java.time.LocalDateTime.of(2026, 8, 1, 9, 30)

        assertEquals("Aug 1 2026 09:30", sessionCreationTitle(moment))

        // One format, deliberately: a session titled at creation and an untitled one labeled by
        // its last activity at the same moment read identically on the list.
        val untitled = HermesSession(id = "api_x", title = null, messageCount = 0, lastActive = EPOCH)
        assertEquals(sessionDisplayName(untitled, UTC), sessionCreationTitle(java.time.LocalDateTime.of(2026, 8, 1, 9, 30)))
    }

    private companion object {
        val UTC: java.time.ZoneId = java.time.ZoneOffset.UTC

        /** 2026-08-01T09:30:00Z, as the gateway's epoch-seconds ordering key carries it. */
        val EPOCH: Double = java.time.LocalDateTime.of(2026, 8, 1, 9, 30)
            .toInstant(java.time.ZoneOffset.UTC).epochSecond.toDouble()
    }
}
