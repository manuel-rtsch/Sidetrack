package io.github.manuelrtsch.sidetrack

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * A first search keeps the app's loading identity: the record, with the query as the title, not a
 * text row in a list that happens to be empty. The category row stays out of the way until there
 * is something to filter.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchLoadingTest {
    @get:Rule val compose = createComposeRule()

    private fun showLoading() {
        compose.setContent {
            SidekitPreviewFrame {
                SearchResultsScreen(
                    SearchUiState(query = "night", searchPending = true),
                    SavedUiState(),
                    ChromeUiState(),
                    onBack = {},
                    onTrack = {},
                    onAddToQueue = {},
                    onArtist = {},
                    onAlbum = {},
                    onPlaylist = {},
                    onShow = {},
                    onSaveTrack = {},
                    onEnsureSavedTrack = {},
                    onLoadMore = {},
                    onRetry = {},
                    onScopes = {},
                    onNowPlaying = {},
                    onDismissNotice = {},
                    onDismissNowPlaying = {}
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun theFirstSearchShowsTheRecordNamedByTheQuery() {
        showLoading()
        // One node carries the whole block, so the title is a description rather than text.
        compose.onNodeWithContentDescription("Searching for \"night\"…").assertExists()
        compose.onNodeWithContentDescription("Searching").assertDoesNotExist()
        compose.onNodeWithContentDescription("Tracks").assertDoesNotExist()
    }
}
