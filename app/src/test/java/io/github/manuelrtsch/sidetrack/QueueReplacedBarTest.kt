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
 * that offer (`ActionBars.kt`, `docs/menus.md`): the named inverse leads, a close square accepts the
 * change, and it only takes the bar when nothing more urgent is asking for it.
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
    fun theOfferNamesWhatComesBackThenACloseSquare() {
        val actions = bar(ChromeUiState(queueReplaced = true))

        assertEquals(listOf("restoreQueue", PROMPT_CLOSE_KEY), actions.map { it.key })
        assertEquals("Restore queue", actions.first().label)
        assertEquals("Restore the queue this tap replaced", actions.first().contentDescription)
        assertEquals("Dismiss", actions.last().contentDescription)
        // The restore is the bar's one action; the square is grey because nothing happens when it
        // is pressed.
        assertEquals(ActionTone.Primary, actions.first().tone)
        assertEquals(ActionTone.Muted, actions.last().tone)
    }

    @Test
    fun restoreBringsTheQueueBackAndDismissWithdraws() {
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

        actions.first().onClick()
        actions.last().onClick()

        assertEquals(1, undone)
        assertEquals(1, dismissed)
    }

    @Test
    fun aNoticeOutranksTheOffer() {
        val actions = bar(ChromeUiState(notice = "Couldn't reach the service", queueReplaced = true))

        assertTrue("A failure is news the user has to read", actions.none { it.key == "restoreQueue" })
    }

    @Test
    fun theOfferOutranksTheNowPlayingConfirmation() {
        // The confirmation would push the offer out for most of its window, and only the offer has
        // a deadline that takes something away.
        val actions = bar(ChromeUiState(queueReplaced = true, nowPlayingNotice = track("A")))

        assertEquals(listOf("restoreQueue", PROMPT_CLOSE_KEY), actions.map { it.key })
    }

    @Test
    fun noOfferMeansTheNormalBar() {
        assertEquals(normal, bar(ChromeUiState()))
        assertNull(bar(ChromeUiState()).firstOrNull { it.key == "restoreQueue" })
    }
}
