package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.ResilientSpotifyCatalog
import io.github.manuelrtsch.sidetrack.data.ServiceException
import io.github.manuelrtsch.sidetrack.data.ServiceFailure
import io.github.manuelrtsch.sidetrack.data.SpotifyCatalog
import io.github.manuelrtsch.sidetrack.data.SpotifyLibraryWriter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResilientSpotifyCatalogTest {
    private var now = 0L

    private fun catalog(
        native: FakeCatalog,
        web: FakeCatalog = FakeCatalog(),
        onDegrade: (ServiceException) -> Unit = {}
    ) = ResilientSpotifyCatalog(native, web, onDegrade, retryAfterMs = RETRY_MS, clock = { now })

    @Test fun `an unreachable access point moves reads to the web`() = runTest {
        val native = FakeCatalog(failWith = ServiceException(ServiceFailure.Network, "Couldn't reach Spotify"))
        val web = FakeCatalog()
        val catalog = catalog(native, web)

        catalog.library(LibraryFilter.LikedSongs, 0, 20)
        catalog.library(LibraryFilter.Albums, 0, 20)

        // The native transport is tried once, then not again until the retry window opens;
        // "one connect per library row" is exactly the hammering this avoids.
        assertEquals(1, native.libraryCalls)
        assertEquals(2, web.libraryCalls)
        assertTrue(catalog.degraded)
    }

    @Test fun `a degraded catalog takes the native transport back when it answers again`() = runTest {
        val native = FakeCatalog(failWith = ServiceException(ServiceFailure.Network, "no access point"))
        val web = FakeCatalog()
        val catalog = catalog(native, web)

        catalog.library(LibraryFilter.LikedSongs, 0, 20)
        assertTrue(catalog.degraded)

        // Inside the window the Web API keeps serving and the access point is left alone.
        now += RETRY_MS - 1
        native.failWith = null
        catalog.library(LibraryFilter.Albums, 0, 20)
        assertEquals(1, native.libraryCalls)

        // Once it opens, one read probes, and a native answer retires the degradation.
        now += 1
        val page = catalog.library(LibraryFilter.Albums, 0, 20)
        assertEquals(0, page.items.size)
        assertEquals(2, native.libraryCalls)
        assertEquals(2, web.libraryCalls)
        assertFalse(catalog.degraded)

        // Back on the native transport, so nothing else touches the Web API.
        catalog.library(LibraryFilter.LikedSongs, 0, 20)
        assertEquals(3, native.libraryCalls)
        assertEquals(2, web.libraryCalls)
    }

    @Test fun `a failed probe keeps the fallback and does not hammer the access point`() = runTest {
        val native = FakeCatalog(failWith = ServiceException(ServiceFailure.Network, "no access point"))
        val web = FakeCatalog()
        val catalog = catalog(native, web)

        catalog.library(LibraryFilter.LikedSongs, 0, 20)
        now += RETRY_MS
        catalog.library(LibraryFilter.Albums, 0, 20)
        catalog.library(LibraryFilter.Playlists, 0, 20)

        // One probe per window, not one per page.
        assertEquals(2, native.libraryCalls)
        assertEquals(3, web.libraryCalls)
        assertTrue(catalog.degraded)
    }

    @Test fun `a probe the service refuses keeps the reader on the fallback`() = runTest {
        val native = FakeCatalog(failWith = ServiceException(ServiceFailure.Network, "no access point"))
        val web = FakeCatalog()
        val catalog = catalog(native, web)

        catalog.library(LibraryFilter.LikedSongs, 0, 20)
        now += RETRY_MS
        native.failWith = ServiceException(ServiceFailure.Unknown, "Spotify returned HTTP 502")

        // A probe is a look, not a new decision: a refused look leaves the reader where it was.
        catalog.library(LibraryFilter.Albums, 0, 20)

        assertEquals(2, native.libraryCalls)
        assertEquals(2, web.libraryCalls)
        assertTrue(catalog.degraded)
    }

    @Test fun `the notice fires once, not once per read`() = runTest {
        val native = FakeCatalog(failWith = ServiceException(ServiceFailure.Network, "no access point"))
        var notices = 0
        val catalog = catalog(native, onDegrade = { notices++ })

        catalog.library(LibraryFilter.LikedSongs, 0, 20)
        catalog.library(LibraryFilter.Albums, 0, 20)
        assertEquals(1, notices)

        // Coming back and falling back again is a new degradation, so it says so again.
        now += RETRY_MS
        native.failWith = null
        catalog.library(LibraryFilter.Playlists, 0, 20)
        assertFalse(catalog.degraded)
        native.failWith = ServiceException(ServiceFailure.Network, "no access point")
        catalog.library(LibraryFilter.Shows, 0, 20)
        assertEquals(2, notices)
    }

    @Test fun `a failure that is not the transport's is not degraded`() = runTest {
        val native = FakeCatalog(failWith = ServiceException(ServiceFailure.RateLimited, "slow down"))
        val web = FakeCatalog()
        val catalog = catalog(native, web)

        val error = runCatching { catalog.library(LibraryFilter.LikedSongs, 0, 20) }.exceptionOrNull()

        assertEquals(ServiceFailure.RateLimited, (error as ServiceException).failure)
        assertFalse(catalog.degraded)
        assertEquals(0, web.libraryCalls)
    }

    @Test fun `a degraded catalog refuses to change the library`() = runTest {
        // The Web API transport in this app is read-only, so a write cannot silently go through it.
        val native = FakeCatalog(failWith = ServiceException(ServiceFailure.Network, "no access point"))
        val catalog = catalog(native)

        catalog.library(LibraryFilter.LikedSongs, 0, 20)
        val error = runCatching { catalog.setTrackSaved("id", true) }.exceptionOrNull()

        assertEquals(ServiceFailure.Unavailable, (error as ServiceException).failure)
        assertEquals(0, native.writes)
    }

    private class FakeCatalog(
        var failWith: ServiceException? = null
    ) : SpotifyCatalog,
        SpotifyLibraryWriter {
        var libraryCalls = 0
        var writes = 0

        override suspend fun library(filter: LibraryFilter, offset: Int, limit: Int): Page<LibraryEntry> {
            libraryCalls++
            failWith?.let { throw it }
            return Page.of(emptyList(), offset, limit)
        }

        override suspend fun collection(
            type: CollectionType,
            id: String,
            offset: Int,
            limit: Int
        ): Collection? = throw UnsupportedOperationException()

        override suspend fun artist(id: String): Artist? = throw UnsupportedOperationException()

        override suspend fun albumsBy(artist: Artist, offset: Int, limit: Int): Page<Album> = throw UnsupportedOperationException()

        override suspend fun topTracks(artist: Artist, offset: Int, limit: Int): Page<Track> = throw UnsupportedOperationException()

        override suspend fun search(
            query: String,
            scopes: Set<SearchScope>,
            offset: Int,
            limit: Int
        ): SearchResults = throw UnsupportedOperationException()

        override suspend fun isTrackSaved(id: String): Boolean? = null
        override suspend fun isSaved(type: CollectionType, id: String): Boolean? = null
        override suspend fun isArtistFollowed(id: String): Boolean? = null

        override suspend fun setTrackSaved(id: String, saved: Boolean) {
            writes++
        }

        override suspend fun setSaved(type: CollectionType, id: String, saved: Boolean) {
            writes++
        }

        override suspend fun setArtistFollowed(id: String, followed: Boolean) {
            writes++
        }
    }

    private companion object {
        const val RETRY_MS = 60_000L
    }
}
