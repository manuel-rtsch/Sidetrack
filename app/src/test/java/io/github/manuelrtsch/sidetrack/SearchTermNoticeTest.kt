package io.github.manuelrtsch.sidetrack

import hrln.interactive.sidekit.design.action.NoticeKind
import io.github.manuelrtsch.sidetrack.data.SpotifyMediaCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Removing one remembered term is not offered back: the term is one keystroke to type again, so the
 * bar says what went instead of asking the user to decide about it.
 */
class SearchTermNoticeTest {

    private fun controller(store: UiStateStore) = SearchController(
        store = store,
        catalog = SpotifyMediaCatalog(MockSpotifyDataSource),
        supportedScopes = { emptySet() },
        online = { true },
        saveSearchHistory = {},
        tasks = TaskRegistry(store, CoroutineScope(Dispatchers.Unconfined))
    )

    @Test
    fun `a removed term is announced`() {
        val store = UiStateStore(SidetrackUiState(searchHistory = listOf("night", "aurora")))
        controller(store).removeSearchHistory("night")

        assertEquals(listOf("aurora"), store.value.searchHistory)
        assertEquals("Removed “night”", store.value.notice)
        assertEquals(NoticeKind.Success, store.value.noticeKind)
    }

    @Test
    fun `a term that is not there says nothing`() {
        val store = UiStateStore(SidetrackUiState(searchHistory = listOf("aurora")))
        controller(store).removeSearchHistory("night")

        assertEquals(null, store.value.notice)
    }
}
