package com.hermesconsole.lcars

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Which ends of a pill are rounded off. A run of pills caps only where the run itself ends. */
enum class LcarsCap {
    Both,
    Start,
    End,
    None,
}

/**
 * The pill: a filled block with its label punched out of it, capped at one or both ends (REQ-19).
 * It is the kit's workhorse - a button when [onClick] is given, a static label when it is not.
 *
 * The label is set in caps and pushed to the [labelAlignment] end, which for LCARS is the end
 * furthest from whatever the pill is attached to.
 */
@Composable
fun LcarsPill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LcarsColors.Orange,
    cap: LcarsCap = LcarsCap.Both,
    height: Dp = LcarsMetrics.BarHeight,
    minWidth: Dp = 120.dp,
    textColor: Color = LcarsColors.OnChrome,
    labelAlignment: Alignment = Alignment.CenterEnd,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val fill = if (enabled) color else LcarsColors.Gray
    // Every live touch on the kit confirms itself out loud (REQ-39); silence unless the shell
    // provided a cue path.
    val cues = LocalLcarsCues.current
    Box(
        modifier =
            modifier
                .defaultMinSize(minHeight = height)
                .widthIn(min = minWidth)
                .clip(cap.shape(height))
                .background(fill)
                .let {
                    if (onClick != null && enabled) {
                        it.clickable {
                            cues(LcarsCue.CONFIRM)
                            onClick()
                        }
                    } else {
                        it
                    }
                },
        contentAlignment = labelAlignment,
    ) {
        BasicText(
            text = text.lcarsCase(),
            modifier = Modifier.padding(horizontal = LcarsMetrics.LabelPadding),
            style = LcarsTheme.typography.label.copy(color = textColor),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A pill with no label: the blank blocks LCARS uses to fill a run and give it rhythm. Sized by the
 * caller, because a filler's whole job is to take up an exact amount of the row it sits in.
 */
@Composable
fun LcarsBlock(
    modifier: Modifier = Modifier,
    color: Color = LcarsColors.Bluey,
    cap: LcarsCap = LcarsCap.None,
    height: Dp = LcarsMetrics.BarHeight,
) {
    Box(modifier.defaultMinSize(minHeight = height).clip(cap.shape(height)).background(color))
}

/** A cap is a half-height radius on the end it applies to; an uncapped end is square. */
internal fun LcarsCap.shape(height: Dp): Shape {
    val r = height / 2
    val start = if (this == LcarsCap.Both || this == LcarsCap.Start) r else 0.dp
    val end = if (this == LcarsCap.Both || this == LcarsCap.End) r else 0.dp
    return RoundedCornerShape(topStart = start, bottomStart = start, topEnd = end, bottomEnd = end)
}
