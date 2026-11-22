package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.LocalCatalog
import io.github.manuelrtsch.sidetrack.data.LocalLibrarySource
import io.github.manuelrtsch.sidetrack.data.LocalPlaylistFile
import io.github.manuelrtsch.sidetrack.data.LocalSystemPlaylist
import io.github.manuelrtsch.sidetrack.data.ServiceException
import io.github.manuelrtsch.sidetrack.data.ServiceFailure
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device catalog against a faked media database: grouping, paging, search, artwork/playback
 * URIs, the liked list, and which playlists may be written — the decisions that would otherwise
 * only be observable by plugging in a phone.
 */
class LocalCatalogTest {

    private val files = FakePlaylistFiles(listOf(LocalPlaylistFile("mine", "Road trip", listOf("/music/adele/01-alpha.mp3"))))
    private val favorites = FakeFavorites(listOf("3"))

    private fun catalog(
        source: () -> LocalLibrarySource = { source(playlists = listOf(LocalSystemPlaylist("7", "System mix", listOf("1", "3")))) }
    ) = LocalCatalog(source = source, files = files, favorites = favorites)

    @Test fun `the catalog advertises only what a device library can do`() {
        val caps = catalog().capabilities
        assertEquals(setOf(LibrarySection.Liked, LibrarySection.Playlists, LibrarySection.Albums, LibrarySection.Artists), caps.librarySections)
        assertEquals(setOf(SearchScope.Tracks, SearchScope.Artists, SearchScope.Albums, SearchScope.Playlists), caps.searchScopes)
        assertTrue(caps.supportsPlaylistCreate)
        assertTrue(caps.supportsPlaylistEdit)
        // Nothing to stream from: no radio, no podcasts, no transcoding, no gapless handoff yet.
        assertFalse(caps.supportsRadio)
        assertFalse(caps.supportsPodcasts)
        assertFalse(caps.supportsQualitySelection)
        assertFalse(caps.supportsNormalization)
        assertFalse(caps.supportsGapless)
        assertFalse(caps.supportsFollowArtist)
    }

    @Test fun `liked songs read newest first and skip tracks that are gone`() = runTest {
        favorites.set("4", true)
        favorites.set("9", true)

        val page = catalog().library(LibrarySection.Liked, 0, 20)

        assertEquals(listOf("4", "3"), page.items.map { (it as Track).id })
        assertEquals(2, page.total)
        assertFalse(page.hasMore)
    }

    @Test fun `albums and artists are groupings of the same rows`() = runTest {
        val albums = catalog().library(LibrarySection.Albums, 0, 20)
        assertEquals(listOf("19", "Heroes"), albums.items.map { (it as Album).title })
        assertEquals(2, albums.total)
        // A track number decides the order inside an album, not the title.
        assertEquals(listOf("Alpha", "Beta"), (albums.items.first() as Album).tracks.map { it.title })

        val artists = catalog().library(LibrarySection.Artists, 0, 20)
        assertEquals(listOf("Adele", "Bowie"), artists.items.map { (it as Artist).name })
        assertEquals(1, (artists.items.first() as Artist).albumCount)

        assertEquals(0, catalog().library(LibrarySection.Shows, 0, 20).items.size)
    }

    @Test fun `paging reports the total so the list knows there is more`() = runTest {
        val first = catalog().library(LibrarySection.Artists, 0, 1)
        assertEquals(listOf("Adele"), first.items.map { (it as Artist).name })
        assertTrue(first.hasMore)

        val second = catalog().library(LibrarySection.Artists, 1, 1)
        assertEquals(listOf("Bowie"), second.items.map { (it as Artist).name })
        assertFalse(second.hasMore)
    }

    @Test fun `playlists mix the device's own with the files Sidetrack owns`() = runTest {
        val playlists = catalog().library(LibrarySection.Playlists, 0, 20).items.map { it as Playlist }

        assertEquals(listOf("Road trip", "System mix"), playlists.map { it.name })
        assertEquals("file:mine", playlists.first().id)
        assertEquals(PlaylistActions.All, playlists.first().actions)
        assertEquals("store:7", playlists.last().id)
        assertEquals(PlaylistActions.None, playlists.last().actions)
        // A member that no longer exists is skipped, not shown as a hole.
        assertEquals(2, playlists.last().trackCount)
    }

