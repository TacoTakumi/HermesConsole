package com.hermesconsole.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MediaLineFilterTest {

    /** Feed [deltas] in order, then finish; the text and targets the whole stream let through. */
    private fun run(vararg deltas: String): Pair<String, List<String>> {
        val filter = MediaLineFilter()
        val text = StringBuilder()
        val media = mutableListOf<String>()
        (deltas.map(filter::feed) + filter.finish()).forEach {
            text.append(it.text)
            media += it.media
        }
        return text.toString() to media
    }

    @Test
    fun `text with no tag passes through unchanged`() {
        assertEquals("The warp core is stable.\nMars is red." to emptyList<String>(), run("The warp core ", "is stable.\nMars is red."))
    }

    @Test
    fun `a tag line is removed and its target handed out`() {
        val (text, media) = run("Here is the picture.\n\nMEDIA:https://example.com/a.jpg\nEnjoy.")

        assertEquals("Here is the picture.\n\nEnjoy.", text)
        assertEquals(listOf("https://example.com/a.jpg"), media)
    }

    @Test
    fun `a tag split across deltas is still caught`() {
        val (text, media) = run("Here.\nME", "DI", "A:https://exa", "mple.com/a.jpg")

        assertEquals("Here.\n", text)
        assertEquals(listOf("https://example.com/a.jpg"), media)
    }

    @Test
    fun `a line that only starts like the tag is released whole`() {
        assertEquals("MEDIUM rare.\nME" to emptyList<String>(), run("ME", "DIUM rare.\nME"))
    }

    @Test
    fun `MEDIA in the middle of a line is not a tag`() {
        assertEquals("See MEDIA:x here." to emptyList<String>(), run("See MEDIA:x here."))
    }

    @Test
    fun `an indented tag is caught and its indent dropped`() {
        assertEquals("A\n" to listOf("/tmp/b.png"), run("A\n  MEDIA: /tmp/b.png"))
    }

    @Test
    fun `text is released as soon as a line cannot be a tag`() {
        val filter = MediaLineFilter()

        assertEquals("", filter.feed("M").text, "M could still start a tag")
        assertEquals("Mars", filter.feed("ars").text)
    }
}
