package io.github.manuelrtsch.sidetrack

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The playback markers a list row can carry: the chip that says "the audio is here" and the
 * state description that says which way the transport is going.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlaybackRowMarkersTest {
    @get:Rule val compose = createComposeRule()

    private val track = MockSpotifyDataSource.track("ns1")!!

    private fun rowWith(value: String) = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value))

    private fun noStateDescription(value: String) = compose.onNode(
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)
    ).assertDoesNotExist()

    private fun playback(
        current: MediaItem? = track,
        transport: Transport = Transport.Playing,
        origin: MediaKey? = null
    ) = PlaybackUiState(current = current, transport = transport, origin = origin)

    private fun showRow(state: PlaybackUiState, index: Int? = null, showArt: Boolean = true) {
        compose.setContent {
            SidekitPreviewFrame {
                MediaRow(
                    track,
                    index = index,
                    showArt = showArt,
                    audio = state.audioFor(track),
                    onClick = {}
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `the row that is playing says so to a screen reader`() {
        showRow(playback())

        rowWith("Playing").assertIsDisplayed()
        compose.onNodeWithText("Afterglow").assertExists()
    }

    @Test
    fun `a paused queue still marks its row, and says it is paused`() {
        showRow(playback(transport = Transport.Paused))

        rowWith("Paused").assertIsDisplayed()
    }

    @Test
    fun `a row that is not the current item says nothing about playback`() {
        showRow(playback(current = MockSpotifyDataSource.track("ns2")!!))

        noStateDescription("Playing")
        noStateDescription("Paused")
    }

    @Test
    fun `the chip takes the track number's place on a page without artwork`() {
        showRow(playback(), index = 2, showArt = false)

        compose.onNodeWithText("3").assertDoesNotExist()
        rowWith("Playing").assertIsDisplayed()
    }

    @Test
    fun `a page without artwork keeps its numbers when nothing is playing there`() {
        showRow(playback(current = MockSpotifyDataSource.track("ns2")!!), index = 2, showArt = false)

        compose.onNodeWithText("3").assertExists()
    }

    @Test
    fun `an album is marked while one of its tracks is playing`() {
        val album = Album(
            id = track.albumId,
            title = "Night Signals",
            artist = track.artist,
            artistId = track.artistId,
            year = 2024,
            artRes = 0,
            tracks = listOf(track)
        )
        val other = album.copy(id = "another-album")

        assertEquals(RowAudio(true, playback().position), playback().audioFor(album))
        assertNull(playback().audioFor(other))
    }

    @Test
    fun `an album with no id is never marked`() {
        val tracks = listOf(track.copy(albumId = ""))
        val album = Album(
            id = "",
            title = "Unknown album",
            artist = track.artist,
            artistId = track.artistId,
            year = 0,
            artRes = 0,
            tracks = tracks
        )

        assertNull(playback(current = tracks.first()).audioFor(album))
    }

    @Test
    fun `a playlist is marked only while the queue came from it`() {
        val playlist = Playlist(
            id = "pl-1",
            name = "Late shift",
            owner = "Aurora Vale",
            ownerId = "aurora",
            artRes = 0,
            tracks = listOf(track)
        )
        val state = playback(origin = playlist.key)

        assertEquals(RowAudio(true, state.position), state.audioFor(playlist))
        assertNull(state.copy(origin = null).audioFor(playlist))
        assertNull(state.copy(origin = MediaKey(playlist.service, MediaType.Playlist, "pl-2")).audioFor(playlist))
    }

    @Test
    fun `an episode marks its show, and an artist is marked by the track's artist id`() {
        val episode = Episode(
            id = "ep-1",
            title = "The long way round",
            show = "Static Coast",
            showId = "show-1",
            published = "2024-05-01",
            artRes = 0,
            durationMs = 1_800_000
        )
        val show = Show(
            id = "show-1",
            name = "Static Coast",
            publisher = "Sidetrack",
            artRes = 0,
            episodes = listOf(episode)
        )
        val artist = Artist(id = track.artistId, name = track.artist, artRes = 0, albumIds = listOf(track.albumId))

        val episodeState = playback(current = episode)
        assertEquals(RowAudio(true, episodeState.position), episodeState.audioFor(show))
        assertNull(episodeState.audioFor(artist))

        val trackState = playback()
        assertEquals(RowAudio(true, trackState.position), trackState.audioFor(artist))
    }

    @Test
    fun `an unrelated state change hands the row an equal marker, so the row can skip`() {
        val before = playback(origin = MediaKey(ServiceId.Spotify, MediaType.Playlist, "pl-1"))
        val after = before.copy(
            upcoming = listOf(track),
            history = listOf(track),
            recent = emptyList()
        )

        assertEquals(before.audioFor(track), after.audioFor(track))
    }

    @Test
    fun `the marker follows the transport rather than the item alone`() {
        val flow = MutableStateFlow(0L)
        val playing = PlaybackUiState(current = track, transport = Transport.Playing, position = flow)
        val paused = playing.copy(transport = Transport.Paused)

        assertEquals(RowAudio(true, flow), playing.audioFor(track))
        assertEquals(RowAudio(false, flow), paused.audioFor(track))
    }

    @Test
    fun `the chip fits a compact row without growing it`() {
        // Five rows fit because the artwork shrinks to 64 dp; a chip with a size of its own would
        // either overflow the cover or push the row past the height the list is solved for.
        compose.setContent {
            SidekitPreviewFrame {
                CompositionLocalProvider(LocalCompactRows provides true) {
                    MediaRow(
                        track,
                        modifier = Modifier.testTag("row"),
                        audio = playback().audioFor(track),
                        onClick = {}
                    )
                }
            }
        }
        compose.waitForIdle()

        val bounds = compose.onNodeWithTag("row").getBoundsInRoot()
        assertEquals(80f, (bounds.bottom - bounds.top).value, 0.001f)
        rowWith("Playing").assertIsDisplayed()
    }

    @Test
    fun `the progress line appears as soon as the clock leaves zero`() {
        val flow = MutableStateFlow(0L)
        compose.setContent {
            SidekitPreviewFrame {
                MediaRow(track, audio = RowAudio(playing = true, position = flow), onClick = {})
            }
        }
        compose.waitForIdle()
        // A line from the first pixel would read as an underline rather than as progress.
        compose.onNodeWithTag(ROW_PROGRESS_TAG, useUnmergedTree = true).assertDoesNotExist()

        flow.value = 60_000L
        compose.waitForIdle()

        // The line heard the tick on its own: the row it hangs under was never recomposed.
        compose.onNodeWithTag(ROW_PROGRESS_TAG, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `the page play tile pauses only for the page the queue was started from`() {
        val album = MediaKey(ServiceId.Spotify, MediaType.Album, "album-1")
        compose.setContent {
            SidekitPreviewFrame {
                ContextPlayTile(
                    page = album,
                    origin = album,
                    transport = Transport.Playing,
                    onPlay = {},
                    onPlayPause = {}
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Pause").assertExists()
    }

    @Test
    fun `the page play tile plays when the queue came from somewhere else`() {
        val album = MediaKey(ServiceId.Spotify, MediaType.Album, "album-1")
        compose.setContent {
            SidekitPreviewFrame {
                ContextPlayTile(
                    page = album,
                    origin = MediaKey(ServiceId.Spotify, MediaType.Playlist, "pl-1"),
                    transport = Transport.Playing,
                    onPlay = {},
                    onPlayPause = {}
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Play").assertExists()
    }
}
