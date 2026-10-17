package com.hermesconsole.lcars

import android.content.res.AssetManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Antonio, the LCARS face, loaded from the module's own assets - never from a font provider, so
 * the console renders identically with no network at all (REQ-19). One variable file carries every
 * weight; the wght axis is pinned per style rather than shipping four static cuts.
 */
private const val ANTONIO_ASSET = "fonts/Antonio-Variable.ttf"

private fun antonio(assets: AssetManager): FontFamily =
    FontFamily(
        antonioCut(assets, FontWeight.Light),
        antonioCut(assets, FontWeight.Normal),
        antonioCut(assets, FontWeight.Medium),
        antonioCut(assets, FontWeight.SemiBold),
        antonioCut(assets, FontWeight.Bold),
    )

private fun antonioCut(assets: AssetManager, weight: FontWeight): Font =
    Font(
        path = ANTONIO_ASSET,
        assetManager = assets,
        weight = weight,
        variationSettings = FontVariation.Settings(weight, FontStyle.Normal),
    )

/**
 * The type scale. LCARS text is set in caps, tracked out, and used as a label on a shape far more
 * often than as prose - so the scale is led by its labels, with [body] the one style that is
 * allowed to run long (an assistant reply, an artifact's markdown).
 */
@Immutable
class LcarsTypography(family: FontFamily) {
    /** The one big word at the top of the screen. */
    val title =
        TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 44.sp, letterSpacing = 3.sp)

    /** A section heading, or the name of a pane. */
    val header =
        TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 28.sp, letterSpacing = 2.sp)

    /** The label punched out of a pill or a rail segment. */
    val label =
        TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, letterSpacing = 1.5.sp)

    /** Small numeric chrome: counters, stardates, the digits LCARS scatters around a frame. */
    val readout =
        TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 15.sp, letterSpacing = 1.sp)

    /** Running text. The only style that is not shouted. */
    val body =
        TextStyle(fontFamily = family, fontWeight = FontWeight.Light, fontSize = 19.sp, lineHeight = 26.sp)
}

private val LocalLcarsTypography = staticCompositionLocalOf<LcarsTypography> {
    error("No LcarsTypography: wrap the tree in LcarsTheme { }")
}

/**
 * Provides the kit's typography to everything under it. The face needs an [AssetManager], so it is
 * resolved once here rather than by every component that draws a word.
 */
@Composable
fun LcarsTheme(content: @Composable () -> Unit) {
    val assets = LocalContext.current.assets
    val typography = remember(assets) { LcarsTypography(antonio(assets)) }
    CompositionLocalProvider(LocalLcarsTypography provides typography, content = content)
}

/** Access to the kit's typography from inside an [LcarsTheme]. */
object LcarsTheme {
    val typography: LcarsTypography
        @Composable @ReadOnlyComposable get() = LocalLcarsTypography.current
}

/** LCARS labels are always set in caps; the caller should not have to remember that. */
internal fun String.lcarsCase(): String = uppercase()
