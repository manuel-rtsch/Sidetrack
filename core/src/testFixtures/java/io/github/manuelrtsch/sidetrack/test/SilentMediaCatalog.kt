package io.github.manuelrtsch.sidetrack.test

import io.github.manuelrtsch.sidetrack.Album
import io.github.manuelrtsch.sidetrack.Artist
import io.github.manuelrtsch.sidetrack.Collection
import io.github.manuelrtsch.sidetrack.LibraryEntry
import io.github.manuelrtsch.sidetrack.LibrarySection
import io.github.manuelrtsch.sidetrack.MediaItem
import io.github.manuelrtsch.sidetrack.MediaKey
import io.github.manuelrtsch.sidetrack.Page
import io.github.manuelrtsch.sidetrack.SearchResults
import io.github.manuelrtsch.sidetrack.SearchScope
import io.github.manuelrtsch.sidetrack.ServiceCapabilities
import io.github.manuelrtsch.sidetrack.Track
import io.github.manuelrtsch.sidetrack.data.MediaCatalog
import io.github.manuelrtsch.sidetrack.playback.PlaybackSource

/**
 * A catalogue with no content: every page is empty and no call fails.
 *
 * Tests that only need the catalogue seam — a runtime's state, a controller's write path — extend
 * this and override the one call they assert on, instead of answering eight methods with `Page()`.
 */
open class SilentMediaCatalog(
    override val capabilities: ServiceCapabilities = ServiceCapabilities.Subsonic
) : MediaCatalog {
    override suspend fun library(section: LibrarySection, offset: Int, limit: Int): Page<LibraryEntry> = Page()

    override suspend fun collection(key: MediaKey, offset: Int, limit: Int): Collection? = null

    override suspend fun artist(key: MediaKey): Artist? = null

    override suspend fun albums(artist: MediaKey, offset: Int, limit: Int): Page<Album> = Page()

    override suspend fun topTracks(artist: MediaKey, offset: Int, limit: Int): Page<Track> = Page()

    override suspend fun search(query: String, scopes: Set<SearchScope>, offset: Int, limit: Int): SearchResults = SearchResults()

    override suspend fun resolvePlaybackSource(item: MediaItem): PlaybackSource = PlaybackSource(id = item.key.encode(), uri = item.uri.orEmpty())
}
