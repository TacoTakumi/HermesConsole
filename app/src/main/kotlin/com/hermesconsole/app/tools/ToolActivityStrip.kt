package com.hermesconsole.app.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.hermesconsole.lcars.LcarsColors
import com.hermesconsole.lcars.LcarsCue
import com.hermesconsole.lcars.LcarsMetrics
import com.hermesconsole.lcars.LcarsTheme
import com.hermesconsole.lcars.LocalLcarsCues

/**
 * One tool line as a small `> <tool>` row that expands on tap (REQ-02, REQ-03; T-08/T-10).
 *
 * A compact row - a chevron and the tool name, `> execute_code` - collapsed by default, no boxed
 * panel and no summary or duration crowding it. A tap toggles this row's body (REQ-03); the expanded
 * body is the reader's readable, bounded text - never a JSON envelope (REQ-06, D-03) - with the
 * reader's truncation marker when it overran the cap (REQ-08). A failed call tints its name and
 * carries a small ERROR mark (REQ-02). The conversation renderer weaves this row inline into a reply
 * at the point the tool fired (T-10), so a tool reads where it ran, not in a trailing strip.
 */
@Composable
fun ToolLineRow(line: ToolLine) {
    var expanded by remember { mutableStateOf(false) }
    val accent = if (line.isError) LcarsColors.Mars else LcarsColors.GoldenTanoi
    // The wall has no ripple or haptic; a touch is confirmed by the kit's chirp alone (REQ-39/REQ-43).
    // This row is a raw pressable, not a kit component, so it sounds CONFIRM itself.
    val cues = LocalLcarsCues.current

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    cues(LcarsCue.CONFIRM)
                    expanded = !expanded
                }
                .padding(vertical = LcarsMetrics.Gap),
            horizontalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap * 2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(">", style = LcarsTheme.typography.readout.copy(color = LcarsColors.Bluey))
            BasicText(
                text = line.tool,
                style = LcarsTheme.typography.readout.copy(color = accent, fontSize = 18.sp),
            )
            if (line.isError) {
                BasicText("ERROR", style = LcarsTheme.typography.readout.copy(color = LcarsColors.Mars))
            }
        }

        // The body reveals only when the row is open. A row with nothing to show says so when
        // opened, so a tap never looks like it did nothing. Indented under the row, soft-wrapped:
        // the content column never scrolls sideways (REQ-38).
        if (expanded) {
            BasicText(
                text = line.body.ifBlank { ToolActivityRouter.NO_OUTPUT_NOTE },
                style = LcarsTheme.typography.body.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    color = LcarsColors.IceBlue,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = LcarsMetrics.Gap * 4, top = LcarsMetrics.Gap, bottom = LcarsMetrics.Gap * 2),
            )
        }
    }
}
