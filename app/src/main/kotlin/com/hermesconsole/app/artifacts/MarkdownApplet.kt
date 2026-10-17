package com.hermesconsole.app.artifacts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermesconsole.lcars.LcarsColors
import com.hermesconsole.lcars.LcarsMetrics
import com.hermesconsole.lcars.LcarsTheme
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The markdown artifact's pane (REQ-16): the payload's `markdown` field rendered as formatted
 * content - headings, lists, code blocks and tables as structure, never as raw text.
 *
 * The rendering is [MarkdownContent] over [MarkdownStream]'s block model - the same pipeline the
 * streaming reply pane runs token by token (REQ-18) - so an artifact's one-shot parse and a
 * finished stream render identically by construction. A payload with no usable `markdown` field
 * shows itself raw instead of crashing: no artifact is worth interrupting the reply (REQ-15).
 */
object MarkdownApplet : ArtifactApplet {

    override val type = "markdown"

    @Composable
    override fun Render(artifact: Artifact) {
        val markdown = (artifact.args["markdown"] as? JsonPrimitive)?.contentOrNull
        if (markdown == null) {
            BasicText(
                text = artifact.args.toString(),
                style = LcarsTheme.typography.body.copy(color = LcarsColors.OnSpace),
            )
            return
        }
        val blocks = remember(markdown) { MarkdownStream().sync(markdown) }
        MarkdownContent(blocks)
    }
}

/**
 * Markdown that may still be arriving (REQ-18): the composable the reply pane points at a growing
 * string. One [MarkdownStream] lives across recompositions; each new snapshot of [text] costs a
 * prefix check plus the open tail, never a reparse of the document - and for text that never
 * changes it is simply a parse-once renderer, so every assistant line goes through here.
 */
@Composable
fun StreamingMarkdown(text: String, modifier: Modifier = Modifier) {
    val stream = remember { MarkdownStream() }
    val blocks = remember(text) { stream.sync(text) }
    MarkdownContent(blocks, modifier)
}

/**
 * The block list on the wall. Each block is keyed by its document position and rendered from its
 * own immutable data, so a sealed block - same id, same instance - recomposes to the same nodes
 * and its inline styling stays remembered; only the open tail block ever changes under the key.
 */
@Composable
fun MarkdownContent(blocks: List<MdBlock>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap * 3),
    ) {
        blocks.forEach { block -> key(block.id) { MarkdownBlock(block) } }
    }
}

@Composable
private fun MarkdownBlock(block: MdBlock) {
    when (block) {
        is MdHeading -> BasicText(
            text = rememberInline(block.text),
            style = when (block.level) {
                1 -> LcarsTheme.typography.header.copy(fontSize = 34.sp, color = LcarsColors.Orange)
                2 -> LcarsTheme.typography.header.copy(color = LcarsColors.GoldenTanoi)
                else -> LcarsTheme.typography.label.copy(color = LcarsColors.AlmondCreme)
            },
        )

        is MdParagraph -> BasicText(
            text = rememberInline(block.text),
            style = LcarsTheme.typography.body.copy(color = LcarsColors.OnSpace),
        )

        is MdQuote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(LcarsMetrics.Gap)
                    .fillMaxHeight()
                    .background(LcarsColors.Lilac, RoundedCornerShape(2.dp)),
            )
            BasicText(
                text = rememberInline(block.text),
                style = LcarsTheme.typography.body.copy(color = LcarsColors.AlmondCreme),
                modifier = Modifier.padding(start = LcarsMetrics.Gap * 3).weight(1f),
            )
        }

        is MdRule -> Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(LcarsColors.Gray, RoundedCornerShape(50)),
        )

        is MdCode -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(PanelTint, RoundedCornerShape(12.dp))
                .padding(LcarsMetrics.Gap * 3),
            verticalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap),
        ) {
            if (block.language.isNotBlank()) {
                BasicText(
                    text = block.language.uppercase(),
                    style = LcarsTheme.typography.readout.copy(color = LcarsColors.GoldenTanoi),
                )
            }
            // Soft-wrapped on purpose: the content column never scrolls sideways (REQ-38).
            BasicText(
                text = block.code,
                style = LcarsTheme.typography.body.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    color = LcarsColors.IceBlue,
                ),
            )
        }

        is MdList -> Column(verticalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap)) {
            block.items.forEach { item ->
                Row {
                    Spacer(Modifier.width((item.depth * 24).dp))
                    BasicText(
                        text = item.ordinal?.let { "$it." } ?: "•",
                        style = LcarsTheme.typography.body.copy(color = LcarsColors.Orange),
                        modifier = Modifier.widthIn(min = 28.dp),
                    )
                    BasicText(
                        text = rememberInline(item.text),
                        style = LcarsTheme.typography.body.copy(color = LcarsColors.OnSpace),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        is MdTable -> {
            val columns = maxOf(block.header.size, 1)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap * 2)) {
                    repeat(columns) { c ->
                        BasicText(
                            text = rememberInline(block.header.getOrElse(c) { "" }),
                            style = LcarsTheme.typography.label.copy(color = LcarsColors.GoldenTanoi),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().height(2.dp).background(LcarsColors.Orange))
                block.rows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap * 2)) {
                        repeat(columns) { c ->
                            BasicText(
                                text = rememberInline(row.getOrElse(c) { "" }),
                                style = LcarsTheme.typography.body.copy(color = LcarsColors.OnSpace),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A block's inline markdown as an [AnnotatedString], computed once per text and remembered. */
@Composable
private fun rememberInline(text: String): AnnotatedString = remember(text) { annotated(text) }

private fun annotated(text: String): AnnotatedString = buildAnnotatedString {
    inlineSpans(text).forEach { span ->
        val decorations = listOfNotNull(
            TextDecoration.LineThrough.takeIf { span.strike },
            TextDecoration.Underline.takeIf { span.link != null },
        )
        withStyle(
            SpanStyle(
                fontWeight = FontWeight.SemiBold.takeIf { span.bold },
                fontStyle = FontStyle.Italic.takeIf { span.italic },
                fontFamily = FontFamily.Monospace.takeIf { span.code },
                fontSize = if (span.code) 16.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
                color = if (span.code || span.link != null) LcarsColors.IceBlue else Color.Unspecified,
                background = if (span.code) PanelTint else Color.Unspecified,
                textDecoration = when {
                    decorations.isEmpty() -> null
                    decorations.size == 1 -> decorations.single()
                    else -> TextDecoration.combine(decorations)
                },
            ),
        ) { append(span.text) }
    }
}

/** The one non-palette tone here: a whisper of Bluey for code to sit on, still reading as space. */
private val PanelTint = LcarsColors.Bluey.copy(alpha = 0.18f)
