package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The playback markers a list row can carry, one per row: an item row draws the line it has got to
 * and a collection row wears the chip; both say "Playing" or "Paused" to a screen reader.
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
    fun `an item row is marked by its line and never by a chip`() {
        showRow(playback(), index = 2, showArt = false)

        // A page without artwork keeps its numbers: the row's mark is the line, not a swap.
        compose.onNodeWithText("3").assertExists()
        compose.onNodeWithTag(ROW_CHIP_TAG, useUnmergedTree = true).assertDoesNotExist()
        rowWith("Playing").assertIsDisplayed()
    }

    @Test
    fun `a collection row wears the chip and draws no line`() {
        compose.setContent {
            SidekitPreviewFrame {
                LibraryRow(
                    artRes = 0,
                    title = "Night Signals",
                    secondary = "Aurora Vale · 2024",
                    audio = playback().audioFor(
                        Album(
                            id = track.albumId,
                            title = "Night Signals",
                            artist = track.artist,
                            artistId = track.artistId,
                            year = 2024,
                            artRes = 0,
                            tracks = listOf(track)
                        )
                    ),
                    onClick = {}
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag(ROW_CHIP_TAG, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(ROW_PROGRESS_TAG, useUnmergedTree = true).assertDoesNotExist()
        rowWith("Playing").assertIsDisplayed()
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
    fun `the line fits a compact row without growing it`() {
        // Five rows fit because the artwork shrinks to 64 dp; a line with a height of its own would
        // push the row past the height the list is solved for.
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
    fun `the explicit badge takes two fifths of the cover`() {
        // The cover is 80 dp in the spacious row, so the badge is 32: at the old 22 dp the "E"
        // read as a smudge on the corner.
        compose.setContent {
            SidekitPreviewFrame {
                MediaRow(MockSpotifyDataSource.track("ns5")!!, onClick = {})
            }
        }
        compose.waitForIdle()

        val bounds = compose.onNodeWithContentDescription("Explicit", useUnmergedTree = true).getBoundsInRoot()
        assertEquals(80f * 0.4f, (bounds.right - bounds.left).value, 0.01f)
        assertEquals(80f * 0.4f, (bounds.bottom - bounds.top).value, 0.01f)
    }

    @Test
    fun `a compact row's badge keeps the same share of its smaller cover`() {
        compose.setContent {
            SidekitPreviewFrame {
                CompositionLocalProvider(LocalCompactRows provides true) {
                    MediaRow(
                        MockSpotifyDataSource.track("ns5")!!,
                        onClick = {}
                    )
                }
            }
        }
        compose.waitForIdle()

        // Two-fifths of 64 is 25.6, rounded to the 26 dp the canvas can draw.
        val bounds = compose.onNodeWithContentDescription("Explicit", useUnmergedTree = true).getBoundsInRoot()
        assertEquals(26f, (bounds.right - bounds.left).value, 0.01f)
    }

    @Test
    fun `the playing chip is the explicit badge's size, not a speck`() {
        compose.setContent {
            SidekitPreviewFrame {
                LibraryRow(
                    artRes = 0,
                    title = "Night Signals",
                    secondary = "Aurora Vale · 2024",
                    audio = playback().audioFor(
                        Album(
                            id = track.albumId,
                            title = "Night Signals",
                            artist = track.artist,
                            artistId = track.artistId,
                            year = 2024,
                            artRes = 0,
                            tracks = listOf(track)
                        )
                    ),
                    onClick = {}
                )
            }
        }
        compose.waitForIdle()

        val bounds = compose.onNodeWithTag(ROW_CHIP_TAG, useUnmergedTree = true).getBoundsInRoot()
        assertEquals(80f * 0.4f, (bounds.right - bounds.left).value, 0.01f)
    }

    @Test
    fun `the line begins and ends where the corner arc swallows it`() {
        // A full-bleed line was hidden under the tile's 36 dp bottom corners: its first seconds
        // fell under the left arc and its last under the right one. Inset by the whole radius it
        // regained the first pixel but showed its square ends. Inset by the arc's crossing at the
        // line's own height, the ends are hidden and the fill starts and stops on the curve.
        assertTrue(RowProgressInset < ItemCornerRadius)
        // The arc crosses the line's upper edge at 19.51 dp; floored to the pixel grid so the end
        // stays under the curve rather than peeking out of it.
        assertEquals(
            kotlin.math.floor(36f - kotlin.math.sqrt(2f * 36f * 4f - 4f * 4f)),
            RowProgressInset.value,
            0.01f
        )
        val flow = MutableStateFlow(0L)
        compose.setContent {
            SidekitPreviewFrame {
                MediaRow(
                    track,
                    modifier = Modifier.testTag("row"),
                    audio = RowAudio(playing = true, position = flow),
                    onClick = {}
                )
            }
        }
        compose.waitForIdle()
        val row = compose.onNodeWithTag("row").getBoundsInRoot()

        flow.value = track.durationMs / 2
        compose.waitForIdle()
        val half = compose.onNodeWithTag(ROW_PROGRESS_FILL_TAG, useUnmergedTree = true).getBoundsInRoot()
        assertEquals(row.left.value + RowProgressInset.value, half.left.value, 0.01f)

        flow.value = track.durationMs
        compose.waitForIdle()
        val full = compose.onNodeWithTag(ROW_PROGRESS_FILL_TAG, useUnmergedTree = true).getBoundsInRoot()
        assertEquals(row.right.value - RowProgressInset.value, full.right.value, 0.01f)
    }

    @Test
    fun `the shared line maps its fraction onto the inset span`() {
        // The playing row and the podcast resume line are the same drawing, so the inset is
        // asserted once here rather than per caller: both ends are swallowed and the fill is a
        // fraction of the span that remains visible.
        compose.setContent {
            SidekitPreviewFrame { ProgressLine(0.5f, Color.Red, Modifier.fillMaxWidth()) }
        }
        compose.waitForIdle()
        val line = compose.onNodeWithTag(ROW_PROGRESS_TAG).getBoundsInRoot()
        val fill = compose.onNodeWithTag(ROW_PROGRESS_FILL_TAG, useUnmergedTree = true).getBoundsInRoot()
        // The tag sits after the padding, so the node is the span that remains between the arcs.
        assertEquals(RowProgressInset.value, line.left.value, 0.01f)
        assertEquals(0f, fill.left.value - line.left.value, 0.01f)
        val lineWidth = line.right.value - line.left.value
        assertEquals(lineWidth / 2f, fill.right.value - fill.left.value, 0.01f)
    }

    @Test
    fun `the line starts empty and fills as soon as the clock moves`() {
        val flow = MutableStateFlow(0L)
        compose.setContent {
            SidekitPreviewFrame {
                MediaRow(track, audio = RowAudio(playing = true, position = flow), onClick = {})
            }
        }
        compose.waitForIdle()
        // The row is marked from its first frame, before there is anything to fill.
        compose.onNodeWithTag(ROW_PROGRESS_TAG, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(ROW_PROGRESS_FILL_TAG, useUnmergedTree = true).assertDoesNotExist()

        flow.value = 60_000L
        compose.waitForIdle()

        // The line heard the tick on its own: the row it hangs under was never recomposed.
        compose.onNodeWithTag(ROW_PROGRESS_FILL_TAG, useUnmergedTree = true).assertExists()
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
