package io.github.manuelrtsch.sidetrack

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Playlists and shows use the same meatball gutter as tracks, albums and artists; it carries only
 * the non-destructive queue action.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(shadows = [RecordingHapticShadow::class])
class PlaylistShowGutterTest : GutterTestBase() {
    private val playlist = MockSpotifyDataSource.playlists.first()
    private val show = MockSpotifyDataSource.shows.first()

    override var key: String = playlist.key.encode()

    private fun showPlaylist(onQueue: () -> Unit = {}) = showGutterRow { gutter, bar ->
        PlaylistGutterRow(key, playlist, gutter, bar, onAddToQueue = onQueue, onClick = {})
    }

    private fun showShow(onQueueLatest: () -> Unit = {}) = showGutterRow { gutter, bar ->
        ShowGutterRow(key, show, gutter, bar, onAddLatestToQueue = onQueueLatest, onClick = {})
    }

    @Test
    fun playlistOffersQueueOnly() {
        showPlaylist()
        openGutter()
        compose.onNodeWithContentDescription("Actions for ${playlist.name}").performClick()
        compose.runOnIdle {
            assertEquals(
                listOf("Add ${playlist.name} to queue"),
                barState().selection?.actions?.map { it.contentDescription }
            )
        }
    }

    @Test
    fun showOffersLatestEpisodeOnly() {
        key = show.key.encode()
        showShow()
        openGutter()
        compose.onNodeWithContentDescription("Actions for ${show.name}").performClick()
        compose.runOnIdle {
            assertEquals(
                listOf("Add latest episode of ${show.name} to queue"),
                barState().selection?.actions?.map { it.contentDescription }
            )
        }
    }

    @Test
    fun showLatestActionQueues() {
        key = show.key.encode()
        var queued = 0
        showShow(onQueueLatest = { queued++ })
        openGutter()
        compose.onNodeWithContentDescription("Actions for ${show.name}").performClick()
        compose.runOnIdle {
            // The show queues its latest episode, but it is the same entry as any other add-to-queue.
            barState().selection?.actions?.first { it.key == "addToQueue" }?.onClick?.invoke()
        }
        assertEquals(1, queued)
    }
}
