package io.github.manuelrtsch.sidetrack

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(shadows = [RecordingHapticShadow::class])
class ArtistLibraryGutterTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun aCategoryBecomingFocusedDoesNotStealTheArtistOptions() {
        val artist = MockSpotifyDataSource.artists.first()
        compose.showLibrary(LibraryFilter.Artists)
        compose.onNodeWithText(artist.name).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Actions for ${artist.name}").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Artist radio for ${artist.name}").assertExists()
        runCatching {
            compose.onNodeWithContentDescription("Liked Songs").requestFocus()
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Artist radio for ${artist.name}").assertExists()
    }
}
