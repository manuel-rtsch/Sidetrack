package io.github.manuelrtsch.sidetrack

import androidx.compose.runtime.Composable
import io.github.manuelrtsch.sidetrack.playback.SleepTimerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

/**
 * The demo state the screenshots are rendered from: one current track, a seeded history and a
 * full library, all served by [MockSpotifyDataSource]'s fictional pack so no personal content is
 * ever drawn.
 */
internal fun screenshotState(): SidetrackUiState {
    val recent = MockSpotifyDataSource.recentlyPlayed
    val current = recent.first()
    return SidetrackUiState(
        current = current,
        upcoming = recent.drop(1),
        history = recent.drop(3),
        recent = recent,
        libraryPages = mapOf(
            LibraryFilter.Albums to LibraryPage(
                runBlocking { MockSpotifyDataSource.library(LibraryFilter.Albums).items }
            )
        ),
        librarySections = runBlocking {
            LibraryFilter.entries.associateWith { MockSpotifyDataSource.library(it, 0, LIBRARY_SECTION_SIZE).items }
        },
        librarySectionHasMore = LibraryFilter.entries.toSet(),
        searchPages = mapOf(
            "night" to SearchScope.entries.associateWith { scope ->
                val items = runBlocking { MockSpotifyDataSource.search("night", setOf(scope)) }.itemsFor(scope)
                LibraryPage(items = items, hasMore = items.size >= SEARCH_PAGE_SIZE)
            }
        ),
        transport = Transport.Playing,
        // The queue came from the album the current track sits on, so the album page's tile can
        // show Pause and its row can carry the marker.
        origin = (current as? Track)?.takeIf { it.albumId.isNotEmpty() }
            ?.let { MediaKey(it.service, MediaType.Album, it.albumId) },
        savedTrackIds = setOf(current.id),
        filter = LibraryFilter.Albums
    )
}

/**
 * The same playback, further into the current item, so the screenshots show the row's progress
 * line with something on it instead of the empty state every fixture starts at.
 */
internal fun PlaybackUiState.at(positionMs: Long): PlaybackUiState = copy(position = MutableStateFlow(positionMs))

/** The Home screen at one point of the player, as both screenshot suites shoot it. */
@Composable
internal fun HomeShot(state: SidetrackUiState, sleepTimer: SleepTimerState) {
    SidetrackHomeScreen(
        state.playback,
        state.chrome,
        sleepTimer = MutableStateFlow(sleepTimer),
        onNowPlaying = {},
        onLibrary = {},
        onSearch = {},
        onSettings = {},
        onPlay = {},
        onOpenQueue = {},
        onAddToQueue = {},
        onRemoveHistory = { null },
        onHistory = {},
        onDismissNotice = {},
        onDismissNowPlaying = {}
    )
}

/** The Library with its albums page, as both screenshot suites shoot it. */
@Composable
internal fun LibraryShot(state: SidetrackUiState) {
    LibraryScreen(
        state.library,
        state.saved,
        state.playback,
        state.chrome,
        onBack = {},
        onSearch = {},
        onFilter = {},
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
        onDismissNotice = {},
        onDismissNowPlaying = {}
    )
}
