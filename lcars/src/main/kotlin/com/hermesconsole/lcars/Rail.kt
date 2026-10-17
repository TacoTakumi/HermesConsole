package com.hermesconsole.lcars

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One block in a rail or a bar. [weight] is its share of the run's length, so a rail is described
 * by proportion - which is how LCARS reads: a tall block, two short ones, a gap.
 */
@Immutable
data class LcarsSegment(
    val label: String? = null,
    val color: Color = LcarsColors.Orange,
    val weight: Float = 1f,
    val enabled: Boolean = true,
    val onClick: (() -> Unit)? = null,
)

/**
 * The vertical rail: the stack of blocks down the side of the frame, gapped by the kit's gutter
 * and rounded only where the whole run ends (REQ-19). Everything between reads as one column of
 * chrome, which is what makes the elbow at its head look like a joint rather than a decoration.
 */
@Composable
fun LcarsRail(
    segments: List<LcarsSegment>,
    modifier: Modifier = Modifier,
    width: Dp = LcarsMetrics.RailWidth,
    gap: Dp = LcarsMetrics.Gap,
    capTop: Boolean = false,
    capBottom: Boolean = true,
    // Half the rail's width would bow the end into a semicircle and swallow the segment's label;
    // a bar-height cap is the radius the rest of the kit already rounds by.
    capRadius: Dp = LcarsMetrics.BarHeight / 2,
) {
    Column(modifier.width(width), verticalArrangement = Arrangement.spacedBy(gap)) {
        segments.forEachIndexed { index, segment ->
            val top = if (capTop && index == 0) capRadius else 0.dp
            val bottom = if (capBottom && index == segments.lastIndex) capRadius else 0.dp
            RailBlock(segment, verticalShape(top, bottom), Modifier.weight(segment.weight))
        }
    }
}

/**
 * The horizontal bar: the same run of blocks, laid along a frame edge. Caps land on the ends of
 * the run, so a bar butted against an elbow leaves its attached end square.
 */
@Composable
fun LcarsBar(
    segments: List<LcarsSegment>,
    modifier: Modifier = Modifier,
    height: Dp = LcarsMetrics.BarHeight,
    gap: Dp = LcarsMetrics.Gap,
    capStart: Boolean = false,
    capEnd: Boolean = true,
) {
    Row(modifier.height(height), horizontalArrangement = Arrangement.spacedBy(gap)) {
        segments.forEachIndexed { index, segment ->
            val cap =
                when {
                    segments.size == 1 && capStart && capEnd -> LcarsCap.Both
                    capStart && index == 0 -> LcarsCap.Start
                    capEnd && index == segments.lastIndex -> LcarsCap.End
                    else -> LcarsCap.None
                }
            BarBlock(segment, cap, Modifier.weight(segment.weight))
        }
    }
}

/** A rail block: label pinned bottom-end, the way a rail reads down and out. */
@Composable
private fun ColumnScope.RailBlock(segment: LcarsSegment, shape: Shape, modifier: Modifier) {
    val fill = if (segment.enabled) segment.color else LcarsColors.Gray
    // As in LcarsPill: a live touch confirms itself out loud (REQ-39).
    val cues = LocalLcarsCues.current
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(fill)
                .let {
                    val onClick = segment.onClick
                    if (onClick != null && segment.enabled) {
                        it.clickable {
                            cues(LcarsCue.CONFIRM)
                            onClick()
                        }
                    } else {
                        it
                    }
                },
        contentAlignment = Alignment.BottomEnd,
    ) {
        segment.label?.let { label ->
            BasicText(
                text = label.lcarsCase(),
                modifier = Modifier.padding(LcarsMetrics.LabelPadding / 2),
                // End-aligned like the block corner it sits in: a wrapped label's lines must sit
                // flush right where every single-line label sits (REQ-16).
                style = LcarsTheme.typography.label.copy(color = LcarsColors.OnChrome, textAlign = TextAlign.End),
                // Two lines, not one: a multi-word label ("system access") must read whole on the
                // rail's width, and truncating chrome text defeats the label (REQ-16).
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RowScope.BarBlock(segment: LcarsSegment, cap: LcarsCap, modifier: Modifier) {
    if (segment.label == null) {
        LcarsBlock(modifier.fillMaxHeight(), segment.color, cap)
    } else {
        LcarsPill(
            text = segment.label,
            modifier = modifier.fillMaxHeight(),
            color = segment.color,
            cap = cap,
            minWidth = 0.dp,
            enabled = segment.enabled,
            onClick = segment.onClick,
        )
    }
}

/** A vertically-capped shape: the rounding runs across the block's full width. */
private fun verticalShape(top: Dp, bottom: Dp): Shape =
    RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
