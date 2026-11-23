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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A page that did not arrive is not the end of the list.
 *
 * A failed "Load more" used to set `hasMore = false` and retire the footer, so the rest of a
 * playlist could not be reached at all after one bad request — while a failed first page offered
 * "Try again". The footer now keeps its rows, keeps `hasMore`, and turns into the retry.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class LoadMoreRetryTest {
    private val dispatcher = StandardTestDispatcher()
    private val viewModels = mutableListOf<SidetrackViewModel>()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        viewModels.clear()
        Dispatchers.resetMain()
    }

    @Test fun `a failed library page keeps its rows and its footer`() = runTest(dispatcher) {
        val catalog = PagingCatalog()
        val vm = viewModel(catalog)

        vm.openLibrary()
        advanceUntilIdle()
        assertEquals(CATALOG_PAGE_SIZE, vm.state.value.libraryItems.size)

        catalog.failPages = true
        vm.loadMoreLibrary()
        advanceUntilIdle()

        assertEquals(CATALOG_PAGE_SIZE, vm.state.value.libraryItems.size)
        assertTrue("a page that failed is not the last page", vm.state.value.libraryHasMore)
        assertEquals("Couldn't reach the service", vm.state.value.library.libraryMoreError)

        // The retry is the same press, and it clears both the failure and the footer's state.
        catalog.failPages = false
        vm.loadMoreLibrary()
        advanceUntilIdle()

        assertEquals(CATALOG_PAGE_SIZE * 2, vm.state.value.libraryItems.size)
        assertNull(vm.state.value.library.libraryMoreError)
        assertTrue(vm.state.value.libraryHasMore)
    }

    @Test fun `a failed collection page keeps its rows and its footer`() = runTest(dispatcher) {
        val catalog = PagingCatalog()
        val vm = viewModel(catalog)
        val type = CollectionType.Playlist
        val id = "long"

        vm.openCollection(type, id)
        advanceUntilIdle()
        assertEquals(CATALOG_PAGE_SIZE, vm.collection(type, id)!!.items.size)

        catalog.failPages = true
        vm.loadMoreCollection(type, id)
        advanceUntilIdle()

        val failed = vm.collection(type, id)!!
        assertEquals(CATALOG_PAGE_SIZE, failed.items.size)
        assertTrue(failed.hasMore)
        // One failure, on this collection's own key: every other footer stays idle.
        assertEquals("Couldn't reach the service", vm.state.value.library.collectionMoreErrors.values.single())

        catalog.failPages = false
        vm.loadMoreCollection(type, id)
        advanceUntilIdle()

        assertEquals(CATALOG_PAGE_SIZE * 2, vm.collection(type, id)!!.items.size)
        assertTrue(vm.state.value.library.collectionMoreErrors.isEmpty())
    }

    @Test fun `a collection the catalog ends on still drops its footer`() = runTest(dispatcher) {
        val catalog = PagingCatalog().apply { endCollections = true }
        val vm = viewModel(catalog)
        val type = CollectionType.Playlist
        val id = "long"

        vm.openCollection(type, id)
        advanceUntilIdle()
        vm.loadMoreCollection(type, id)
        advanceUntilIdle()

        val page = vm.collection(type, id)!!
        assertFalse(page.hasMore)
        assertTrue(vm.state.value.library.collectionMoreErrors.isEmpty())
    }

    private fun viewModel(catalog: SpotifyCatalog): SidetrackViewModel = SidetrackViewModel(
        SpotifyMediaCatalog(catalog),
        session = RecordingServiceRuntime(signedInFlow = MutableStateFlow(true)),
        simulatePlayback = false
    ).also { viewModels += it }
}
