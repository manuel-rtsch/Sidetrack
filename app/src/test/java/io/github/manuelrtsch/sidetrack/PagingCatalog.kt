package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.ServiceException
import io.github.manuelrtsch.sidetrack.data.ServiceFailure
import io.github.manuelrtsch.sidetrack.data.SpotifyCatalog

/**
 * A catalog with more rows than one page in every list the app pages, and the offsets it was asked
 * for, so a test can tell a cache hit from a refetch.
 *
 * [failPages] makes every page after the first fail the way a dropped connection does, which is how
 * a paging footer's failure is produced. [endCollections] makes a collection's second page simply
 * not exist, the one case where a list really does end.
 */
internal class PagingCatalog : SpotifyCatalog by MockSpotifyDataSource {
    val libraryOffsets = mutableListOf<Int>()
    val collectionOffsets = mutableListOf<Int>()
    val albumOffsets = mutableListOf<Int>()
    val topTrackOffsets = mutableListOf<Int>()
    val searchOffsets = mutableListOf<Int>()

    var failPages = false
    var endCollections = false

    private val rows = (0 until CATALOG_PAGE_SIZE * 2 + 5).map { pagingTrack("row$it") }
    private val albums = rows.map { pagingAlbum(it.albumId) }.distinctBy { it.id }
    private val solo = Artist(ARTIST_ID, "Ada", 0, albums.map { it.id })

    private fun page(offset: Int, limit: Int): List<Track> {
        if (offset > 0 && failPages) throw ServiceException(ServiceFailure.Network, "offline")
        return rows.drop(offset).take(limit)
    }

    override suspend fun library(filter: LibraryFilter, offset: Int, limit: Int): Page<LibraryEntry> {
        libraryOffsets += offset
        return Page.of(page(offset, limit), offset, limit, rows.size)
    }

    override suspend fun collection(type: CollectionType, id: String, offset: Int, limit: Int): Collection? {
        collectionOffsets += offset
        if (endCollections && offset > 0) return null
        val items = page(offset, limit)
        return Collection(
            type = type,
            id = id,
            title = "Long",
            subtitle = "You · ${rows.size} tracks",
            artRes = 0,
            items = items,
            totalItems = rows.size,
            hasMore = offset + items.size < rows.size
        )
    }

    override suspend fun artist(id: String): Artist? = solo.takeIf { it.id == id }

    override suspend fun albumsBy(artist: Artist, offset: Int, limit: Int): Page<Album> {
        albumOffsets += offset
        return Page.of(albums.drop(offset).take(limit), offset, limit, albums.size)
    }

    override suspend fun topTracks(artist: Artist, offset: Int, limit: Int): Page<Track> {
        topTrackOffsets += offset
        return Page.of(page(offset, limit), offset, limit, rows.size)
    }

    override suspend fun search(query: String, scopes: Set<SearchScope>, offset: Int, limit: Int): SearchResults {
        searchOffsets += offset
        return SearchResults(
            tracks = if (SearchScope.Tracks in scopes) page(offset, limit) else emptyList()
        )
    }

    companion object {
        const val ARTIST_ID = "ada"
        const val QUERY = "row"
    }
}

private fun pagingTrack(id: String) = Track(id, "Row $id", "Ada", PagingCatalog.ARTIST_ID, "al$id", 0, 180_000L)

private fun pagingAlbum(id: String) = Album(id, "Album $id", "Ada", PagingCatalog.ARTIST_ID, 2024, 0, emptyList())
