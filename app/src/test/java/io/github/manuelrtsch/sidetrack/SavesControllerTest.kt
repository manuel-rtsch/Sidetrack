package io.github.manuelrtsch.sidetrack

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.manuelrtsch.sidetrack.data.MediaLibraryWriter
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The optimistic write is one shape now: the tile moves at once, the service is told after, and a
 * failure moves it back and says why. Both directions come from the same lambda, so a rollback
 * cannot drift from the move it undoes.
 */
@RunWith(AndroidJUnit4::class)
class SavesControllerTest {

    private val track = Track(
        id = "track",
        title = "Track",
        artist = "Artist",
        artistId = "artist",
        albumId = "album",
        artRes = 0,
        durationMs = 180_000L
    )
    private val artist = Artist(id = "artist", name = "Artist", artRes = 0, albumIds = emptyList())

    @Test
    fun `a failed save puts the track back and says why`() = runTest {
        val store = UiStateStore(SidetrackUiState(signedIn = true))
        val controller = controller(store, this, FailingWriter())

        controller.toggleSaved(track)
        // The flip is visible before the write settles, which is what makes the tile responsive.
        assertTrue(store.value.savedTrackIds.isNotEmpty())
        advanceUntilIdle()

        assertTrue(store.value.savedTrackIds.isEmpty())
        assertNotNull(store.value.notice)
    }

    @Test
    fun `a failed follow puts the artist back and says why`() = runTest {
        val store = UiStateStore(SidetrackUiState(signedIn = true))
        val controller = controller(store, this, FailingWriter())

        controller.toggleFollow(artist)
        assertTrue(store.value.followedArtistIds.isNotEmpty())
        advanceUntilIdle()

        assertTrue(store.value.followedArtistIds.isEmpty())
        assertNotNull(store.value.notice)
    }

    @Test
    fun `a successful save keeps the tile and says nothing`() = runTest {
        val store = UiStateStore(SidetrackUiState(signedIn = true))
        val controller = controller(store, this, FailingWriter(fail = false))

        controller.toggleSaved(track)
        advanceUntilIdle()

        assertEquals(listOf(MediaKeys(ServiceId.Navidrome).track("track").encode()), store.value.savedTrackIds.toList())
        assertEquals(null, store.value.notice)
        assertFalse(store.value.runningTasks.any { it is TaskKey.Action })
    }

    private fun controller(store: UiStateStore, scope: kotlinx.coroutines.CoroutineScope, writer: MediaLibraryWriter): SavesController {
        val mediaKeys = MediaKeys(ServiceId.Navidrome)
        return SavesController(
            store = store,
            writer = writer,
            hints = SavedHints { writer },
            mediaKeys = mediaKeys,
            online = { true },
            scope = scope,
            tasks = TaskRegistry(store, scope)
        )
    }

    private class FailingWriter(private val fail: Boolean = true) : MediaLibraryWriter {
        override suspend fun isSaved(item: MediaKey): Boolean? = null
        override suspend fun isFollowed(artist: MediaKey): Boolean? = null
        override suspend fun setSaved(item: MediaKey, saved: Boolean) {
            if (fail) error("offline")
        }

        override suspend fun setFollowed(artist: MediaKey, followed: Boolean) {
            if (fail) error("offline")
        }

        override suspend fun addToPlaylist(playlist: MediaKey, item: MediaItem): Int? = null
        override suspend fun removeFromPlaylist(playlist: MediaKey, item: MediaItem, index: Int?) = Unit
        override suspend fun renamePlaylist(playlist: MediaKey, name: String): Boolean = false
        override suspend fun deletePlaylist(playlist: MediaKey): Boolean = false
        override suspend fun createPlaylist(name: String, seed: List<MediaKey>): Playlist? = null
    }
}
