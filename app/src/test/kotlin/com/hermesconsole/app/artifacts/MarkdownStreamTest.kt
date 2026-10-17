package com.hermesconsole.app.artifacts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T-26 / REQ-16, REQ-18: markdown renders as structure, and it renders *incrementally*.
 *
 * The contract under test is [MarkdownStream]'s two promises. First, identity: as `message.delta`
 * tokens append to a reply, a block that has been sealed is done - the same instance shows up in
 * every later snapshot, so nothing downstream ever re-renders it (REQ-18's "not reparsed per
 * token", made observable with [assertSame]). Second, convergence: feeding the text token by token
 * ends at exactly the parse of the completed text - one chunk or a thousand, same blocks, same
 * ids. The renderer is a pure function of the block list, so block equality here *is* rendered
 * equality on the wall.
 */
class MarkdownStreamTest {

    @Test
    fun `completed blocks keep instance identity across successive render snapshots`() {
        val stream = MarkdownStream()
        data class Snap(val blocks: List<MdBlock>, val sealedCount: Int)
        val snaps = ArrayList<Snap>()
        var fed = ""
        DOCUMENT.chunked(3).forEach { token ->
            fed += token
            snaps.add(Snap(stream.sync(fed), stream.sealedCount))
        }

        val last = snaps.last()
        assertEquals(9, last.sealedCount, "every block but the trailing paragraph must have sealed")

        var sealedSoFar = 0
        snaps.forEach { snap ->
            assertTrue(snap.sealedCount >= sealedSoFar, "a sealed block must never come unsealed")
            sealedSoFar = snap.sealedCount
            repeat(snap.sealedCount) { i ->
                assertSame(
                    snap.blocks[i], last.blocks[i],
                    "sealed block $i must be the same instance in every later snapshot",
                )
            }
        }
    }

    @Test
    fun `streaming token by token converges on the parse of the completed text`() {
        val batch = MarkdownStream().sync(DOCUMENT)
        listOf(1, 3, 7).forEach { size ->
            val stream = MarkdownStream()
            var fed = ""
            var streamed: List<MdBlock> = emptyList()
            DOCUMENT.chunked(size).forEach { token ->
                fed += token
                streamed = stream.sync(fed)
            }
            assertEquals(batch, streamed, "chunk size $size must converge on the batch parse")
        }
    }

    @Test
    fun `headings lists code blocks and tables come out as their own shapes`() {
        assertEquals(
            listOf(
                MdHeading(0, 1, "Warp core diagnostic"),
                MdParagraph(1, "The plasma injector assembly reports **nominal** flow across all manifolds."),
                MdHeading(2, 2, "Injector readings"),
                MdTable(
                    3,
                    header = listOf("Manifold", "Flow", "Status"),
                    rows = listOf(
                        listOf("Port", "99.7%", "nominal"),
                        listOf("Starboard", "99.2%", "nominal"),
                    ),
                ),
                MdList(
                    4,
                    listOf(
                        MdListItem(0, null, "intermix ratio stable"),
                        MdListItem(0, null, "coolant loop pressure within tolerance"),
                        MdListItem(1, null, "primary loop nominal"),
                        MdListItem(0, null, "no microfractures detected"),
                    ),
                ),
                MdCode(5, "kotlin", "val core = \"STABLE\"\n\n# not a heading, just code"),
                MdList(6, listOf(MdListItem(0, 1, "seal the injectors"), MdListItem(0, 2, "recheck the matrix"))),
                MdQuote(7, "Engineering signs off on the duty cycle."),
                MdRule(8),
                MdParagraph(9, "End of report."),
            ),
            MarkdownStream().sync(DOCUMENT),
        )
    }

    @Test
    fun `a loose list stays one list across the blank lines between its items`() {
        val stream = MarkdownStream()
        stream.sync("- alpha\n\n- beta\n")
        assertEquals(0, stream.sealedCount, "an open list must not seal at a blank line - the next item may continue it")

        val blocks = stream.sync("- alpha\n\n- beta\n\n- gamma\n\nEpilogue.\n")
        assertEquals(
            listOf(
                MdList(0, listOf(MdListItem(0, null, "alpha"), MdListItem(0, null, "beta"), MdListItem(0, null, "gamma"))),
                MdParagraph(1, "Epilogue."),
            ),
            blocks,
        )
        assertEquals(1, stream.sealedCount, "the flush-left paragraph is what finally seals the list")
    }

    @Test
    fun `a setext underline promotes the paragraph it closes into a heading`() {
        assertEquals(
            listOf(MdHeading(0, 1, "Warp core"), MdHeading(1, 2, "Diagnostic")),
            MarkdownStream().sync("Warp core\n===\n\nDiagnostic\n---\n"),
        )
    }

    @Test
    fun `a text that is not an append of what came before resets the stream`() {
        val stream = MarkdownStream()
        stream.sync("# One reply\n\nWith a paragraph.\n")
        val blocks = stream.sync("A different reply entirely.")
        assertEquals(listOf<MdBlock>(MdParagraph(0, "A different reply entirely.")), blocks)
        assertEquals(0, stream.sealedCount, "a superseded reply starts the stream over")
    }

    @Test
    fun `the unterminated tail still renders and matches the batch parse`() {
        val doc = "```kotlin\nval x = 1"
        val stream = MarkdownStream()
        var fed = ""
        var streamed: List<MdBlock> = emptyList()
        doc.chunked(1).forEach { token ->
            fed += token
            streamed = stream.sync(fed)
        }
        assertEquals(listOf<MdBlock>(MdCode(0, "kotlin", "val x = 1")), streamed)
        assertEquals(0, stream.sealedCount, "an unclosed fence must stay unsealed - more code may come")
        assertEquals(MarkdownStream().sync(doc), streamed)
    }

    @Test
    fun `inline markdown resolves to styled spans`() {
        assertEquals(
            listOf(
                MdSpan("plasma flow "),
                MdSpan("nominal", bold = true),
                MdSpan(" across "),
                MdSpan("all", italic = true),
                MdSpan(" manifolds, per "),
                MdSpan("core.status", code = true),
                MdSpan(" - see "),
                MdSpan("the log", link = "https://x/log"),
                MdSpan("."),
            ),
            inlineSpans("plasma flow **nominal** across *all* manifolds, per `core.status` - see [the log](https://x/log)."),
        )
    }

    @Test
    fun `an underscore inside a word is a character, not a delimiter`() {
        assertEquals(
            listOf(MdSpan("snake_case stays snake_case")),
            inlineSpans("snake_case stays snake_case"),
        )
    }

    private companion object {
        /** Every shape the renderer knows, in one document - the acceptance's four kinds and the rest. */
        val DOCUMENT = """
            # Warp core diagnostic

            The plasma injector assembly reports **nominal** flow across all manifolds.

            ## Injector readings

            | Manifold | Flow | Status |
            | --- | ---: | :--- |
            | Port | 99.7% | nominal |
            | Starboard | 99.2% | nominal |

            - intermix ratio stable
            - coolant loop pressure within tolerance
              - primary loop nominal
            - no microfractures detected

            ```kotlin
            val core = "STABLE"

            # not a heading, just code
            ```

            1. seal the injectors
            2. recheck the matrix

            > Engineering signs off on the duty cycle.

            ---

            End of report.
        """.trimIndent() + "\n"
    }
}
