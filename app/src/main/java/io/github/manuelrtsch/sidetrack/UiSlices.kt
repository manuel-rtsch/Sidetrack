package io.github.manuelrtsch.sidetrack

import hrln.interactive.sidekit.design.action.NoticeKind

/**
 * Focused views of [SidetrackUiState].
 *
 * The screen state used to be one large data class, so any change (a notice, a save toggle, a
 * search keystroke) produced a new instance and recomposed every screen that collected it. Each
 * slice below is a small, structurally comparable value: a screen collects only the slices it
 * reads, and a change to an unrelated slice no longer reaches it.
 *
 * The slices are derived from the single [SidetrackUiState] source of truth, so the ViewModel's
 * update sites stay unchanged.
 */
data class PlaybackUiState(
    val current: MediaItem? = null,
    val upcoming: List<MediaItem> = emptyList(),
    val history: List<MediaItem> = emptyList(),
    val recent: List<MediaItem> = emptyList(),
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.Off
)

/**
 * The library as the screen reads it. Every category the user has opened stays in [libraryPages],
 * and the open one is a projection of that cache, so switching back to a category is a repaint
 * rather than a refetch.
 */
data class LibraryUiState(
    val libraryPages: Map<LibraryFilter, LibraryPage> = emptyMap(),
    val librarySections: Map<LibraryFilter, List<Any>> = emptyMap(),
    val librarySectionHasMore: Set<LibraryFilter> = emptySet(),
    /** Categories fetching their first page with nothing cached to show meanwhile. */
    val libraryPendingPages: Set<LibraryFilter> = emptySet(),
    val libraryMorePendingPages: Set<LibraryFilter> = emptySet(),
    val filter: LibraryFilter = LibraryFilter.LikedSongs,
    val collections: Map<String, Collection> = emptyMap(),
    val artists: Map<String, Artist> = emptyMap(),
    val artistAlbums: Map<String, List<Album>> = emptyMap(),
    val artistTracks: Map<String, List<Track>> = emptyMap(),
    val pendingCollections: Set<String> = emptySet(),
    val pendingArtists: Set<String> = emptySet(),
    val collectionErrors: Map<String, String> = emptyMap(),
    val artistErrors: Map<String, String> = emptyMap(),
    val collectionMorePending: Set<String> = emptySet(),
    val artistAlbumHasMore: Map<String, Boolean> = emptyMap(),
    val artistTrackHasMore: Map<String, Boolean> = emptyMap(),
    val artistMorePending: Set<String> = emptySet(),
    /** Failed "load more" requests: the page kept its rows, so the footer offers the retry. */
    val libraryMoreErrors: Map<LibraryFilter, String> = emptyMap(),
    val collectionMoreErrors: Map<String, String> = emptyMap(),
    val artistMoreErrors: Map<String, String> = emptyMap()
) {
    val libraryItems: List<Any> get() = libraryPages[filter]?.items.orEmpty()
    val libraryHasMore: Boolean get() = libraryPages[filter]?.hasMore == true
    val libraryError: String? get() = libraryPages[filter]?.error
    val libraryLoading: Boolean get() = filter in libraryPendingPages
    val libraryMorePending: Boolean get() = filter in libraryMorePendingPages
    val libraryMoreError: String? get() = libraryMoreErrors[filter]
}

data class SearchUiState(
    val searchResults: SearchResults = SearchResults(),
    val searchPending: Boolean = false,
    val searchError: String? = null,
    val searchHasMore: Set<SearchScope> = emptySet(),
    val searchMorePending: Set<SearchScope> = emptySet(),
    val searchScopes: Set<SearchScope> = SearchScope.entries.toSet(),
    val searchHistory: List<String> = emptyList(),
    val query: String = ""
)

data class AccountUiState(
    val signedIn: Boolean = false,
    val authPending: Boolean = false,
    val audioQuality: AudioQuality = AudioQuality.High,
    val normalization: Boolean = false,
    val gapless: Boolean = true,
    val autoplay: Boolean = true,
    val cacheSizeBytes: Long = 0L
)

data class SavedUiState(
    val savedTrackIds: Set<String> = emptySet(),
    val savedCollectionKeys: Set<String> = emptySet(),
    val followedArtistIds: Set<String> = emptySet()
)

/**
 * The transient action-bar state: a notice, the queue-replaced offer, or the now-playing
 * confirmation.
 *
 * [noticeKind] decides how long the notice owns the bar. It travels with [notice] rather than
 * living in the screen, so the bar's timer is the same on every screen and a later success cannot
 * inherit an earlier error's duration. [queueReplaced] outranks the now-playing confirmation: a
 * queue that can still be taken back is news, the confirmation is decoration.
 */
