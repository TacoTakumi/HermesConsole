package com.hermesconsole.app.artifacts

import androidx.compose.runtime.Immutable

/**
 * The markdown block model and the incremental splitter behind both markdown surfaces (REQ-16,
 * REQ-18): the markdown artifact pane and the streaming assistant reply.
 *
 * A reply arrives as `message.delta` tokens, and REQ-18 forbids the obvious renderer - reparse the
 * whole accumulated text at every token - because it makes each token cost the whole document.
 * [MarkdownStream] instead splits the text into top-level blocks as the lines complete, and *seals*
 * a block the moment nothing later in the stream could change it: a paragraph at its blank line, a
 * code block at its closing fence, a list only when a flush-left non-item finally ends it. A sealed
 * block is one immutable instance, returned identically in every later snapshot - which is the
 * whole trick: Compose keys on it, the inline styling is remembered against it, and the only work a
 * new token can cause is on the still-open tail.
 *
 * The block model is deliberately flat and small: what an LLM writes into a wall console's reply -
 * headings, paragraphs, lists, fenced code, tables, quotes, rules. It is not CommonMark complete;
 * it is the wire-to-wall shape for exactly these surfaces, and both of them go through it, so the
 * finished stream and a one-shot parse of the finished text render identically by construction.
 */
sealed interface MdBlock {
    /** Position in the document, assigned in order - a stable Compose key on a growing document. */
    val id: Int
}

@Immutable
data class MdHeading(override val id: Int, val level: Int, val text: String) : MdBlock

@Immutable
data class MdParagraph(override val id: Int, val text: String) : MdBlock

@Immutable
data class MdQuote(override val id: Int, val text: String) : MdBlock

@Immutable
data class MdRule(override val id: Int) : MdBlock

@Immutable
data class MdCode(override val id: Int, val language: String, val code: String) : MdBlock

@Immutable
data class MdListItem(val depth: Int, val ordinal: Int?, val text: String)

@Immutable
data class MdList(override val id: Int, val items: List<MdListItem>) : MdBlock

@Immutable
data class MdTable(override val id: Int, val header: List<String>, val rows: List<List<String>>) : MdBlock

/** A finished block, waiting for its position in the document to become its id. */
private typealias BlockFactory = (Int) -> MdBlock

/**
 * The incremental splitter. Feed it the *whole accumulated text* at each snapshot via [sync]; it
 * consumes only what is new, seals what has finished, and re-derives only the open tail. A text
 * that is not an append of the last one - a superseded turn, a new reply in the same slot - resets
 * the stream and parses fresh, which is also exactly what a one-shot caller gets.
 */
class MarkdownStream {

    private var seen = ""
    private val sealed = ArrayList<MdBlock>()
    private var state = ParserState()
    private val partial = StringBuilder()

    /** How many leading blocks of the last snapshot are sealed - immutable, identity-stable. */
    val sealedCount: Int get() = sealed.size

    /**
     * Bring the stream up to [full] and return the current snapshot: every sealed block (same
     * instances as last time), then the open tail parsed from the unfinished lines. The prefix
     * check is a character compare, not a parse - the cost a token pays for the whole document is
     * one `startsWith`, and everything else is proportional to the tail.
     */
    fun sync(full: String): List<MdBlock> {
        if (!full.startsWith(seen)) {
            seen = ""
            sealed.clear()
            state = ParserState()
            partial.setLength(0)
        }
        val from = seen.length
        seen = full
        for (i in from until full.length) {
            val ch = full[i]
            if (ch == '\n') {
                val line = partial.toString().removeSuffix("\r")
                partial.setLength(0)
                state.processLine(line) { make -> sealed.add(make(sealed.size)) }
            } else {
                partial.append(ch)
            }
        }

        // The tail preview: a copy of the parser state plays the unfinished line as if it were
        // complete, then flushes whatever is open. The real state is never touched, so a token
        // that later changes the line's meaning (a "#" growing into a heading) just previews
        // differently next time.
        val preview = ArrayList<MdBlock>()
        val sink: (BlockFactory) -> Unit = { make -> preview.add(make(sealed.size + preview.size)) }
        val tail = state.copy()
        val partialLine = partial.toString().removeSuffix("\r")
        if (partialLine.isNotEmpty()) tail.processLine(partialLine, sink)
        tail.flushOpen(sink)
        return sealed + preview
    }
}

private enum class Mode { NONE, PARA, CODE, LIST, TABLE, QUOTE }

private class PendingItem(val depth: Int, val ordinal: Int?, val text: StringBuilder) {
    fun copy() = PendingItem(depth, ordinal, StringBuilder(text))
}

