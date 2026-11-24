package io.github.manuelrtsch.sidetrack

import hrln.interactive.sidekit.design.action.NoticeKind

data class SidetrackUiState(
    val signedIn: Boolean = false,
    val current: MediaItem? = null,
    val upcoming: List<MediaItem> = emptyList(),
    val history: List<MediaItem> = emptyList(),
    val recent: List<MediaItem> = emptyList(),
    /** The work in flight right now, keyed by what it is for; see [TaskRegistry]. */
    val runningTasks: Set<TaskKey> = emptySet(),
    /**
     * Why the last run of a key failed, for the paging footers only.
     *
     * A failed first page has somewhere to put its error — the cached [LibraryPage], `collectionErrors`
     * or `artistErrors` — because that entry is the thing the screen retries from. A failed
     * "load more" has no such entry: the page keeps its rows and its `hasMore`, so the failure
     * would otherwise be dropped on the floor. It lives here, keyed like the work that produced it,
     * and [TaskRegistry] clears it when the page is asked for again.
     */
    val taskFailures: Map<TaskKey, String> = emptyMap(),
    /** Every category opened this session, so switching back paints without a fetch. */
    val libraryPages: Map<LibraryFilter, LibraryPage> = emptyMap(),
    val librarySections: Map<LibraryFilter, List<Any>> = emptyMap(),
    val librarySectionHasMore: Set<LibraryFilter> = emptySet(),
    /** Cached search pages keyed by query, then by category, so a scope toggle never drops the
     *  pages of the categories it leaves untouched. */
    val searchPages: Map<String, Map<SearchScope, LibraryPage>> = emptyMap(),
    /** Set when a search request failed and there are no results to keep on screen. */
    val searchError: String? = null,
    /** Failed collection loads, keyed by "type:id", so the screen can offer a retry. */
    val collectionErrors: Map<String, String> = emptyMap(),
    /** Failed artist loads, keyed by artist id, so the screen can offer a retry. */
    val artistErrors: Map<String, String> = emptyMap(),
    val artistAlbumHasMore: Map<String, Boolean> = emptyMap(),
    val artistTrackHasMore: Map<String, Boolean> = emptyMap(),
    /** What the transport is doing; [isPlaying] and [isBuffering] are read-only projections. */
    val transport: Transport = Transport.Idle,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.Off,
    val savedTrackIds: Set<String> = emptySet(),
    val savedCollectionKeys: Set<String> = emptySet(),
    val followedArtistIds: Set<String> = emptySet(),
    val collections: Map<String, Collection> = emptyMap(),
    val artists: Map<String, Artist> = emptyMap(),
    val artistAlbums: Map<String, List<Album>> = emptyMap(),
    val artistTracks: Map<String, List<Track>> = emptyMap(),
    /** The artist's whole catalogue, for the "All songs" page; empty until that page is opened. */
    val artistSongs: Map<String, List<Track>> = emptyMap(),
    val artistSongHasMore: Map<String, Boolean> = emptyMap(),
    val filter: LibraryFilter = LibraryFilter.LikedSongs,
    val searchScopes: Set<SearchScope> = SearchScope.entries.toSet(),
    /** Recent search terms, newest first; the landing screen offers them under "Search in". */
    val searchHistory: List<String> = emptyList(),
    val query: String = "",
    val audioQuality: AudioQuality = AudioQuality.High,
    val normalization: Boolean = false,
    val gapless: Boolean = true,
    val autoplay: Boolean = true,
    val cacheSizeBytes: Long = 0L,
    val notice: String? = null,
    /** How long the bar keeps [notice]: a success is short, everything else gets time to be read. */
    val noticeKind: NoticeKind = NoticeKind.Other,
    /** The item that just started, shown briefly in the action bar as a now-playing confirmation. */
    val nowPlayingNotice: MediaItem? = null,
    /**
     * A collection tap just replaced the queue, and the bar is offering it back for a few seconds.
     * The queue itself lives in the playback driver; this is only the offer's deadline.
     */
    val queueReplaced: Boolean = false,
    /** The station built from the current track, shown by the Song radio screen. */
    val radio: Collection? = null,
    /** Which track seeded [radio], so revisiting the same song does not refetch it. */
    val radioSeedId: String? = null,
    val radioError: String? = null,
    /** The station built from an artist, shown by the Artist radio screen. */
    val artistRadio: Collection? = null,
    /** Which artist seeded [artistRadio], so revisiting the same artist does not refetch it. */
    val artistRadioArtistId: String? = null,
    val artistRadioError: String? = null,
    /** The playlists the Add-to-playlist picker may offer: only ones this account may add to. */
    val playlistChoices: List<Playlist> = emptyList(),
    val playlistChoicesLoaded: Boolean = false,
    val playlistChoicesHasMore: Boolean = false,
    val playlistChoicesError: String? = null
) {
    /** Audio exists or is on its way; see [Transport]. Kept while screens move to [transport]. */
    val isPlaying: Boolean get() = transport.isPlaying
    val isBuffering: Boolean get() = transport == Transport.Buffering

    // The open category is a projection of [libraryPages]: the cache is the only copy of the rows,
    // so a page that arrives for a category the user has left is still worth keeping.
    val libraryItems: List<Any> get() = libraryPages[filter]?.items.orEmpty()
    val libraryHasMore: Boolean get() = libraryPages[filter]?.hasMore == true

    /** Set when the open category could not load and has no items to show. */
    val libraryError: String? get() = libraryPages[filter]?.error

    // Search, like the library, keeps its rows in a per-category cache and projects the open view
    // from it. The cache here is keyed by the active query as well, because a search result set has
    // no meaning outside the query that produced it: typing a new query is always a fresh browse.
    val searchPagesForQuery: Map<SearchScope, LibraryPage> get() = searchPages[query].orEmpty()
    val searchResults: SearchResults get() = searchResultsFor(searchPagesForQuery)
    val searchHasMore: Set<SearchScope> get() = searchPagesForQuery.filterValues { it.hasMore }.keys
}

/** Sets the transient bar message together with the kind the bar renders it with. */
internal fun SidetrackUiState.say(text: String, kind: NoticeKind = NoticeKind.Other) = copy(notice = text, noticeKind = kind)
