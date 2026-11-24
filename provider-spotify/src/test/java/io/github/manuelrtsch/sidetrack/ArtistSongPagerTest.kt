package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.ArtistSongPager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The artist catalogue is a walk over albums, so its pages must line up like any other list: the
 * same offset always names the same row, Load more appends, and a song that appears twice appears
 * once.
 */
@RunWith(RobolectricTestRunner::class)
class ArtistSongPagerTest {
    private val artist = Artist(
        id = "a1",
        name = "Artist",
        artRes = 0,
        albumIds = emptyList()
    )

    private val fetchLog = mutableListOf<String>()

    private fun album(id: String, size: Int) = Album(
        id = id,
        title = "Album $id",
        artist = artist.name,
        artistId = artist.id,
        year = 2020,
        artRes = 0,
        tracks = (0 until size).map { track("$id-$it", id) }
    )

    private fun track(id: String, albumId: String) = Track(
        id = id,
        title = id,
        artist = artist.name,
        artistId = artist.id,
        albumId = albumId,
        artRes = 0,
        durationMs = 1_000
    )

    private fun pager(albums: List<Album>): ArtistSongPager = ArtistSongPager(
        albums = { _, offset, limit ->
            fetchLog += "albums:$offset"
            Page.of(albums.drop(offset).take(limit), offset, limit, albums.size)
        },
        collection = { _, id, offset, limit ->
            fetchLog += "album:$id"
            val songs = albums.first { it.id == id }.tracks
            Collection(
                type = CollectionType.Album,
                id = id,
                title = id,
                subtitle = "",
                artRes = 0,
                items = songs.drop(offset).take(limit),
                totalItems = songs.size,
                hasMore = offset + limit < songs.size
            )
        }
    )

    @Test
    fun `a catalogue shorter than a page is the whole list`() = runTest {
        val albums = listOf(album("a", 2), album("b", 3))

        val page = pager(albums).page(artist, 0, CATALOG_PAGE_SIZE)

        assertEquals(listOf("a-0", "a-1", "b-0", "b-1", "b-2"), page.items.map { it.id })
        assertFalse(page.hasMore)
        assertEquals(5, page.total)
    }

    @Test
    fun `a long catalogue pages twenty rows at a time and Load more continues`() = runTest {
        val albums = (0 until 4).map { album("a$it", 8) }
        val pager = pager(albums)
        val all = albums.flatMap { it.tracks }.map { it.id }

        val first = pager.page(artist, 0, CATALOG_PAGE_SIZE)
        assertEquals(all.take(CATALOG_PAGE_SIZE), first.items.map { it.id })
        assertTrue(first.hasMore)
        // The total is unknown until the albums are exhausted, so paging goes by fullness.
        assertNull(first.total)

        val second = pager.page(artist, CATALOG_PAGE_SIZE, CATALOG_PAGE_SIZE)
        assertEquals(all.drop(CATALOG_PAGE_SIZE), second.items.map { it.id })
        assertFalse(second.hasMore)
    }

    @Test
    fun `an offset from a deeper page names the same rows`() = runTest {
        val albums = (0 until 4).map { album("a$it", 8) }
        val pager = pager(albums)
        val all = albums.flatMap { it.tracks }.map { it.id }

        assertEquals(all.drop(9).take(7), pager.page(artist, 9, 7).items.map { it.id })
        assertEquals(all.drop(20).take(5), pager.page(artist, 20, 5).items.map { it.id })
    }

    @Test
    fun `a song on two albums is listed once, where it first appears`() = runTest {
        val single = album("single", 1)
        val album = album("album", 2).copy(tracks = listOf(track("single-0", "album"), track("album-1", "album")))
        val pager = pager(listOf(single, album))

        val page = pager.page(artist, 0, CATALOG_PAGE_SIZE)

        assertEquals(listOf("single-0", "album-1"), page.items.map { it.id })
        assertEquals(2, page.total)
    }

    @Test
    fun `the albums behind a page are not fetched again for the next one`() = runTest {
        val albums = (0 until 30).map { album("a$it", 2) }
        val pager = pager(albums)

        pager.page(artist, 0, CATALOG_PAGE_SIZE)
        val firstPage = fetchLog.toList()
        pager.page(artist, CATALOG_PAGE_SIZE, CATALOG_PAGE_SIZE)

        // The album page is wider than the row page, so the second walk revisits the albums the
        // first one used to line up its offset. Their tracks are cached; only new albums cost a call.
        val secondPage = fetchLog.drop(firstPage.size)
        val refetched = secondPage.filter { it.startsWith("album:") && it in firstPage }
        assertEquals(emptyList<String>(), refetched)
        assertTrue(secondPage.any { it.startsWith("album:") })
    }
}
