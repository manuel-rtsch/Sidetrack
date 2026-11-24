package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.ChromeState
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.canvas.SidekitCanvas
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitSpacing
import hrln.interactive.sidekit.design.theme.SidekitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The Options bar and the contextual row bar both own the action bar. They must dismiss each
 * other instead of stacking, and a row selection must not survive hidden behind Options.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RowActionBarTest {
    @get:Rule val compose = createComposeRule()

    private val selection = RowActionSelection(
        key = "row",
        title = "Row",
        actions = listOf(
            RowAction(
                key = "addToQueue",
                label = "Add to queue",
                icon = SidekitIcon.PlaylistAdd,
                onClick = {}
            )
        )
    )

    private fun showHost(chrome: ChromeState, onState: (RowActionBarState) -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                SidekitTheme(spacing = SidekitSpacing.Compact) {
                    SidekitCanvas(chrome = chrome) {
                        Host(onState)
                    }
                }
            }
        }
    }

    @Test
    fun openingRowActionsClosesOptions() {
        val chrome = ChromeState()
        lateinit var state: RowActionBarState
        showHost(chrome) { state = it }

        compose.runOnIdle { chrome.toggleOptions() }
        compose.waitForIdle()
        assertTrue(chrome.optionsOpen)

        compose.runOnIdle { state.show(selection) }
        compose.waitForIdle()

        assertFalse(chrome.optionsOpen)
        assertEquals("row", state.selection?.key)
    }

    @Test
    fun theRestoreOfferNamesWhatComesBack() {
        val chrome = ChromeState()
        lateinit var state: RowActionBarState
        showHost(chrome) { state = it }

        compose.runOnIdle {
            state.showRestore(
                PendingRestore(
                    label = "Restore Night Signals",
                    description = "Restore Night Signals to Liked Songs",
                    onClick = {}
                )
            )
        }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Restore Night Signals to Liked Songs").assertExists()
        compose.onNodeWithText("Restore Night Signals").assertExists()
    }

    @Test
    fun aConfirmedRemovalLeavesTheCursorARowToLandOn() {
        val state = RowActionBarState()
        state.show(
            RowActionSelection(
                key = "night",
                title = "Remove Night Signals?",
                confirm = true,
                neighbourKey = "aurora",
                actions = rowActions(confirmRemoveFromPlaylistRowAction("Night Signals") {})
            )
        )

        // The question lands on the shared close square, so Enter cancels.
        assertEquals(PROMPT_CLOSE_KEY, rowActionBarActions(state, emptyList()).last().key)

        state.close()
        // The row is gone once the answer is yes: the neighbour is what the cursor can reach.
        assertEquals("aurora", state.pendingReturn?.neighbour)
    }

    @Test
    fun refusingARestoreHandsTheCursorToTheNeighbour() {
        val state = RowActionBarState()
        state.showRestore(
            PendingRestore(
                label = "Restore track",
                description = "Restore Night Signals to history",
                onClick = {},
                returnKey = "night",
                neighbourKey = "aurora"
            )
        )

        state.clearRestore()

        assertEquals("night", state.pendingReturn?.key)
        assertEquals("aurora", state.pendingReturn?.neighbour)
    }

    @Test
    fun rowActionsPutTheDestructiveButtonLastAndDropWhatTheRowCannotOffer() {
        val actions = rowActions(
            removeFromQueueRowAction("A") {},
            if (false) moveRowAction("A") {} else null,
            addToQueueRowAction("A") {}
        )

        assertEquals(listOf("addToQueue", "remove"), actions.map { it.key })
        assertEquals(ActionTone.Primary, actions.first().tone)
        assertEquals(ActionTone.Destructive, actions.last().tone)
    }

    @Test
    fun playOpensACollectionRowAndItsLabelStaysShort() {
        // The order the caller lists them in is the order the bar shows, so Play leads the
        // collection row's three squares the way `TrackRow` builds it.
        val actions = rowActions(
            playRowAction("A") {},
            addToQueueRowAction("A") {},
            addToPlaylistRowAction("A") {}
        )

        assertEquals(listOf("play", "addToQueue", "addToPlaylist"), actions.map { it.key })
        // Three actions drop the label, so it has to read on its own and the description carries
        // the sentence a sighted user would otherwise get from the bar.
        assertEquals("Play", playRowAction("A") {}.label)
        assertEquals("Play A now", playRowAction("A") {}.contentDescription)
        assertEquals(ActionTone.Primary, playRowAction("A") {}.tone)
    }

    @Test
    fun theCollectionRowSquaresDoNotRepeatAGlyph() {
        // Play, Add to queue and Add to playlist sit side by side, and three squares are icon-only:
        // two of them sharing a glyph makes the bar read as the same action offered twice.
        val actions = rowActions(
            playRowAction("A") {},
            addToQueueRowAction("A") {},
            addToPlaylistRowAction("A") {}
        )

        assertEquals(actions.size, actions.map { it.icon }.distinct().size)
    }

    @Test
    fun aRowActionIsLiveByDefaultAndTheConfirmationIsRed() {
        val menu = rowActions(
            removeFromPlaylistRowAction("A") {},
            addToPlaylistRowAction("A") {}
        )

        assertEquals(ActionTone.Primary, menu.first { it.key == "addToPlaylist" }.tone)
        assertEquals(ActionTone.Destructive, confirmRemoveFromPlaylistRowAction("A") {}.tone)
        // The question is not a menu: the unsafe entry is the only one, and the bar's own close
        // square is the way out of it.
        val question = rowActions(confirmRemoveFromPlaylistRowAction("A") {})
        assertEquals(listOf("confirmRemove"), question.map { it.key })
    }

    @Test
    fun aConfirmationOffersTheActionAndTheCloseSquareOnly() {
        // Removing from a playlist cannot be taken back at its old position, so the bar asks; the
        // close square is the "no" and no second Cancel button is needed.
        val chrome = ChromeState()
        lateinit var state: RowActionBarState
        showHost(chrome) { state = it }

        compose.runOnIdle {
            state.show(
                RowActionSelection(
                    key = "row",
                    title = "Remove Night Signals?",
                    confirm = true,
                    actions = listOf(confirmRemoveFromPlaylistRowAction("Night Signals") {})
                )
            )
        }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Remove Night Signals from this playlist").assertExists()
        compose.onNodeWithContentDescription("Close actions for Remove Night Signals?").assertExists()
        compose.onNodeWithContentDescription("Cancel").assertDoesNotExist()
    }

    @Test
    fun openingOptionsClosesRowActions() {
        val chrome = ChromeState()
        lateinit var state: RowActionBarState
        showHost(chrome) { state = it }

        compose.runOnIdle { state.show(selection) }
        compose.waitForIdle()
        assertEquals("row", state.selection?.key)

        compose.runOnIdle { chrome.toggleOptions() }
        compose.waitForIdle()

        assertTrue(chrome.optionsOpen)
        assertNull(state.selection)
    }
}

@Composable
private fun Host(onState: (RowActionBarState) -> Unit) {
    val state = rememberRowActionBarState()
    SideEffect { onState(state) }
    SidekitScaffold(
        title = "Test",
        actions = rowActionBarActions(
            state,
            listOf(SideAction("normal", "Normal", onClick = {}))
        ),
        options = listOf(SideAction("option", "Option", onClick = {}))
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding))
    }
}
