package com.hermesconsole.app

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hermesconsole.hermesclient.HermesMessage
import com.hermesconsole.voiceclient.VoiceManagerInfo
import com.hermesconsole.voiceclient.VoiceManagerModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * T-56 / REQ-38, REQ-43: the shell's content column sticky-follows the output.
 *
 * The bug this pins: T-44 taught the conversation pane to accumulate, so a streamed reply and its
 * panes now grow past the fold - but nothing moved the scroll offset, so the viewport stayed pinned
 * at the top and the newest output fell below it. The fix follows the tail while the reader is at
 * the bottom, gets out of the way the moment they scroll up to read back, and offers a jump-to-latest
 * control to snap back and re-engage the follow.
 *
 * The whole contract is observable off one injected [ScrollState] plus the one control on screen, so
 * this is the shell's only instrumented test that drives the tree (finds and taps a node) rather than
 * asserting on hoisted state - the content here is scripted, no Hermes, no wake path. That the control
 * actually chirps (REQ-43, clause e) is proven structurally by [TouchChirpAuditTest]: it is an
 * [com.hermesconsole.lcars.LcarsPill], so its touch confirms through the kit's cue path by construction.
 */
@RunWith(AndroidJUnit4::class)
class AutoScrollTest {

    @get:Rule
    val compose = createComposeRule()

    private val lines = mutableStateListOf<SpokenTurn>()
    private val scroll = ScrollState(0)
    private val backlog = mutableStateOf<List<HermesMessage>?>(emptyList())

    private fun mount() {
        compose.setContent {
            ConsoleShell(
                state = ConsoleState.READY,
                turn = TurnLog(),
                backlog = emptyList(),
                spoken = lines,
                contentScroll = scroll,
            )
        }
    }

    /**
     * Has the console assembled (T-08, REQ-13)? The ready slot is `console?.let` at the real
     * composition root, so on the wall the whole System Access screen lands async: the attract
     * state holds with a null slot (STANDING BY) while startWallConsole runs, then the assembled
     * console swaps the full screen in. False here is that boot beat, held for the test to end.
     */
    private val consoleUp = mutableStateOf(true)

