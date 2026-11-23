package io.github.manuelrtsch.sidetrack

import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.manuelrtsch.sidetrack.playback.SleepTimerState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NowPlayingTileTest {
    @get:Rule val compose = createComposeRule()

    private val track = Track(
        id = "track",
        title = "A track",
        artist = "An artist",
        artistId = "artist",
        albumId = "album",
        artRes = 0,
        durationMs = 180_000L
    )

    private fun showTile(transport: Transport) {
        compose.setContent {
            SidekitPreviewFrame {
                NowPlayingTile(
                    track,
                    transport,
                    remember { MutableStateFlow(SleepTimerState.Off) },
                    onClick = {}
                )
            }
        }
    }

    @Test
    fun bufferingKeepsTheStartingArc() {
        showTile(Transport.Buffering)

        compose.onNodeWithContentDescription("Starting playback").assertExists()
    }

    @Test
    fun playingShowsNoLoadingMotion() {
        showTile(Transport.Playing)

        compose.onNodeWithContentDescription("Starting playback").assertDoesNotExist()
    }
}
