package com.hermesconsole.lcars

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * The preview harness (REQ-19): every component the kit offers, rendered with nothing else
 * running. It is in `main` rather than in a test source set on purpose - the kit is meant to be
 * liftable into another consumer, and the first thing that consumer wants is to see it.
 *
 * On device it is hosted by the debug-only gallery Activity; in the IDE, by the previews below.
 */
@Composable
fun LcarsGallery(modifier: Modifier = Modifier) {
    LcarsTheme {
        Column(
            modifier =
                modifier
                    .background(LcarsColors.Space)
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(LcarsMetrics.Gap * 4),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Frame()
            TypeSpecimen()
            Palette()
            Elbows()
            Pills()
            Bars()
        }
    }
}

/** An elbow joined to a rail and a bar: the grammar, assembled the way a screen would use it. */
@Composable
private fun Frame() {
    Section("FRAME") {
        Row(horizontalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap)) {
            Column(verticalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap)) {
                LcarsElbow(
                    modifier = Modifier.size(LcarsMetrics.RailWidth + 80.dp, LcarsMetrics.BarHeight + 64.dp),
                    corner = LcarsCorner.TopStart,
                    color = LcarsColors.Orange,
                )
                LcarsRail(
                    segments =
                        listOf(
                            LcarsSegment("ready", LcarsColors.Lilac, weight = 2f),
                            LcarsSegment("sessions", LcarsColors.IceBlue),
                            LcarsSegment(null, LcarsColors.Bluey, weight = 0.6f),
                            LcarsSegment("config", LcarsColors.Butterscotch),
                        ),
                    modifier = Modifier.height(280.dp),
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap),
            ) {
                Spacer(Modifier.height(LcarsMetrics.BarHeight + 64.dp - LcarsMetrics.BarHeight))
                Box(
                    modifier =
                        Modifier.fillMaxWidth()
                            .height(280.dp + LcarsMetrics.BarHeight)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0A0A0A)),
                ) {
                    BasicText(
                        text = "LCARS 47174-A",
                        modifier = Modifier.padding(16.dp),
                        style = LcarsTheme.typography.readout.copy(color = LcarsColors.OnSpace),
                    )
                }
            }
        }
    }
}

@Composable
private fun TypeSpecimen() {
    Section("TYPE - ANTONIO, BUNDLED") {
        BasicText("LCARS SYSTEMS", style = LcarsTheme.typography.title.copy(color = LcarsColors.Orange))
        BasicText("SUBSYSTEM STATUS", style = LcarsTheme.typography.header.copy(color = LcarsColors.Lilac))
        BasicText("LABEL 20SP", style = LcarsTheme.typography.label.copy(color = LcarsColors.IceBlue))
        BasicText("READOUT 0123456789", style = LcarsTheme.typography.readout.copy(color = LcarsColors.GoldenTanoi))
        BasicText(
            text =
                "Body text runs long: the only style in the kit that is not shouted, used for an " +
                    "assistant reply or an artifact's markdown.",
            style = LcarsTheme.typography.body.copy(color = LcarsColors.AlmondCreme),
        )
    }
}

@Composable
private fun Palette() {
    val swatches =
        listOf(
            "orange" to LcarsColors.Orange,
            "golden tanoi" to LcarsColors.GoldenTanoi,
            "sunflower" to LcarsColors.Sunflower,
            "butterscotch" to LcarsColors.Butterscotch,
            "almond creme" to LcarsColors.AlmondCreme,
            "lilac" to LcarsColors.Lilac,
            "african violet" to LcarsColors.AfricanViolet,
            "ice blue" to LcarsColors.IceBlue,
            "bluey" to LcarsColors.Bluey,
            "anodyne" to LcarsColors.Anodyne,
            "mars" to LcarsColors.Mars,
            "tomato" to LcarsColors.Tomato,
            "gray" to LcarsColors.Gray,
        )
    Section("PALETTE") {
        swatches.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap)) {
                row.forEach { (name, color) ->
                    LcarsPill(text = name, color = color, minWidth = 0.dp, modifier = Modifier.width(150.dp))
                }
            }
        }
    }
}

@Composable
private fun Elbows() {
    Section("ELBOWS") {
        Row(horizontalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap * 4)) {
            LcarsCorner.entries.forEach { corner ->
                Column(verticalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap)) {
                    LcarsElbow(
                        modifier = Modifier.size(160.dp, 110.dp),
                        corner = corner,
                        color = LcarsColors.Orange,
                        railWidth = 60.dp,
                        barHeight = 36.dp,
                        outerRadius = 40.dp,
                        innerRadius = 16.dp,
                    )
                    BasicText(
                        text = corner.name,
                        style = LcarsTheme.typography.readout.copy(color = LcarsColors.OnSpace),
                    )
                }
            }
        }
    }
}

@Composable
private fun Pills() {
    Section("PILLS") {
        Row(horizontalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap)) {
            LcarsPill("both", color = LcarsColors.Orange, cap = LcarsCap.Both)
            LcarsPill("start", color = LcarsColors.Lilac, cap = LcarsCap.Start)
            LcarsPill("end", color = LcarsColors.IceBlue, cap = LcarsCap.End)
            LcarsPill("none", color = LcarsColors.Butterscotch, cap = LcarsCap.None)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap)) {
            LcarsPill("engage", color = LcarsColors.Sunflower, onClick = {})
            LcarsPill("offline", color = LcarsColors.Mars, enabled = false, onClick = {})
            LcarsBlock(modifier = Modifier.width(80.dp), color = LcarsColors.Bluey, cap = LcarsCap.End)
        }
    }
}

@Composable
private fun Bars() {
    Section("BARS") {
        LcarsBar(
            segments =
                listOf(
                    LcarsSegment(null, LcarsColors.Orange, weight = 0.4f),
                    LcarsSegment("primary", LcarsColors.IceBlue),
                    LcarsSegment("auxiliary", LcarsColors.Lilac),
                    LcarsSegment(null, LcarsColors.GoldenTanoi, weight = 0.3f),
                ),
            modifier = Modifier.fillMaxWidth(),
            capEnd = true,
        )
        LcarsBar(
            segments = listOf(LcarsSegment("single capped bar", LcarsColors.Butterscotch)),
            modifier = Modifier.fillMaxWidth(),
            capStart = true,
            capEnd = true,
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap)) {
        BasicText(text = title, style = LcarsTheme.typography.header.copy(color = LcarsColors.GoldenTanoi))
        content()
    }
}

@Preview(name = "LCARS gallery", widthDp = 600, heightDp = 960, showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun GalleryPreview() = LcarsGallery(Modifier.fillMaxSize())

@Preview(name = "Elbows", widthDp = 600, heightDp = 200, showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun ElbowsPreview() = LcarsTheme { Box(Modifier.background(LcarsColors.Space)) { Elbows() } }

@Preview(name = "Pills", widthDp = 600, heightDp = 160, showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PillsPreview() = LcarsTheme { Box(Modifier.background(LcarsColors.Space)) { Pills() } }