/**
 * The line-by-line state machine. It only ever sees *complete* lines, in order, so its decisions
 * cannot depend on how the text was chunked - which is the property that makes token-by-token
 * streaming converge on the batch parse.
 */
private class ParserState {

    private var mode = Mode.NONE
    private val lines = ArrayList<String>() // PARA and QUOTE content
    private var fenceChar = '`'
    private var fenceLen = 3
    private var language = ""
    private val code = ArrayList<String>()
    private val items = ArrayList<PendingItem>()
    private var listBlank = false
    private var tableHeader: List<String> = emptyList()
    private val tableRows = ArrayList<List<String>>()

    fun copy(): ParserState = ParserState().also { c ->
        c.mode = mode
        c.lines.addAll(lines)
        c.fenceChar = fenceChar
        c.fenceLen = fenceLen
        c.language = language
        c.code.addAll(code)
        items.forEach { c.items.add(it.copy()) }
        c.listBlank = listBlank
        c.tableHeader = tableHeader
        c.tableRows.addAll(tableRows)
    }

    fun processLine(line: String, seal: (BlockFactory) -> Unit) {
        // A fence swallows everything, markdown or not, until its closing fence.
        if (mode == Mode.CODE) {
            if (isFenceClose(line)) sealOpen(seal) else code.add(line)
            return
        }

        if (line.isBlank()) {
            // A blank line ends everything except a list, which the *next* line decides: another
            // item continues it (a loose list is still one list), anything flush-left ends it.
            if (mode == Mode.LIST) listBlank = true else sealOpen(seal)
            return
        }

        val fence = FENCE_OPEN.matchEntire(line)
        if (fence != null && !(fence.groupValues[1][0] == '`' && fence.groupValues[2].contains('`'))) {
            sealOpen(seal)
            mode = Mode.CODE
            fenceChar = fence.groupValues[1][0]
            fenceLen = fence.groupValues[1].length
            language = fence.groupValues[2].trim().substringBefore(' ')
            return
        }

        // A setext underline directly below an open paragraph turns that paragraph into a heading
        // - the one way a later line rewrites an earlier one, and the reason a paragraph cannot
        // seal before its blank line arrives.
        if (mode == Mode.PARA && SETEXT.matches(line)) {
            val level = if (line.trimStart().startsWith('=')) 1 else 2
            val text = joined(lines)
            lines.clear()
            mode = Mode.NONE
            seal { id -> MdHeading(id, level, text) }
            return
        }

        if (THEMATIC.matches(line)) {
            sealOpen(seal)
            seal { id -> MdRule(id) }
            return
        }

        val atx = ATX.matchEntire(line.trimStart())
        if (atx != null) {
            sealOpen(seal)
            val level = atx.groupValues[1].length
            val text = atx.groupValues[2].trim().trimEnd('#').trim()
            seal { id -> MdHeading(id, level, text) }
            return
        }

        val item = LIST_ITEM.matchEntire(line)
        if (item != null) {
            if (mode != Mode.LIST) {
                sealOpen(seal)
                mode = Mode.LIST
            }
            listBlank = false
            val marker = item.groupValues[2]
            items.add(
                PendingItem(
                    depth = item.groupValues[1].length / 2,
                    ordinal = marker.dropLast(1).toIntOrNull(),
                    text = StringBuilder(item.groupValues[3].trim()),
                ),
            )
            return
        }

        if (mode == Mode.LIST) {
            if (!listBlank || line.startsWith("  ")) {
                items.last().text.append(' ').append(line.trim())
                listBlank = false
                return
            }
            sealOpen(seal) // flush-left after the gap: the list is over; the line classifies fresh below
        }

        if (mode == Mode.TABLE) {
            if (line.contains('|')) {
                tableRows.add(splitRow(line))
                return
            }
            sealOpen(seal)
        }

        // A delimiter row under a line of cells turns the open paragraph's last line into a table
        // header; any earlier lines were prose and seal as the paragraph they are.
        if (mode == Mode.PARA && isDelimiterRow(line) && lines.last().contains('|')) {
            val header = splitRow(lines.removeAt(lines.size - 1))
            if (lines.isNotEmpty()) {
                val text = joined(lines)
                seal { id -> MdParagraph(id, text) }
            }
            lines.clear()
            mode = Mode.TABLE
            tableHeader = header
            return
        }

        val quote = QUOTE.matchEntire(line)
        if (quote != null && mode != Mode.QUOTE) {
            sealOpen(seal)
            mode = Mode.QUOTE
            lines.add(quote.groupValues[1])
            return
        }
        if (mode == Mode.QUOTE) {
            lines.add(quote?.groupValues?.get(1) ?: line)
            return
        }

        if (mode != Mode.PARA) mode = Mode.PARA
        lines.add(line)
    }

