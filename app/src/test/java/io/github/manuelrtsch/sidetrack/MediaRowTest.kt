package io.github.manuelrtsch.sidetrack

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** The media row's list-specific pieces: compact long durations and the explicit-content chip. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaRowTest {
    @get:Rule val compose = createComposeRule()

    private fun showRow(item: MediaItem, index: Int? = null, showArt: Boolean = true) {
        compose.setContent {
            SidekitPreviewFrame {
                MediaRow(item, index = index, showArt = showArt, onClick = {})
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `a duration past 99 minutes drops seconds in the list`() {
        val track = MockSpotifyDataSource.track("ns1")!!.copy(durationMs = 6_000_000)
        showRow(track)

        compose.onNodeWithText("1h 40m").assertExists()
        compose.onNodeWithText("1:40:00").assertDoesNotExist()
    }

    @Test
    fun `a duration below 99 minutes keeps minutes and seconds`() {
        val track = MockSpotifyDataSource.track("ns1")!!.copy(durationMs = 4_530_000)
        showRow(track)

        compose.onNodeWithText("75:30").assertExists()
        compose.onNodeWithText("1h 15m").assertDoesNotExist()
    }

    @Test
    fun `a track row can hide the duration where it is not needed`() {
        val track = MockSpotifyDataSource.track("ns1")!!
        compose.setContent {
            SidekitPreviewFrame {
                val rows = rememberTrackRows(SavedUiState(), {}, {}, {})
                TrackRow("row", track, rows, showDuration = false, onClick = {})
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Afterglow").assertExists()
        compose.onNodeWithText("3:47").assertDoesNotExist()
    }

    @Test
    fun `an explicit track puts the marker on the artwork instead of the artist line`() {
        val track = MockSpotifyDataSource.track("ns5")!!
        showRow(track)

        compose.onNodeWithContentDescription("Explicit", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Aurora Vale").assertExists()
        compose.onNodeWithText("Aurora Vale · E").assertDoesNotExist()
    }

    @Test
    fun `an index row without artwork still shows the explicit marker`() {
        val track = MockSpotifyDataSource.track("rw3")!!
        showRow(track, index = 2, showArt = false)

        compose.onNodeWithContentDescription("Explicit", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("The Static Coast").assertExists()
        compose.onNodeWithText("The Static Coast · E").assertDoesNotExist()
    }
}