    @Test fun `an album collection carries its tracks and its artwork`() = runTest {
        val collection = catalog().collection(MediaKey(ServiceId.Device, MediaType.Album, "20"), 0, 20)

        assertNotNull(collection)
        assertEquals("Heroes", collection!!.title)
        assertEquals("Bowie", collection.subtitle)
        assertEquals("Bowie", collection.artistId)
        assertEquals(listOf("Gamma", "Delta"), collection.items.map { it.title })
        assertEquals("content://media/external/audio/albumart/20", collection.artUrl)
        assertNull(catalog().collection(MediaKey(ServiceId.Device, MediaType.Album, "nope"), 0, 20))
    }

    @Test fun `a playlist collection pages and refuses a stale key`() = runTest {
        val page = catalog().collection(MediaKey(ServiceId.Device, MediaType.Playlist, "store:7"), 0, 1)

        assertNotNull(page)
        assertEquals(2, page!!.totalItems)
        assertTrue(page.hasMore)
        assertEquals(PlaylistActions.None, page.playlistActions)
        assertNull(catalog().collection(MediaKey(ServiceId.Device, MediaType.Playlist, "file:gone"), 0, 20))
    }

    @Test fun `an artist resolves to albums and top tracks`() = runTest {
        val artist = catalog().artist(MediaKey(ServiceId.Device, MediaType.Artist, "Adele"))
        assertEquals("Adele", artist?.name)
        assertEquals(listOf("10"), artist?.albumIds)

        val albums = catalog().albums(MediaKey(ServiceId.Device, MediaType.Artist, "Adele"), 0, 20)
        assertEquals(listOf("19"), albums.items.map { it.title })

        val tracks = catalog().topTracks(MediaKey(ServiceId.Device, MediaType.Artist, "Bowie"), 0, 20)
        assertEquals(listOf("Delta", "Gamma"), tracks.items.map { it.title })
        assertNull(catalog().artist(MediaKey(ServiceId.Device, MediaType.Artist, "Nobody")))
    }

    @Test fun `search answers each requested scope and nothing else`() = runTest {
        val results = catalog().search("adele", setOf(SearchScope.Tracks, SearchScope.Artists), 0, 20)

        assertEquals(listOf("Alpha", "Beta"), results.tracks.map { it.title })
        assertEquals(listOf("Adele"), results.artists.map { it.name })
        assertTrue(results.albums.isEmpty())
        assertTrue(results.playlists.isEmpty())

        val titles = catalog().search("heroes", setOf(SearchScope.Albums), 0, 20)
        assertEquals(listOf("Heroes"), titles.albums.map { it.title })
        assertEquals(0, titles.tracks.size)

        assertTrue(catalog().search("   ", SearchScope.entries.toSet(), 0, 20).isEmpty)
    }

    @Test fun `playback resolves the MediaStore row, never a path`() = runTest {
        val source = catalog().resolvePlaybackSource(fixtureTracks.first().toItem())
        assertEquals("content://media/external/audio/media/1", source.uri)
        assertEquals("device:track:1", source.id)

        val wrongService = MediaKey(ServiceId.Spotify, MediaType.Track, "1")
        assertEquals(
            ServiceFailure.TrackUnavailable,
            failure { catalog().resolvePlaybackSource(fixtureTracks.first().toItem(wrongService)) }
        )
        assertEquals(
            ServiceFailure.TrackUnavailable,
            failure { catalog().resolvePlaybackSource(Track(id = "404", title = "Gone", artist = "x", artistId = "x", albumId = "0", artRes = 0, durationMs = 1L, service = ServiceId.Device)) }
        )
    }

    @Test fun `liking a track is local, and liking an album likes its tracks`() = runTest {
        val catalog = catalog()
        assertFalse(catalog.isSaved(MediaKey(ServiceId.Device, MediaType.Track, "1"))!!)

        catalog.setSaved(MediaKey(ServiceId.Device, MediaType.Track, "1"), true)
        assertTrue(favorites.contains("1"))

        catalog.setSaved(MediaKey(ServiceId.Device, MediaType.Album, "20"), true)
        assertTrue(favorites.contains("3") && favorites.contains("4"))
        assertTrue(catalog.isSaved(MediaKey(ServiceId.Device, MediaType.Album, "20"))!!)

        catalog.setSaved(MediaKey(ServiceId.Device, MediaType.Album, "20"), false)
        assertFalse(catalog.isSaved(MediaKey(ServiceId.Device, MediaType.Album, "20"))!!)

        // A service-level follow has no meaning on a device, so the answer stays neutral.
        assertNull(catalog.isSaved(MediaKey(ServiceId.Device, MediaType.Artist, "Adele")))
        assertNull(catalog.isFollowed(MediaKey(ServiceId.Device, MediaType.Artist, "Adele")))
    }

