package com.hermesconsole.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hermesconsole.lcars.LcarsCap
import com.hermesconsole.lcars.LcarsColors
import com.hermesconsole.lcars.LcarsPill

/**
 * The voice-loop state, rendered (REQ-35). One colour and one word per state, and the word is the
 * state's own name - what the screenshot shows and what the log says can never be two different
 * things.
 *
 * The colours are chosen to read at wall-mount range, by temperature as much as hue: the console
 * warms up as it comes toward you. Amber standing by, ice blue taking you in, violet away thinking,
 * orange - the loudest colour LCARS has - when sound is actually coming out.
 */
val ConsoleState.lcarsColor: Color
    get() = when (this) {
        ConsoleState.READY -> LcarsColors.GoldenTanoi
        ConsoleState.LISTENING -> LcarsColors.IceBlue
        ConsoleState.THINKING -> LcarsColors.AfricanViolet
        ConsoleState.SPEAKING -> LcarsColors.Orange
    }

/** The state's label, as the indicator pill punches it out. */
val ConsoleState.lcarsLabel: String
    get() = name.lowercase()

/**
 * The indicator itself: the pill at the head of the frame that says what the console is doing.
 * It is chrome, not content - the shell keeps it outside the scrolling region, so it is on screen
 * whenever the display is (REQ-35).
 */
@Composable
fun StateIndicator(state: ConsoleState, modifier: Modifier = Modifier) {
    LcarsPill(
        text = state.lcarsLabel,
        modifier = modifier,
        color = state.lcarsColor,
        cap = LcarsCap.End,
        minWidth = 220.dp,
    )
}
