package com.hermesconsole.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * REQ-35's first clause, pinned at the mapping: each of the four states must be a *visually
 * distinct* indication. Distinct means distinct - four colours no two of which are the same, and
 * four labels likewise - and the mapping is total, so a fifth state added later cannot silently
 * render as one of these four.
 */
class StateIndicatorTest {

    @Test
    fun `each state has its own colour`() {
        val colours = ConsoleState.entries.map { it.lcarsColor }
        assertEquals(colours.size, colours.toSet().size, "two states share a colour: $colours")
    }

    @Test
    fun `each state has its own label`() {
        val labels = ConsoleState.entries.map { it.lcarsLabel }
        assertEquals(labels.size, labels.toSet().size, "two states share a label: $labels")
        labels.forEach { assertTrue(it.isNotBlank(), "a state renders as a blank label") }
    }

    @Test
    fun `labels read as the state they indicate`() {
        // The label is the state's own name: a person reading "LISTENING" and a log reading
        // ConsoleState.LISTENING are looking at the same word, so the screenshot evidence and the
        // state machine can never drift apart.
        ConsoleState.entries.forEach { state ->
            assertEquals(state.name, state.lcarsLabel.uppercase())
        }
    }
}
