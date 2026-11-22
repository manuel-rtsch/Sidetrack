package io.github.manuelrtsch.sidetrack

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.manuelrtsch.sidetrack.data.SpotifyCatalog
import io.github.manuelrtsch.sidetrack.data.SpotifyMediaCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opening a list puts it back on its first page.
 *
 * A list that was paged deeper and then left used to reopen at whatever length the last visit
 * reached: the Library kept the rows of a category scrolled far down, a playlist kept every page
 * the user had asked for, and a search result set kept every scope's extra pages. Entry is the one
 * moment a list may shrink, and it costs nothing: the first page is already cached, so the collapse
 * makes no request, and the next "Load more" asks for the page after the first twenty.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ListPagingResetTest {
    private val dispatcher = StandardTestDispatcher()
    private val viewModels = mutableListOf<SidetrackViewModel>()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        viewModels.clear()
        Dispatchers.resetMain()
    }

    @Test fun `library reopens on its first page without refetching it`() = runTest(dispatcher) {
        val catalog = PagingCatalog()
        val vm = viewModel(catalog)

        vm.openLibrary()
        advanceUntilIdle()
        assertEquals(CATALOG_PAGE_SIZE, vm.state.value.libraryItems.size)

        vm.loadMoreLibrary()
        advanceUntilIdle()
        assertEquals(CATALOG_PAGE_SIZE * 2, vm.state.value.libraryItems.size)

        // The library's own preload fetches every category once, so the request count is read
        // around the re-entry rather than from the start.
        val fetched = catalog.libraryOffsets.size
        vm.openLibrary()
        advanceUntilIdle()

        assertEquals(CATALOG_PAGE_SIZE, vm.state.value.libraryItems.size)
        assertTrue(vm.state.value.libraryHasMore)
        assertEquals(fetched, catalog.libraryOffsets.size)

        vm.loadMoreLibrary()
        advanceUntilIdle()

        assertEquals(fetched + 1, catalog.libraryOffsets.size)
        assertEquals(CATALOG_PAGE_SIZE, catalog.libraryOffsets.last())
        assertEquals(CATALOG_PAGE_SIZE * 2, vm.state.value.libraryItems.size)
    }

    @Test fun `a collection reopens on its first page without refetching it`() = runTest(dispatcher) {
        val catalog = PagingCatalog()
        val vm = viewModel(catalog)
        val type = CollectionType.Playlist
        val id = "long"

        vm.openCollection(type, id)
        advanceUntilIdle()
        assertEquals(CATALOG_PAGE_SIZE, vm.collection(type, id)!!.items.size)

        vm.loadMoreCollection(type, id)
        advanceUntilIdle()
        assertEquals(CATALOG_PAGE_SIZE * 2, vm.collection(type, id)!!.items.size)

        vm.openCollection(type, id)
        advanceUntilIdle()

        assertEquals(CATALOG_PAGE_SIZE, vm.collection(type, id)!!.items.size)
        assertTrue(vm.collection(type, id)!!.hasMore)
        assertEquals(listOf(0, CATALOG_PAGE_SIZE), catalog.collectionOffsets)
    }

    @Test fun `an artist reopens with both lists on their first page`() = runTest(dispatcher) {
        val catalog = PagingCatalog()
        val vm = viewModel(catalog)
        val id = PagingCatalog.ARTIST_ID

        vm.openArtist(id)
        advanceUntilIdle()
        assertEquals(ARTIST_PAGE_SIZE, vm.state.value.artistAlbums[id]!!.size)
        assertEquals(ARTIST_PAGE_SIZE, vm.state.value.artistTracks[id]!!.size)

        // The two lists share one in-flight guard, so a footer is pressed, settles, then the other.
        vm.loadMoreArtistAlbums(vm.artist(id)!!)
        advanceUntilIdle()
        vm.loadMoreArtistTracks(vm.artist(id)!!)
        advanceUntilIdle()
        assertEquals(ARTIST_PAGE_SIZE * 2, vm.state.value.artistAlbums[id]!!.size)
        assertEquals(ARTIST_PAGE_SIZE * 2, vm.state.value.artistTracks[id]!!.size)

        vm.openArtist(id)
        advanceUntilIdle()

        assertEquals(ARTIST_PAGE_SIZE, vm.state.value.artistAlbums[id]!!.size)
        assertEquals(ARTIST_PAGE_SIZE, vm.state.value.artistTracks[id]!!.size)
        assertEquals(true, vm.state.value.artistAlbumHasMore[id])
        assertEquals(true, vm.state.value.artistTrackHasMore[id])
        // One fetch for the artist page, one per "Load more": re-entry adds none.
        assertEquals(listOf(0, ARTIST_PAGE_SIZE), catalog.albumOffsets)
        assertEquals(listOf(0, ARTIST_PAGE_SIZE), catalog.topTrackOffsets)
    }

    @Test fun `search results reopen on their first page`() = runTest(dispatcher) {
        val catalog = PagingCatalog()
        val vm = viewModel(catalog)

        vm.submitSearch(PagingCatalog.QUERY)
        advanceUntilIdle()
        val scope = SearchScope.Tracks
        assertEquals(SEARCH_PAGE_SIZE, vm.state.value.searchPagesForQuery[scope]!!.items.size)

        vm.loadMoreSearch(scope)
        advanceUntilIdle()
        assertEquals(SEARCH_PAGE_SIZE * 2, vm.state.value.searchPagesForQuery[scope]!!.items.size)

        vm.openSearchResults()
        advanceUntilIdle()

        assertEquals(SEARCH_PAGE_SIZE, vm.state.value.searchPagesForQuery[scope]!!.items.size)
        assertTrue(scope in vm.state.value.searchHasMore)
        assertEquals(listOf(0, SEARCH_PAGE_SIZE), catalog.searchOffsets)
    }

    private fun viewModel(catalog: SpotifyCatalog): SidetrackViewModel = SidetrackViewModel(
        SpotifyMediaCatalog(catalog),
        // The runtime only carries the signed-in flag the library and search entries check; the
        // catalog above is what answers, so the fixture decides every page.
        session = RecordingServiceRuntime(signedInFlow = MutableStateFlow(true)),
        simulatePlayback = false
    ).also { viewModels += it }

    /** A catalog with more rows than one page in every list the app pages. */
    private class PagingCatalog : SpotifyCatalog by MockSpotifyDataSource {
        val libraryOffsets = mutableListOf<Int>()
        val collectionOffsets = mutableListOf<Int>()
        val albumOffsets = mutableListOf<Int>()
        val topTrackOffsets = mutableListOf<Int>()
        val searchOffsets = mutableListOf<Int>()

        private val rows = (0 until CATALOG_PAGE_SIZE * 2 + 5).map { track("row$it") }
        private val albums = rows.map { album(it.albumId) }.distinctBy { it.id }
        private val solo = Artist(ARTIST_ID, "Ada", 0, albums.map { it.id })

        override suspend fun library(filter: LibraryFilter, offset: Int, limit: Int): Page<LibraryEntry> {
            libraryOffsets += offset
            return Page.of(rows.drop(offset).take(limit), offset, limit, rows.size)
        }

        override suspend fun collection(type: CollectionType, id: String, offset: Int, limit: Int): Collection {
            collectionOffsets += offset
            val items = rows.drop(offset).take(limit)
            return Collection(
                type = type,
                id = id,
                title = "Long",
                subtitle = "You · ${rows.size} tracks",
                artRes = 0,
                items = items,
                totalItems = rows.size,
                hasMore = offset + items.size < rows.size
            )
        }

        override suspend fun artist(id: String): Artist? = solo.takeIf { it.id == id }

        override suspend fun albumsBy(artist: Artist, offset: Int, limit: Int): Page<Album> {
            albumOffsets += offset
            return Page.of(albums.drop(offset).take(limit), offset, limit, albums.size)
        }

        override suspend fun topTracks(artist: Artist, offset: Int, limit: Int): Page<Track> {
            topTrackOffsets += offset
            return Page.of(rows.drop(offset).take(limit), offset, limit, rows.size)
        }

        override suspend fun search(query: String, scopes: Set<SearchScope>, offset: Int, limit: Int): SearchResults {
            searchOffsets += offset
            return SearchResults(
                tracks = if (SearchScope.Tracks in scopes) rows.drop(offset).take(limit) else emptyList()
            )
        }

        companion object {
            const val ARTIST_ID = "ada"
            const val QUERY = "row"
        }
    }

    private companion object {
        fun track(id: String) = Track(id, "Row $id", "Ada", PagingCatalog.ARTIST_ID, "al$id", 0, 180_000L)

        fun album(id: String) = Album(id, "Album $id", "Ada", PagingCatalog.ARTIST_ID, 2024, 0, emptyList())
    }
}
