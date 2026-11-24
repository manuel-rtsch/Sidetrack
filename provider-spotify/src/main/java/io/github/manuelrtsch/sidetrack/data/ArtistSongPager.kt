package io.github.manuelrtsch.sidetrack.data

import io.github.manuelrtsch.sidetrack.Album
import io.github.manuelrtsch.sidetrack.Artist
import io.github.manuelrtsch.sidetrack.CATALOG_PAGE_SIZE
import io.github.manuelrtsch.sidetrack.Collection
import io.github.manuelrtsch.sidetrack.CollectionType
import io.github.manuelrtsch.sidetrack.Page
import io.github.manuelrtsch.sidetrack.Track
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Pages an artist's catalogue by walking their albums.
 *
 * Spotify has no "all songs by this artist" endpoint. `/artists/{id}/top-tracks` and librespot's
 * `artist.top_tracks` both answer a fixed shortlist with no cursor, so a tenth row is where the
 * list ends no matter what the caller asks for. Albums are the only pageable catalogue there is, so
 * this walks them in the service's own order and flattens them:
 *
 * - albums are fetched in pages and each album's tracks are appended, first artist appearance first;
 * - duplicates are dropped by track id, since an album, its singles and a compilation repeat songs;
 * - the caller's window is sliced out of that stream.
 *
 * The walk always starts at album zero, so `offset` means the same thing on every call and Load more
 * appends instead of repeating a page. [total] is only reported once the albums are exhausted, which
 * is exactly when the stream is complete.
 *
 * Album track lists are remembered, because a page 60 rows deep would otherwise re-download the
 * discography in front of it on every press. The cache is small and ordered: the albums behind the
 * current page stay, the ones far behind are dropped.
 */
class ArtistSongPager(
    private val albums: suspend (artist: Artist, offset: Int, limit: Int) -> Page<Album>,
    private val collection: suspend (type: CollectionType, id: String, offset: Int, limit: Int) -> Collection?,
    private val remember: Int = ALBUM_CACHE_SIZE
) {
    private val lock = Mutex()
    private val albumTracks = LinkedHashMap<String, List<Track>>()

    suspend fun page(artist: Artist, offset: Int, limit: Int): Page<Track> {
        val wanted = offset + limit
        val songs = mutableListOf<Track>()
        val seen = mutableSetOf<String>()
        var albumOffset = 0
        // True only once every album has been walked and every one of its tracks collected: the
        // stream is then the whole catalogue and its length is a total. Stopping at the window in
        // the middle of an album leaves it unknown, and paging goes by fullness instead.
        var complete = false
        while (songs.size < wanted && !complete) {
            val page = albums(artist, albumOffset, CATALOG_PAGE_SIZE)
            if (page.items.isEmpty()) {
                complete = true
                break
            }
            albumOffset += page.items.size
            // A page of albums holds more tracks than a page of rows, so the walk stops as soon as
            // it has the window: the albums behind it are not fetched until a later page needs them.
            var collectedAll = true
            for (album in page.items) {
                if (songs.size >= wanted) {
                    collectedAll = false
                    break
                }
                for (track in tracksOf(album)) if (seen.add(track.id)) songs += track
            }
            // A short album page is the last one only when the transport can say so; Spotify's
            // native album list reports fullness instead of a total, and an empty page ends the walk.
            complete = !page.hasMore && collectedAll
        }
        val window = songs.drop(offset).take(limit)
        return Page.of(window, offset, limit, if (complete) songs.size else null)
    }

    private suspend fun tracksOf(album: Album): List<Track> {
        lock.withLock { albumTracks[album.id] }?.let { return it }
        // Fetched outside the lock: two pages of the same artist are serialized by the caller, and a
        // duplicate fetch of one album is cheaper than holding the lock across a network round trip.
        val tracks = collectTracks(album)
        lock.withLock {
            albumTracks[album.id] = tracks
            while (albumTracks.size > remember) {
                albumTracks.remove(albumTracks.keys.first())
            }
        }
        return tracks
    }

    private suspend fun collectTracks(album: Album): List<Track> {
        val tracks = mutableListOf<Track>()
        var offset = 0
        while (true) {
            val page = collection(CollectionType.Album, album.id, offset, CATALOG_PAGE_SIZE)
            val items = page?.items.orEmpty().filterIsInstance<Track>()
            tracks += items
            if (page?.hasMore != true || items.isEmpty()) return tracks.distinctBy { it.id }
            offset += items.size
        }
    }

    private companion object {
        /**
         * Albums whose track lists stay cached. An album page is [CATALOG_PAGE_SIZE] wide, so the
         * bound has to cover several pages of them: a cache narrower than the walk in front of the
         * current page evicts what the next Load more is about to need and every press refetches
         * the discography.
         */
        const val ALBUM_CACHE_SIZE = CATALOG_PAGE_SIZE * 3
    }
}
