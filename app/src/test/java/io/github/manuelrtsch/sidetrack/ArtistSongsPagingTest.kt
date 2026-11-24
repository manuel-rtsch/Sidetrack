package io.github.manuelrtsch.sidetrack

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.manuelrtsch.sidetrack.data.SpotifyCatalog
import io.github.manuelrtsch.sidetrack.data.SpotifyMediaCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The artist's "All songs" page is a paged list like every other: twenty rows on entry, Load more
 * appends, re-entering falls back to the first batch, and Play queues the catalogue rather than the
 * page that happened to be loaded.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ArtistSongsPagingTest {
    private val dispatcher = StandardTestDispatcher()
    private val viewModels = mutableListOf<SidetrackViewModel>()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        viewModels.clear()
        Dispatchers.resetMain()
    }

    private val artist = MockSpotifyDataSource.artists.first { it.id == "aurora" }
    private val base = MockSpotifyDataSource.playlist("late_night")!!.tracks.first()
    private val catalogue = (0 until 45).map { base.copy(id = "song$it", title = "Song $it") }

    private fun viewModel(catalog: SpotifyCatalog) = SidetrackViewModel(SpotifyMediaCatalog(catalog), simulatePlayback = false).also { viewModels += it }

    private fun catalog(): SpotifyCatalog = object : SpotifyCatalog by MockSpotifyDataSource {
        override suspend fun artistSongs(
            artist: Artist,
            offset: Int,
            limit: Int
        ): Page<Track> = Page.of(catalogue.drop(offset).take(limit), offset, limit, catalogue.size)
    }

    private fun ids(tracks: List<Track>) = tracks.map { it.id }

    @Test fun `the page opens on twenty rows and Load more extends it`() = runTest(dispatcher) {
        val vm = viewModel(catalog())
        vm.ensureArtist(artist.id)
        advanceUntilIdle()

        vm.openArtistSongs(artist)
        advanceUntilIdle()

        assertEquals(ids(catalogue.take(CATALOG_PAGE_SIZE)), ids(vm.artistSongs(artist)))
        assertTrue(vm.state.value.artistSongHasMore.getValue(artist.id))

        vm.loadMoreArtistSongs(artist)
        advanceUntilIdle()

        assertEquals(ids(catalogue.take(CATALOG_PAGE_SIZE * 2)), ids(vm.artistSongs(artist)))
        assertTrue(vm.state.value.artistSongHasMore.getValue(artist.id))

        vm.loadMoreArtistSongs(artist)
        advanceUntilIdle()

        assertEquals(ids(catalogue), ids(vm.artistSongs(artist)))
        assertFalse(vm.state.value.artistSongHasMore.getValue(artist.id))
    }

    @Test fun `reopening the page goes back to its first batch`() = runTest(dispatcher) {
        val vm = viewModel(catalog())
        vm.ensureArtist(artist.id)
        advanceUntilIdle()
        vm.openArtistSongs(artist)
        advanceUntilIdle()
        vm.loadMoreArtistSongs(artist)
        advanceUntilIdle()
        assertEquals(CATALOG_PAGE_SIZE * 2, vm.artistSongs(artist).size)

        vm.openArtistSongs(artist)
        advanceUntilIdle()

        assertEquals(CATALOG_PAGE_SIZE, vm.artistSongs(artist).size)
        assertTrue(vm.state.value.artistSongHasMore.getValue(artist.id))
    }

    @Test fun `play queues the whole catalogue, not just the loaded page`() = runTest(dispatcher) {
        val vm = viewModel(catalog())
        vm.ensureArtist(artist.id)
        advanceUntilIdle()
        vm.openArtistSongs(artist)
        advanceUntilIdle()

        vm.playArtistSongs(artist)
        advanceUntilIdle()

        assertEquals(catalogue.first().id, vm.state.value.current?.id)
        assertEquals(ids(catalogue.drop(1)), vm.state.value.upcoming.map { it.id })
        // The page keeps up, so the list and the queue agree afterwards.
        assertEquals(ids(catalogue), ids(vm.artistSongs(artist)))
        assertFalse(vm.state.value.artistSongHasMore.getValue(artist.id))
    }

    @Test fun `a service without a catalogue answers empty instead of failing`() = runTest(dispatcher) {
        val vm = viewModel(MockSpotifyDataSource)
        vm.ensureArtist(artist.id)
        advanceUntilIdle()

        vm.openArtistSongs(artist)
        advanceUntilIdle()

        assertTrue(vm.artistSongs(artist).isEmpty())
        assertFalse(vm.state.value.artistSongHasMore.getValue(artist.id))
    }
}
