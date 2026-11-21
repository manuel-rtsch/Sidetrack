package io.github.manuelrtsch.sidetrack

import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.icons.SidekitIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A collection tap replaces the queue, so the bar offers it back for a few seconds. These tests pin
 * that offer (`ActionBars.kt`, `docs/menus.md`): it is two entries, what happened and Undo, and it
 * only takes the bar when nothing more urgent is asking for it.
 */
class QueueReplacedBarTest {

    private fun track(id: String) = Track(
        id = id,
        title = id,
        artist = "Artist",
        artistId = "artist",
        albumId = "album",
        artRes = 0,
        durationMs = 180_000L
    )

    private val normal = listOf(
        SideAction("back", "Back", SidekitIcon.ArrowLeftAlt, onClick = {})
    )

    private fun bar(
        chrome: ChromeUiState,
        showNowPlayingNotice: Boolean = true,
        onUndo: () -> Unit = {}
    ) = sidetrackActions(
        chrome = chrome,
        onDismissNotice = {},
        onOpenNowPlaying = {},
        onDismissNowPlaying = {},
        actions = normal,
        onUndoQueueReplace = onUndo,
        showNowPlayingNotice = showNowPlayingNotice
    )

    @Test
    fun theOfferIsWhatHappenedThenUndo() {
        val actions = bar(ChromeUiState(queueReplaced = true))

        assertEquals(listOf("queueReplaced", "undoQueueReplace"), actions.map { it.key })
        assertEquals("Queue replaced", actions.first().label)
        assertEquals("Undo", actions.last().label)
        // The label only says what happened; the single live entry is the Undo.
        assertEquals(ActionTone.Muted, actions.first().tone)
    }

    @Test
    fun undoRunsTheOfferAndTheLabelWithdrawsIt() {
        var undone = 0
        var dismissed = 0
        val actions = sidetrackActions(
            chrome = ChromeUiState(queueReplaced = true),
            onDismissNotice = { dismissed++ },
            onOpenNowPlaying = {},
            onDismissNowPlaying = {},
            actions = normal,
            onUndoQueueReplace = { undone++ }
        )

        actions.last().onClick()
        actions.first().onClick()

        assertEquals(1, undone)
        assertEquals(1, dismissed)
    }

    @Test
    fun aNoticeOutranksTheOffer() {
        val actions = bar(ChromeUiState(notice = "Couldn't reach the service", queueReplaced = true))

        assertTrue("A failure is news the user has to read", actions.none { it.key == "undoQueueReplace" })
    }

    @Test
    fun theOfferOutranksTheNowPlayingConfirmation() {
        // The confirmation would push the offer out for most of its window, and only the offer has
        // a deadline that takes something away.
        val actions = bar(ChromeUiState(queueReplaced = true, nowPlayingNotice = track("A")))

        assertEquals(listOf("queueReplaced", "undoQueueReplace"), actions.map { it.key })
    }

    @Test
    fun noOfferMeansTheNormalBar() {
        assertEquals(normal, bar(ChromeUiState()))
        assertNull(bar(ChromeUiState()).firstOrNull { it.key == "queueReplaced" })
    }
}