    /** Materialise whatever is still open - the tail preview's last step, on a copy only. */
    fun flushOpen(seal: (BlockFactory) -> Unit) = sealOpen(seal)

    private fun sealOpen(seal: (BlockFactory) -> Unit) {
        when (mode) {
            Mode.NONE -> Unit
            Mode.PARA -> {
                val text = joined(lines)
                if (text.isNotBlank()) seal { id -> MdParagraph(id, text) }
            }
            Mode.QUOTE -> {
                val text = joined(lines)
                seal { id -> MdQuote(id, text) }
            }
            Mode.CODE -> {
                val lang = language
                val body = code.joinToString("\n")
                seal { id -> MdCode(id, lang, body) }
            }
            Mode.LIST -> {
                val list = items.map { MdListItem(it.depth, it.ordinal, it.text.toString()) }
                seal { id -> MdList(id, list) }
            }
            Mode.TABLE -> {
                val header = tableHeader
                val rows = tableRows.toList()
                seal { id -> MdTable(id, header, rows) }
            }
        }
        lines.clear()
        code.clear()
        items.clear()
        tableRows.clear()
        tableHeader = emptyList()
        language = ""
        listBlank = false
        mode = Mode.NONE
    }

    private fun isFenceClose(line: String): Boolean {
        val t = line.trim()
        return t.length >= fenceLen && t.all { it == fenceChar }
    }

    private companion object {
        val FENCE_OPEN = Regex("""\s{0,3}(`{3,}|~{3,})\s*(.*)""")
        val SETEXT = Regex("""\s{0,3}(=+|-+)\s*""")
        val THEMATIC = Regex("""\s{0,3}([*_-])(\s*\1){2,}\s*""")
        val ATX = Regex("""(#{1,6})(?:\s+(.*?))?\s*""")
        val LIST_ITEM = Regex("""(\s{0,11})([-*+]|\d{1,9}[.)])\s+(.*)""")
        val QUOTE = Regex("""\s{0,3}>\s?(.*)""")
        val DELIMITER = Regex("""\s{0,3}\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*""")

        fun isDelimiterRow(line: String) = line.contains('|') && line.contains('-') && DELIMITER.matches(line)

        /** Prose lines join on a space; a line that ends in markdown's two-space break keeps its break. */
        fun joined(lines: List<String>): String = buildString {
            lines.forEachIndexed { i, raw ->
                append(raw.trim())
                if (i < lines.size - 1) append(if (raw.endsWith("  ")) "\n" else " ")
            }
        }

        /** One table row into cells: unescaped pipes split, `\|` stays for the inline pass. */
        fun splitRow(line: String): List<String> {
            val cells = ArrayList<String>()
            val cell = StringBuilder()
            val t = line.trim()
            var i = 0
            while (i < t.length) {
                val c = t[i]
                when {
                    c == '\\' && i + 1 < t.length && t[i + 1] == '|' -> {
                        cell.append("\\|")
                        i += 2
                    }
                    c == '|' -> {
                        cells.add(cell.toString().trim())
                        cell.setLength(0)
                        i++
                    }
                    else -> {
                        cell.append(c)
                        i++
                    }
                }
            }
            cells.add(cell.toString().trim())
            if (cells.isNotEmpty() && cells.first().isEmpty()) cells.removeAt(0)
            if (cells.isNotEmpty() && cells.last().isEmpty()) cells.removeAt(cells.size - 1)
            return cells
        }
    }
}

// -- inline markdown ---------------------------------------------------------------------------

/**
 * One run of identically-styled text inside a block. The flat shape the renderer wants: no tree,
 * just the text with its styles resolved, in order.
 */
@Immutable
data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false,
    val link: String? = null,
)

/**
 * Resolve a block's inline markdown - emphasis, code spans, strikethrough, links, escapes - into
 * styled spans. Not CommonMark's delimiter algorithm: a forward scan that handles what a model
 * actually writes, and degrades to literal text rather than ever failing.
 */
fun inlineSpans(text: String): List<MdSpan> {
    val out = ArrayList<MdSpan>()
    scanInline(text, bold = false, italic = false, strike = false, link = null, out = out)
    return out
}

private const val ESCAPABLE = "\\`*_{}[]()#+-.!|~><\""

