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
 * Album rows share the artist rows' grey meatball gutter. The bar offers Add to queue only;
 * Save/Unsave stays on the album page.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(shadows = [RecordingHapticShadow::class])
class AlbumGutterTest : GutterTestBase() {
    private val album = MockSpotifyDataSource.albums.first()
    private val actions = "Actions for ${album.title}"

    override var key: String = album.key.encode()

    private fun showRow(onQueue: () -> Unit = {}) = showGutterRow { gutter, bar ->
        AlbumGutterRow(key, album, gutter, bar, onAddToQueue = onQueue, onClick = {})
    }

    @Test
    fun optionsOfferQueueOnly() {
        showRow()
        openGutter()
        compose.onNodeWithContentDescription(actions).performClick()
        compose.runOnIdle {
            assertEquals(
                listOf("Add ${album.title} to queue"),
                barState().selection?.actions?.map { it.contentDescription }
            )
        }
    }

    @Test
    fun queueActionQueuesTheAlbum() {
        var queued = 0
        showRow(onQueue = { queued++ })
        openGutter()
        compose.onNodeWithContentDescription(actions).performClick()
        compose.runOnIdle {
            barState().selection?.actions?.first { it.key == "addToQueue" }?.onClick?.invoke()
        }
        assertEquals(1, queued)
    }
}
