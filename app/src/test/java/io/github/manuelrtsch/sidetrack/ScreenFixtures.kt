package io.github.manuelrtsch.sidetrack

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import hrln.interactive.sidekit.design.canvas.SidekitCanvas
import hrln.interactive.sidekit.design.theme.SidekitSpacing
import hrln.interactive.sidekit.design.theme.SidekitTheme
import kotlinx.coroutines.runBlocking

/**
 * The three screens the focus and semantics tests drive, each rendered the way the app renders it:
 * inside the theme, on the canvas, with every callback that test does not exercise already
 * answered.
 *
 * Only what a test drives is a parameter. This one hoists the selected category too, so a swap goes
 * through the screen's own `onFilter` and the list it grows is the catalogue's, which is what the
 * focus tests need to be looking at.
 */
fun ComposeContentTestRule.showLibrary(
    start: LibraryFilter,
    supportedFilters: Set<LibraryFilter> = LibraryFilter.entries.toSet(),
    onFilter: (LibraryFilter) -> Unit = {}
) {
    val pages = LibraryFilter.entries.associateWith {
        runBlocking { MockSpotifyDataSource.library(it).items }
    }
    setContent {
        SidekitTheme(spacing = SidekitSpacing.Compact) {
            SidekitCanvas {
                var filter by remember { mutableStateOf(start) }
                LibraryScreen(
                    LibraryUiState(
                        libraryPages = mapOf(filter to LibraryPage(pages.getValue(filter))),
                        filter = filter
                    ),
                    SavedUiState(),
                    PlaybackUiState(),
                    ChromeUiState(),
                    onBack = {},
                    onSearch = {},
                    onFilter = {
                        onFilter(it)
                        filter = it
                    },
                    onTrack = {},
                    onAddToQueue = {},
                    onPlaylist = {},
                    onAlbum = {},
                    onArtist = {},
                    onShow = {},
                    onSaveTrack = {},
                    onEnsureSavedTrack = {},
                    onLoadMore = {},
                    onNowPlaying = {},
                    onRetry = {},
                    onCreatePlaylist = {},
                    canCreatePlaylist = false,
                    supportedFilters = supportedFilters,
                    onDismissNotice = {},
                    onDismissNowPlaying = {}
                )
            }
        }
    }
    waitForIdle()
}

/** Renders the results of [query] in the categories of [scopes] the service can answer. */
fun ComposeContentTestRule.showSearchResults(
    query: String = "night",
    scopes: Set<SearchScope> = SearchScope.entries.toSet(),
    supportedScopes: Set<SearchScope> = SearchScope.entries.toSet()
) {
    val results = runBlocking { MockSpotifyDataSource.search(query, scopes, 0, 100) }
    val state = SearchUiState(query = query, searchResults = results, searchScopes = scopes)
    setContent {
        SidekitTheme(spacing = SidekitSpacing.Compact) {
            SidekitCanvas {
                SearchResultsScreen(
                    state,
                    SavedUiState(),
                    ChromeUiState(),
                    supportedScopes = supportedScopes,
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
    }
    waitForIdle()
}

/** Renders the search screen with [query] typed into its field. */
fun ComposeContentTestRule.showSearchField(query: String) {
    setContent {
        SidekitTheme(spacing = SidekitSpacing.Compact) {
            SidekitCanvas {
                SearchScreen(
                    SearchUiState(query = query),
                    ChromeUiState(),
                    onBack = {},
                    onSearch = {},
                    onClear = {},
                    onRemoveHistory = {},
                    onScopes = {},
                    onNowPlaying = {},
                    onDismissNotice = {},
                    onDismissNowPlaying = {}
                )
            }
        }
    }
    waitForIdle()
}
