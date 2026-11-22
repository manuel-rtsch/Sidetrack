package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.LocalFavorites
import io.github.manuelrtsch.sidetrack.data.LocalLibrarySource
import io.github.manuelrtsch.sidetrack.data.LocalPlaylistFile
import io.github.manuelrtsch.sidetrack.data.LocalPlaylistFiles
import io.github.manuelrtsch.sidetrack.data.LocalSystemPlaylist
import io.github.manuelrtsch.sidetrack.data.LocalTrack
import io.github.manuelrtsch.sidetrack.data.ServiceException
import io.github.manuelrtsch.sidetrack.data.ServiceFailure

/**
 * The catalog's inputs, faked. MediaStore cannot be queried from a unit test, so everything the
 * device supplies arrives through one of these: the rows, the playlist files and the liked list.
 */
internal class FakeLibrarySource(
    private val tracks: List<LocalTrack>,
    private val playlists: List<LocalSystemPlaylist> = emptyList()
) : LocalLibrarySource {
    override fun tracks(): List<LocalTrack> = tracks
    override fun playlists(): List<LocalSystemPlaylist> = playlists
}

internal class FakePlaylistFiles(initial: List<LocalPlaylistFile> = emptyList()) : LocalPlaylistFiles {
    private val stored = initial.associateBy { it.id }.toMutableMap()
    var nextId: String = "generated"

    override fun list(): List<LocalPlaylistFile> = stored.values.toList()

    override fun create(name: String, entries: List<String>): LocalPlaylistFile {
        val file = LocalPlaylistFile(nextId, name.trim(), entries)
        stored[file.id] = file
        return file
    }

    override fun save(id: String, entries: List<String>) {
        val current = stored[id] ?: throw ServiceException(ServiceFailure.TrackUnavailable, "missing")
        stored[id] = current.copy(entries = entries)
    }

    override fun rename(id: String, name: String) {
        val current = stored[id] ?: throw ServiceException(ServiceFailure.TrackUnavailable, "missing")
        stored[id] = current.copy(name = name)
    }

    override fun delete(id: String) {
        stored.remove(id) ?: throw ServiceException(ServiceFailure.TrackUnavailable, "missing")
    }

    fun entries(id: String): List<String> = stored.getValue(id).entries
    fun name(id: String): String = stored.getValue(id).name
    fun has(id: String): Boolean = stored.containsKey(id)
}

internal class FakeFavorites(initial: List<String> = emptyList()) : LocalFavorites {
    private val ids = LinkedHashSet(initial)
    override fun ids(): List<String> = ids.toList()
    override fun contains(id: String): Boolean = ids.contains(id)
    override fun set(id: String, saved: Boolean) {
        if (saved) ids.add(id) else ids.remove(id)
    }

    override fun setAll(ids: kotlin.collections.Collection<String>, saved: Boolean) {
        if (saved) this.ids.addAll(ids) else this.ids.removeAll(ids.toSet())
    }
}

internal fun track(
    id: String,
    title: String,
    artist: String,
    album: String = "$artist album",
    albumId: String = album.lowercase().replace(' ', '-'),
    trackNumber: Int = 1,
    durationMs: Long = 120_000L,
    path: String = "/music/$id.mp3"
) = LocalTrack(
    id = id,
    title = title,
    artist = artist,
    album = album,
    albumId = albumId,
    durationMs = durationMs,
    trackNumber = trackNumber,
    year = 2001,
    path = path
)

internal val fixtureTracks = listOf(
    track("1", "Alpha", "Adele", album = "19", albumId = "10", trackNumber = 1, path = "/music/adele/01-alpha.mp3"),
    track("2", "Beta", "Adele", album = "19", albumId = "10", trackNumber = 2, path = "/music/adele/02-beta.mp3"),
    track("3", "Gamma", "Bowie", album = "Heroes", albumId = "20", trackNumber = 1, path = "/music/bowie/03-gamma.mp3"),
    track("4", "Delta", "Bowie", album = "Heroes", albumId = "20", trackNumber = 2, path = "/music/bowie/04-delta.mp3")
)

internal fun source(
    tracks: List<LocalTrack> = fixtureTracks,
    playlists: List<LocalSystemPlaylist> = emptyList()
): LocalLibrarySource = FakeLibrarySource(tracks, playlists)
