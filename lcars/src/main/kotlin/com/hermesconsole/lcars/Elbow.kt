package com.hermesconsole.lcars

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Which corner of its own box the elbow turns through. */
enum class LcarsCorner {
    TopStart,
    TopEnd,
    BottomStart,
    BottomEnd,
}

/**
 * The elbow: the L that joins a rail to a bar, and the shape the whole grammar hangs off (REQ-19).
 * A wide outer sweep, a small bite out of the inner corner, and two straight arms - the vertical
 * one [railWidth] wide, the horizontal one [barHeight] tall.
 *
 * The elbow fills whatever box the [modifier] gives it, so a caller sizes it by layout rather than
 * by arithmetic. [LcarsElbowSize] is here for the common case of a fixed one.
 */
@Composable
fun LcarsElbow(
    modifier: Modifier = Modifier,
    corner: LcarsCorner = LcarsCorner.TopStart,
    color: Color = LcarsColors.Orange,
    railWidth: Dp = LcarsMetrics.RailWidth,
    barHeight: Dp = LcarsMetrics.BarHeight,
    outerRadius: Dp = LcarsMetrics.OuterRadius,
    innerRadius: Dp = LcarsMetrics.InnerRadius,
) {
    Canvas(modifier) {
        val path =
            topStartElbow(
                size = size,
                rail = railWidth.toPx(),
                bar = barHeight.toPx(),
                outer = outerRadius.toPx(),
                inner = innerRadius.toPx(),
            )
        // Every elbow is the top-start one, reflected. Mirroring the path rather than authoring
        // four of them keeps the four corners provably identical.
        withTransform({ scale(corner.scaleX(), corner.scaleY(), pivot = center) }) {
            drawPath(path, color)
        }
    }
}

/** An elbow at the kit's default size: one rail wide, tall enough to turn. */
@Composable
fun LcarsElbowSize(
    modifier: Modifier = Modifier,
    corner: LcarsCorner = LcarsCorner.TopStart,
    color: Color = LcarsColors.Orange,
    width: Dp = LcarsMetrics.RailWidth + 96.dp,
    height: Dp = LcarsMetrics.BarHeight + 72.dp,
) = LcarsElbow(modifier.size(width, height), corner, color)

private fun LcarsCorner.scaleX(): Float =
    when (this) {
        LcarsCorner.TopStart, LcarsCorner.BottomStart -> 1f
        LcarsCorner.TopEnd, LcarsCorner.BottomEnd -> -1f
    }

private fun LcarsCorner.scaleY(): Float =
    when (this) {
        LcarsCorner.TopStart, LcarsCorner.TopEnd -> 1f
        LcarsCorner.BottomStart, LcarsCorner.BottomEnd -> -1f
    }

/**
 * The top-start elbow, traced clockwise from the foot of the vertical arm:
 *
 *     (0,H) -> up -> outer sweep -> along the top -> down the bar's end
 *           -> back under the bar -> inner bite -> down the rail's inner edge -> close
 *
 * Radii are clamped to what the box can actually hold, so a squeezed elbow degrades into a
 * tighter corner rather than into a self-intersecting path.
 */
private fun DrawScope.topStartElbow(size: Size, rail: Float, bar: Float, outer: Float, inner: Float): Path {
    val w = size.width
    val h = size.height
    val r = outer.coerceAtMost(minOf(rail, bar, w, h))
    val i = inner.coerceAtMost(minOf(w - rail, h - bar).coerceAtLeast(0f))

    return Path().apply {
        moveTo(0f, h)
        lineTo(0f, r)
        // Outer corner: a quarter turn about (r, r), from due west round to due north.
        arcTo(Rect(0f, 0f, 2 * r, 2 * r), 180f, 90f, forceMoveTo = false)
        lineTo(w, 0f)
        lineTo(w, bar)
        lineTo(rail + i, bar)
        // Inner corner: the same quarter turn, run backwards, so the bite is concave.
        arcTo(Rect(rail, bar, rail + 2 * i, bar + 2 * i), 270f, -90f, forceMoveTo = false)
        lineTo(rail, h)
        close()
    }
}
