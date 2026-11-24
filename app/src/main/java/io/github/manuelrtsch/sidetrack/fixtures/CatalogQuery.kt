package io.github.manuelrtsch.sidetrack.fixtures

import io.github.manuelrtsch.sidetrack.Album
import io.github.manuelrtsch.sidetrack.Artist
import io.github.manuelrtsch.sidetrack.LibraryEntry
import io.github.manuelrtsch.sidetrack.LibraryFilter
import io.github.manuelrtsch.sidetrack.Page
import io.github.manuelrtsch.sidetrack.Playlist
import io.github.manuelrtsch.sidetrack.SearchResults
import io.github.manuelrtsch.sidetrack.SearchScope
import io.github.manuelrtsch.sidetrack.Show
import io.github.manuelrtsch.sidetrack.Track

/**
 * The two read paths every fixed catalog needs, shared by the demo packs and the screenshot
 * fixture.
 *
 * Those catalogs differ in where their items come from — the demo build maps a fictional library
 * onto real playable URIs, the screenshot suite uses duration-only tracks — but a library page and
 * a search answer the same way for all of them. Only the item lists are arguments, so neither
 * caller repeats the paging rule or the two-character minimum.
 *
 * Reached from the `demo` build type and from the screenshot suite; a release build references
 * neither, so R8 drops this together with them.
 */
internal fun catalogLibraryPage(
    filter: LibraryFilter,
    offset: Int,
    limit: Int,
    liked: List<Track>,
    playlists: List<Playlist>,
    albums: List<Album>,
    artists: List<Artist>,
    shows: List<Show>
): Page<LibraryEntry> {
    val all: List<LibraryEntry> = when (filter) {
        LibraryFilter.LikedSongs -> liked
        LibraryFilter.Playlists -> playlists
        LibraryFilter.Albums -> albums
        LibraryFilter.Artists -> artists
        LibraryFilter.Shows -> shows
    }
    return Page.of(all.drop(offset).take(limit), offset, limit, all.size)
}

/**
 * A search over fixed items. One character is not a query: the catalogs answer nothing below two,
 * so the screen keeps showing the library instead of matching half of it.
 */
internal fun catalogSearch(
    query: String,
    scopes: Set<SearchScope>,
    offset: Int,
    limit: Int,
    tracks: List<Track>,
    artists: List<Artist>,
    albums: List<Album>,
    playlists: List<Playlist>,
    shows: List<Show>
): SearchResults {
    val q = query.trim()
    if (q.length < 2) return SearchResults()
    fun match(vararg text: String) = text.any { it.contains(q, ignoreCase = true) }
    fun <T> page(items: List<T>) = items.drop(offset).take(limit)
    return SearchResults(
        tracks = if (SearchScope.Tracks in scopes) page(tracks.filter { match(it.title, it.artist) }) else emptyList(),
        artists = if (SearchScope.Artists in scopes) page(artists.filter { match(it.name) }) else emptyList(),
        albums = if (SearchScope.Albums in scopes) page(albums.filter { match(it.title, it.artist) }) else emptyList(),
        playlists = if (SearchScope.Playlists in scopes) page(playlists.filter { match(it.name, it.owner) }) else emptyList(),
        shows = if (SearchScope.Shows in scopes) page(shows.filter { match(it.name, it.publisher) }) else emptyList()
    )
}