    @Test fun `a track appends to an owned playlist and returns where it landed`() = runTest {
        val catalog = catalog()
        val appended = catalog.addToPlaylist(
            MediaKey(ServiceId.Device, MediaType.Playlist, "file:mine"),
            fixtureTracks[1].toItem()
        )

        assertEquals(1, appended)
        assertEquals(listOf("/music/adele/01-alpha.mp3", "/music/adele/02-beta.mp3"), files.entries("mine"))
    }

    @Test fun `removal follows the row the caller named, otherwise the last copy`() = runTest {
        val catalog = catalog()
        val playlist = MediaKey(ServiceId.Device, MediaType.Playlist, "file:mine")
        files.save("mine", listOf("/music/adele/01-alpha.mp3", "/music/adele/02-beta.mp3", "/music/adele/01-alpha.mp3"))

        catalog.removeFromPlaylist(playlist, fixtureTracks[0].toItem(), index = 2)
        assertEquals(listOf("/music/adele/01-alpha.mp3", "/music/adele/02-beta.mp3"), files.entries("mine"))

        // No usable row: the last match goes, which is the copy an Undo of an append means.
        catalog.removeFromPlaylist(playlist, fixtureTracks[0].toItem(), index = 1)
        assertEquals(listOf("/music/adele/02-beta.mp3"), files.entries("mine"))

        // A track that is not in the playlist is a no-op, not an error.
        catalog.removeFromPlaylist(playlist, fixtureTracks[3].toItem())
        assertEquals(listOf("/music/adele/02-beta.mp3"), files.entries("mine"))
    }

    @Test fun `a playlist this device owns is refused before anything is written`() = runTest {
        val catalog = catalog()
        val devicePlaylist = MediaKey(ServiceId.Device, MediaType.Playlist, "store:7")
        val track = fixtureTracks.first().toItem()

        assertEquals(ServiceFailure.NotAllowed, failure { catalog.addToPlaylist(devicePlaylist, track) })
        assertEquals(ServiceFailure.NotAllowed, failure { catalog.renamePlaylist(devicePlaylist, "x") })
        assertEquals(ServiceFailure.NotAllowed, failure { catalog.deletePlaylist(devicePlaylist) })
        assertTrue(files.has("mine"))
    }

    @Test fun `create, rename and delete work on the files Sidetrack owns`() = runTest {
        val catalog = catalog()
        val created = catalog.createPlaylist("  New mix  ", listOf(fixtureTracks[2].toItem().key, fixtureTracks[3].toItem().key))

        assertNotNull(created)
        assertEquals("New mix", created!!.name)
        assertEquals("file:generated", created.id)
        assertEquals(listOf("/music/bowie/03-gamma.mp3", "/music/bowie/04-delta.mp3"), files.entries("generated"))

        assertTrue(catalog.renamePlaylist(created.key, "Renamed"))
        assertEquals("Renamed", files.name("generated"))

        assertTrue(catalog.deletePlaylist(created.key))
        assertFalse(files.has("generated"))
    }

    @Test fun `a missing playlist file is a track-level failure, not a silent success`() = runTest {
        val catalog = catalog()
        val gone = MediaKey(ServiceId.Device, MediaType.Playlist, "file:gone")

        assertEquals(
            ServiceFailure.TrackUnavailable,
            failure { catalog.addToPlaylist(gone, fixtureTracks.first().toItem()) }
        )
    }

    @Test fun `a deleted track cannot be added to a playlist`() = runTest {
        val catalog = catalog()
        val stale = Track(id = "999", title = "Gone", artist = "Nobody", artistId = "Nobody", albumId = "0", artRes = 0, durationMs = 1L, service = ServiceId.Device)

        assertEquals(
            ServiceFailure.TrackUnavailable,
            failure { catalog.addToPlaylist(MediaKey(ServiceId.Device, MediaType.Playlist, "file:mine"), stale) }
        )
    }

    /** Runs [block] and returns the failure it threw, so a refusal can be asserted inline. */
    private suspend fun failure(block: suspend () -> Unit): ServiceFailure = try {
        block()
        error("expected a ServiceException")
    } catch (e: ServiceException) {
        e.failure
    }

    private fun io.github.manuelrtsch.sidetrack.data.LocalTrack.toItem(key: MediaKey = MediaKey(ServiceId.Device, MediaType.Track, id)) = Track(
        id = key.id,
        title = title,
        artist = artist,
        artistId = artist,
        albumId = albumId,
        artRes = 0,
        durationMs = durationMs,
        service = key.service
    )
}
