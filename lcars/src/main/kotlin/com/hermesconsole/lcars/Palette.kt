package com.hermesconsole.lcars

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The LCARS palette (REQ-19). Black first: LCARS is chrome laid on space, never a light surface.
 *
 * The names are the canonical ones from the show's style guides (thelcars.com), kept so that a
 * consumer asks for a colour by its LCARS name rather than by a hex it has to recognise.
 */
object LcarsColors {
    /** The background. Everything else is a shape floating on it. */
    val Space = Color(0xFF000000)

    val Orange = Color(0xFFFF9900)
    val GoldenTanoi = Color(0xFFFFCC66)
    val Sunflower = Color(0xFFFFCC00)
    val Butterscotch = Color(0xFFFF9966)
    val AlmondCreme = Color(0xFFFFCC99)
    val Lilac = Color(0xFFCC99CC)
    val AfricanViolet = Color(0xFF9944AA)
    val IceBlue = Color(0xFF99CCFF)
    val Bluey = Color(0xFF6688CC)
    val Anodyne = Color(0xFF7788FF)
    val Mars = Color(0xFFCC6666)
    val Tomato = Color(0xFFDD4444)

    /** Inert chrome: a rail segment that is present but not offering anything. */
    val Gray = Color(0xFF555555)

    /** Text on a filled shape. LCARS labels are punched out of the fill, not written over it. */
    val OnChrome = Space

    /** Text on space. */
    val OnSpace = GoldenTanoi

    /** A rail's default run of colours, in the order they read down a column. */
    val RailRun = listOf(Orange, Lilac, IceBlue, Butterscotch, Bluey, AlmondCreme)
}

/**
 * The geometry constants the kit is built from. LCARS is a grid: a 4 dp gutter between every
 * shape, one bar height, one rail width, one pair of corner radii. Sizes are given, not guessed.
 */
object LcarsMetrics {
    /** The gutter. Every shape is separated from its neighbour by exactly this. */
    val Gap = 4.dp

    /** The height of a pill, a bar and an elbow's horizontal arm. */
    val BarHeight = 48.dp

    /** The width of a side rail and of an elbow's vertical arm. */
    val RailWidth = 128.dp

    /** The sweep of an elbow's outer corner, and the cap radius of a pill. */
    val OuterRadius = 56.dp

    /** The bite taken out of an elbow's inner corner. */
    val InnerRadius = 20.dp

    /** Distance from a pill's cap to its label. */
    val LabelPadding = 16.dp
}