    /**
     * The T-22 mount, retargeted to the System Access attract content (T-08, REQ-13): the ready
     * slot renders the real [SystemAccessScreen] - fixture healths and readouts, a fixed clock -
     * exactly as the wall's composition root hands it over. The shell stands in a bounded box,
     * deliberately shorter than the real screen: a ScrollState clamps its offset to a shrinking
     * column, so a main screen that fit its viewport would land at 0 with no reset at all and the
     * entry scenario would prove nothing. Bounding the frame keeps the overflow physics real
     * without a scripted row in sight.
     */
    private fun mountWithMainScreen() {
        compose.setContent {
            Box(Modifier.height(FRAME_HEIGHT)) {
                ConsoleShell(
                    state = ConsoleState.READY,
                    turn = TurnLog(),
                    backlog = backlog.value,
                    spoken = lines,
                    contentScroll = scroll,
                    ready = if (consoleUp.value) {
                        {
                            SystemAccessScreen(
                                hermes = BackendHealth.OK,
                                voice = BackendHealth.FAILED,
                                vmInfo = VM_INFO,
                                profile = "tactical",
                                model = "claude-sonnet-5",
                                clock = { FIXED_NOON },
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }

    /** Grow the conversation by [n] tall responses, then let the tree settle. */
    private fun grow(n: Int) {
        compose.runOnUiThread { repeat(n) { lines.add(SpokenTurn(runId = null, prompt = "", reply = LONG_REPLY)) } }
        compose.waitForIdle()
    }

    private fun scrollTo(target: Int) {
        runBlocking { withContext(Dispatchers.Main) { scroll.scrollTo(target) } }
        compose.waitForIdle()
    }

    /**
     * The System Access screen's own header - not the rail segment that wears the same words
     * (both are SYSTEM ACCESS in semantics; the segment's visual two-line wrap changes nothing
     * there). The header is the node inside the scrolling content column; the rail stands
     * outside it, so the scrollable ancestor is the discriminator.
     */
    private fun screenHeader() = compose.onNode(hasText("SYSTEM ACCESS") and hasAnyAncestor(hasScrollAction()))

    @Test
    fun follows_the_output_at_the_bottom_suspends_when_scrolled_up_and_the_jump_control_re_engages() {
        mount()

        // (a) At the bottom (nothing to scroll yet), a turn's worth of output arrives and overflows
        //     the viewport - the column rides the new bottom down so the newest line stays in view.
        grow(40)
        compose.runOnIdle {
            assertTrue("content must overflow for the test to mean anything", scroll.maxValue > 0)
            assertEquals("at the bottom, new output is followed", scroll.maxValue, scroll.value)
        }
        // (d) At the bottom there is nothing to jump to - no control.
        compose.onNodeWithText(JUMP, ignoreCase = true).assertDoesNotExist()

        // (b) The reader scrolls up to read back. More output lands - the view must NOT be yanked
        //     down, and the jump-to-latest control appears.
        scrollTo(0)
        compose.onNodeWithText(JUMP, ignoreCase = true).assertIsDisplayed()
        grow(20)
        compose.runOnIdle {
            assertEquals("scrolled up, new output does not move the view", 0, scroll.value)
            assertTrue("still scrollable", scroll.maxValue > 0)
        }
        compose.onNodeWithText(JUMP, ignoreCase = true).assertIsDisplayed()

        // (c) Tapping the control snaps to the bottom, hides the control, and re-engages the follow.
        compose.onNodeWithText(JUMP, ignoreCase = true).performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("the jump control scrolls to the bottom", scroll.maxValue, scroll.value) }
        compose.onNodeWithText(JUMP, ignoreCase = true).assertDoesNotExist()

        grow(20)
        compose.runOnIdle { assertEquals("follow is re-engaged after the jump", scroll.maxValue, scroll.value) }
    }

    @Test
    fun the_system_access_screen_enters_scrolled_to_the_top_not_at_the_conversations_bottom() {
        // T-22 / REQ-20, retargeted to System Access (T-08, REQ-13): the follow parks the column
        // at the conversation's bottom - that is its job - and the home screen renders in the
        // same column. Without the entry reset (the red this stays against by construction), the
        // header and the com-links - the top of the screen, and the whole point of arriving -
        // sit above a viewport still holding the departed conversation's offset.
        mountWithMainScreen()

        grow(40)
        compose.runOnIdle {
            assertTrue("the conversation must overflow", scroll.maxValue > 0)
            assertEquals("the follow parked the column at the bottom", scroll.maxValue, scroll.value)
        }

        // The visit ends (T-20/T-21): the store's pane goes null and the wall empties - the
        // attract state holds, and the System Access screen takes the column.
        compose.runOnUiThread {
            backlog.value = null
            lines.clear()
        }
        compose.waitForIdle()

        compose.runOnIdle {
            assertTrue("the home screen must overflow its frame, or the clamp could fake this pass", scroll.maxValue > 0)
            assertEquals("the System Access screen enters from the top, no user scroll", 0, scroll.value)
        }
        // What entered is the real home screen, not a stand-in: its header is on the wall, at
        // the top the reset just presented, and the stardate block read the fixed clock.
        screenHeader().assertIsDisplayed()
        compose.onNodeWithText("STARDATE").assertExists()

        // And no conversation chrome over it (T-15, REQ-17): the column sits at the top of an
        // overflowing home screen - the exact shape the ungated control showed over - and the
        // jump-to-latest pill is the conversation's, never the attract state's.
        compose.onNodeWithText(JUMP, ignoreCase = true).assertDoesNotExist()
    }

    @Test
    fun the_async_assembled_home_screen_cannot_drag_the_entry_to_the_bottom() {
        // T-26 / REQ-20, retargeted to the async growth the wall actually has left (T-08,
        // REQ-13): the session list is gone from the attract state, but the whole System Access
        // screen still lands async - the ready slot is `console?.let` at the composition root,
        // so the attract column starts near-empty (STANDING BY) and grows past the fold when the
        // assembled console swaps the full screen in. An entry column has prevMax 0, so an
        // ungated follow's at-the-bottom guard is vacuously true and it would read that growth
        // as new output and ride the home screen to its bottom - the exact wall-run find T-26
        // closed, and the red this scenario stays against by construction.
        backlog.value = null
        consoleUp.value = false
        mountWithMainScreen()
        compose.runOnIdle { assertEquals("the entry starts at the top", 0, scroll.value) }

        // The console assembles: the full System Access screen lands and overflows the frame.
        compose.runOnUiThread { consoleUp.value = true }
        compose.waitForIdle()

        compose.runOnIdle {
            assertTrue("the landed home screen must overflow its frame", scroll.maxValue > 0)
            assertEquals("the entry stays at the top", 0, scroll.value)
        }
        screenHeader().assertIsDisplayed()
    }

    @Test
    fun no_jump_control_while_the_content_fits() {
        mount()
        grow(1)
        compose.runOnIdle { assertEquals("content that fits does not scroll", 0, scroll.maxValue) }
        compose.onNodeWithText(JUMP, ignoreCase = true).assertDoesNotExist()
    }

    private companion object {
        const val JUMP = "jump to latest"

        /** A response tall enough that a handful of them overflow any test viewport. */
        val LONG_REPLY = buildString {
            repeat(6) { append("This is a stored line of the conversation, long enough to take real height on the wall.\n\n") }
        }

        /**
         * The bounded frame the main-screen scenarios stand in: shorter than the real System
         * Access screen, so the home content genuinely overflows and the entry reset has an
         * offset to reset - while STANDING BY (the unassembled slot) still fits with no
         * overflow at all, which is what arms the async scenario's vacuous-bottom shape.
         */
        val FRAME_HEIGHT = 360.dp

        /** The stardate fixture's day, at noon - the same fixed clock the JVM tests pin. */
        val FIXED_NOON: java.time.LocalDateTime = LocalDate.of(2020, 8, 14).atTime(12, 0)

        /** A live VoiceManager readout, so the retargeted screen shows filled blocks, not placeholders. */
        val VM_INFO = VoiceManagerInfo(
            sttEngine = "whisper",
            ttsEngine = "openvoice",
            models = VoiceManagerModels(stt = "large-v3", tts = "openvoice-v2"),
        )
    }
}