private fun scanInline(
    text: String,
    bold: Boolean,
    italic: Boolean,
    strike: Boolean,
    link: String?,
    out: MutableList<MdSpan>,
) {
    val plain = StringBuilder()
    fun flush() {
        if (plain.isNotEmpty()) {
            out.add(MdSpan(plain.toString(), bold, italic, code = false, strike = strike, link = link))
            plain.setLength(0)
        }
    }
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '\\' && i + 1 < text.length && text[i + 1] in ESCAPABLE -> {
                plain.append(text[i + 1])
                i += 2
            }

            c == '`' -> {
                val run = runLength(text, i, '`')
                val close = findClosingRun(text, i + run, '`', run)
                if (close < 0) {
                    repeat(run) { plain.append('`') }
                    i += run
                } else {
                    flush()
                    var content = text.substring(i + run, close)
                    if (content.length >= 2 && content.startsWith(' ') && content.endsWith(' ') && content.isNotBlank()) {
                        content = content.substring(1, content.length - 1)
                    }
                    out.add(MdSpan(content, bold, italic, code = true, strike = strike, link = link))
                    i = close + run
                }
            }

            c == '~' && i + 1 < text.length && text[i + 1] == '~' -> {
                val close = text.indexOf("~~", i + 2)
                if (close > i + 2 && !text[i + 2].isWhitespace() && !text[close - 1].isWhitespace()) {
                    flush()
                    scanInline(text.substring(i + 2, close), bold, italic, strike = true, link = link, out = out)
                    i = close + 2
                } else {
                    plain.append(c)
                    i++
                }
            }

            c == '!' && i + 1 < text.length && text[i + 1] == '[' -> {
                val hit = parseLink(text, i + 1)
                if (hit == null) {
                    plain.append(c)
                    i++
                } else {
                    flush()
                    scanInline(hit.label, bold, italic, strike, link = hit.url, out = out)
                    i = hit.end
                }
            }

            c == '[' -> {
                val hit = parseLink(text, i)
                if (hit == null) {
                    plain.append(c)
                    i++
                } else {
                    flush()
                    scanInline(hit.label, bold, italic, strike, link = hit.url, out = out)
                    i = hit.end
                }
            }

            c == '*' || c == '_' -> {
                if (c == '_' && i > 0 && text[i - 1].isLetterOrDigit()) {
                    plain.append(c)
                    i++
                } else {
                    val run = runLength(text, i, c)
                    val hit = tryEmphasis(text, i, c, run)
                    if (hit == null) {
                        repeat(run) { plain.append(c) }
                        i += run
                    } else {
                        flush()
                        scanInline(hit.content, bold || hit.bold, italic || hit.italic, strike, link, out)
                        i = hit.end
                    }
                }
            }

            else -> {
                plain.append(c)
                i++
            }
        }
    }
    flush()
}

private class Emphasis(val content: String, val bold: Boolean, val italic: Boolean, val end: Int)

private fun tryEmphasis(text: String, at: Int, c: Char, run: Int): Emphasis? {
    // Longest delimiter first: *** is bold italic, ** bold, * italic.
    for (len in minOf(run, 3) downTo 1) {
        val delim = c.toString().repeat(len)
        val start = at + len
        if (start >= text.length || text[start].isWhitespace()) continue
        var close = text.indexOf(delim, start)
        while (close >= 0) {
            val okBefore = close > start && !text[close - 1].isWhitespace() && text[close - 1] != '\\'
            val after = close + len
            val okAfter = c != '_' || after >= text.length || !text[after].isLetterOrDigit()
            if (okBefore && okAfter) break
            close = text.indexOf(delim, close + 1)
        }
        if (close < 0) continue
        return Emphasis(text.substring(start, close), bold = len >= 2, italic = len != 2, end = close + len)
    }
    return null
}

private class LinkHit(val label: String, val url: String, val end: Int)

private fun parseLink(text: String, at: Int): LinkHit? {
    var depth = 0
    var i = at
    var closeBracket = -1
    while (i < text.length) {
        when (text[i]) {
            '\\' -> i++
            '[' -> depth++
            ']' -> {
                depth--
                if (depth == 0) {
                    closeBracket = i
                    break
                }
            }
        }
        i++
    }
    if (closeBracket < 0 || closeBracket + 1 >= text.length || text[closeBracket + 1] != '(') return null
    val closeParen = text.indexOf(')', closeBracket + 2)
    if (closeParen < 0) return null
    return LinkHit(
        label = text.substring(at + 1, closeBracket),
        url = text.substring(closeBracket + 2, closeParen).trim().substringBefore(' '),
        end = closeParen + 1,
    )
}

private fun runLength(text: String, from: Int, c: Char): Int {
    var i = from
    while (i < text.length && text[i] == c) i++
    return i - from
}

private fun findClosingRun(text: String, from: Int, c: Char, len: Int): Int {
    var i = from
    while (i < text.length) {
        if (text[i] == c) {
            val r = runLength(text, i, c)
            if (r == len) return i
            i += r
        } else {
            i++
        }
    }
    return -1
}