data class ChromeUiState(
    val notice: String? = null,
    val noticeKind: NoticeKind = NoticeKind.Other,
    val nowPlayingNotice: MediaItem? = null,
    val queueReplaced: Boolean = false
)

data class PlaylistUiState(
    val choices: List<Playlist> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null
)

data class RadioUiState(
    val radio: Collection? = null,
    val radioSeedId: String? = null,
    val radioPending: Boolean = false,
    val radioError: String? = null,
    val artistRadio: Collection? = null,
    val artistRadioArtistId: String? = null,
    val artistRadioPending: Boolean = false,
    val artistRadioError: String? = null
)

val SidetrackUiState.playback: PlaybackUiState
    get() = PlaybackUiState(current, upcoming, history, recent, isPlaying, isBuffering, shuffle, repeat)

private inline fun <reified K : TaskKey> Map<TaskKey, String>.byKey(): Map<K, String> = entries.mapNotNull { (key, message) -> (key as? K)?.let { it to message } }.toMap()

val SidetrackUiState.library: LibraryUiState
    get() = LibraryUiState(
        libraryPages = libraryPages,
        librarySections = librarySections,
        librarySectionHasMore = librarySectionHasMore,
        // The paging flags are projections of the record now: one writer for the work in flight,
        // and the same identity on the controller side and the footer side.
        libraryPendingPages = runningTasks.filterIsInstance<TaskKey.Library>().mapTo(mutableSetOf()) { it.filter },
        libraryMorePendingPages = runningTasks.filterIsInstance<TaskKey.LibraryMore>().mapTo(mutableSetOf()) { it.filter },
        filter = filter,
        collections = collections,
        artists = artists,
        artistAlbums = artistAlbums,
        artistTracks = artistTracks,
        pendingCollections = runningTasks.filterIsInstance<TaskKey.Collection>().mapTo(mutableSetOf()) { it.key },
        pendingArtists = runningTasks.filterIsInstance<TaskKey.Artist>().mapTo(mutableSetOf()) { it.id },
        collectionErrors = collectionErrors,
        artistErrors = artistErrors,
        collectionMorePending = runningTasks.filterIsInstance<TaskKey.CollectionMore>().mapTo(mutableSetOf()) { it.key },
        artistAlbumHasMore = artistAlbumHasMore,
        artistTrackHasMore = artistTrackHasMore,
        artistMorePending = runningTasks.filterIsInstance<TaskKey.ArtistMore>().mapTo(mutableSetOf()) { it.id },
        libraryMoreErrors = taskFailures.byKey<TaskKey.LibraryMore>().mapKeys { (task, _) -> task.filter },
        collectionMoreErrors = taskFailures.byKey<TaskKey.CollectionMore>().mapKeys { (task, _) -> task.key },
        artistMoreErrors = taskFailures.byKey<TaskKey.ArtistMore>().mapKeys { (task, _) -> task.id }
    )

val SidetrackUiState.search: SearchUiState
    get() = SearchUiState(
        searchResults = searchResults,
        searchPending = runningTasks.any { it is TaskKey.Search },
        searchError = searchError,
        searchHasMore = searchHasMore,
        searchMorePending = runningTasks.filterIsInstance<TaskKey.SearchMore>()
            .mapTo(mutableSetOf()) { it.scope },
        searchScopes = searchScopes,
        searchHistory = searchHistory,
        query = query
    )

val SidetrackUiState.account: AccountUiState
    get() = AccountUiState(
        signedIn = signedIn,
        authPending = TaskKey.SignIn in runningTasks,
        audioQuality = audioQuality,
        normalization = normalization,
        gapless = gapless,
        autoplay = autoplay,
        cacheSizeBytes = cacheSizeBytes
    )

val SidetrackUiState.saved: SavedUiState
    get() = SavedUiState(savedTrackIds, savedCollectionKeys, followedArtistIds)

val SidetrackUiState.chrome: ChromeUiState
    get() = ChromeUiState(notice, noticeKind, nowPlayingNotice, queueReplaced)

val SidetrackUiState.playlistSlice: PlaylistUiState
    get() = PlaylistUiState(
        choices = playlistChoices,
        loading = TaskKey.PlaylistChoices in runningTasks,
        loaded = playlistChoicesLoaded,
        hasMore = playlistChoicesHasMore,
        error = playlistChoicesError
    )

val SidetrackUiState.radioSlice: RadioUiState
    get() = RadioUiState(
        radio = radio,
        radioSeedId = radioSeedId,
        radioPending = runningTasks.any { it is TaskKey.Radio },
        radioError = radioError,
        artistRadio = artistRadio,
        artistRadioArtistId = artistRadioArtistId,
        artistRadioPending = runningTasks.any { it is TaskKey.ArtistRadio },
        artistRadioError = artistRadioError
    )
