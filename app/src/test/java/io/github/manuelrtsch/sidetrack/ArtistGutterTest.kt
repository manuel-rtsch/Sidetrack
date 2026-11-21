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
 * The library's artist rows carry the same grey meatball gutter as every other row. Pressing it
 * publishes Artist radio only; Follow/Unfollow stays on the artist page.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(shadows = [RecordingHapticShadow::class])
class ArtistGutterTest : GutterTestBase() {
    private val artist = MockSpotifyDataSource.artists.first()
    private val actions = "Actions for ${artist.name}"

    override var key: String = artist.key.encode()

    private fun showRow(
        canRadio: Boolean = true,
        onRadio: () -> Unit = {},
        onOpen: () -> Unit = {}
    ) = showGutterRow { gutter, bar ->
        ArtistGutterRow(key, artist, gutter, bar, canRadio = canRadio, onRadio = onRadio, onClick = onOpen)
    }

    @Test
    fun gutterCarriesOneMeatballSquare() {
        showRow()
        openGutter()
        compose.onNodeWithContentDescription(actions).assertExists()
        compose.onNodeWithContentDescription("Follow ${artist.name}").assertDoesNotExist()
        compose.onNodeWithContentDescription("Artist radio for ${artist.name}").assertDoesNotExist()
    }

    @Test
    fun optionsOfferRadioOnly() {
        showRow()
        openGutter()
        compose.onNodeWithContentDescription(actions).performClick()
        compose.runOnIdle {
            assertEquals(
                listOf("Artist radio for ${artist.name}"),
                barState().selection?.actions?.map { it.contentDescription }
            )
        }
    }

    @Test
    fun radioStartsWithoutOpeningTheArtist() {
        var radios = 0
        var opens = 0
        showRow(onRadio = { radios++ }, onOpen = { opens++ })
        openGutter()
        compose.onNodeWithContentDescription(actions).performClick()
        compose.runOnIdle {
            barState().selection?.actions?.first { it.key == "artistRadio" }?.onClick?.invoke()
        }
        assertEquals(1, radios)
        assertEquals(0, opens)
    }

    @Test
    fun noGutterWhenTheServiceHasNoRadio() {
        showRow(canRadio = false)
        openGutter()
        compose.onNodeWithContentDescription(actions).assertDoesNotExist()
    }
}
