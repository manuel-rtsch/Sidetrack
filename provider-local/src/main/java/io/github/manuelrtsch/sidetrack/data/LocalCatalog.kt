package io.github.manuelrtsch.sidetrack.data

import io.github.manuelrtsch.sidetrack.Album
import io.github.manuelrtsch.sidetrack.Artist
import io.github.manuelrtsch.sidetrack.Collection
import io.github.manuelrtsch.sidetrack.CollectionType
import io.github.manuelrtsch.sidetrack.LibraryEntry
import io.github.manuelrtsch.sidetrack.LibrarySection
import io.github.manuelrtsch.sidetrack.MediaItem
import io.github.manuelrtsch.sidetrack.MediaKey
import io.github.manuelrtsch.sidetrack.MediaType
import io.github.manuelrtsch.sidetrack.Page
import io.github.manuelrtsch.sidetrack.Playlist
import io.github.manuelrtsch.sidetrack.PlaylistActions
import io.github.manuelrtsch.sidetrack.SearchResults
import io.github.manuelrtsch.sidetrack.SearchScope
import io.github.manuelrtsch.sidetrack.ServiceCapabilities
import io.github.manuelrtsch.sidetrack.ServiceId
import io.github.manuelrtsch.sidetrack.Track
import io.github.manuelrtsch.sidetrack.playback.PlaybackSource

/**
 * The device's music library behind the neutral [MediaCatalog] contract.
 *
 * Every read is built from one snapshot of the media database — albums and artists are groupings
 * of the same rows, not separate tables — so the sections cannot disagree about what exists while
 * a rescan is running. Playback resolves to a `content://` URI, which Media3 plays exactly like a
 * stream: the media session, notification and audio focus are the shared runtime's.
 *
 * Identity: a track is its MediaStore id, an album its album id, an artist its name, and a
 * playlist is `store:<id>` for a playlist the media database holds or `file:<id>` for an `.m3u8`
 * Sidetrack owns. Only the last kind is writable, which is what [PlaylistActions] reports.
 */
class LocalCatalog(
    private val source: () -> LocalLibrarySource,
    private val files: LocalPlaylistFiles,
    private val favorites: LocalFavorites,
    private val service: ServiceId = ServiceId.Device
) : MediaCatalog,
    MediaLibraryWriter {

    override val capabilities: ServiceCapabilities = ServiceCapabilities.Device

    override suspend fun library(section: LibrarySection, offset: Int, limit: Int): Page<LibraryEntry> = when (section) {
        LibrarySection.Liked -> {
            val all: List<LibraryEntry> = likedTracks()
            Page.of(all.drop(offset).take(limit), offset, limit, all.size)
        }

        LibrarySection.Playlists -> {
            val all = playlists()
            Page.of<LibraryEntry>(all.drop(offset).take(limit), offset, limit, all.size)
        }

        LibrarySection.Albums -> {
            val all = albumsOf(snapshot().tracks)
            Page.of<LibraryEntry>(all.drop(offset).take(limit), offset, limit, all.size)
        }

        LibrarySection.Artists -> {
            val all = artistsOf(snapshot().tracks)
            Page.of<LibraryEntry>(all.drop(offset).take(limit), offset, limit, all.size)
        }

        LibrarySection.Shows -> Page()
    }

    override suspend fun collection(key: MediaKey, offset: Int, limit: Int): Collection? = when (key.type) {
        MediaType.Album -> albumsOf(snapshot().tracks).firstOrNull { it.id == key.id }?.let { album ->
            Collection(
                type = CollectionType.Album,
                id = album.id,
                title = album.title,
                subtitle = album.artist,
                artRes = 0,
                items = album.tracks,
                artistId = album.artistId.takeIf { it.isNotEmpty() },
                artUrl = album.artUrl,
                totalItems = album.trackCount,
                saveable = true,
                service = service
            )
        }

        MediaType.Playlist -> playlists().firstOrNull { it.id == key.id }?.let { playlist ->
            val items = playlist.tracks.drop(offset).take(limit)
            Collection(
                type = CollectionType.Playlist,
                id = playlist.id,
                title = playlist.name,
                subtitle = playlist.owner,
                artRes = 0,
                items = items,
                artUrl = playlist.artUrl,
                totalItems = playlist.trackCount,
                hasMore = offset + items.size < playlist.trackCount,
                saveable = false,
                playlistActions = playlist.actions,
                service = service
            )
        }

        else -> null
    }

    override suspend fun artist(key: MediaKey): Artist? = artistsOf(snapshot().tracks).firstOrNull { it.id == key.id }

    override suspend fun albums(artist: MediaKey, offset: Int, limit: Int): Page<Album> {
        val all = albumsOf(snapshot().tracks.filter { it.artist == artist.id })
        return Page.of(all.drop(offset).take(limit), offset, limit, all.size)
    }

    override suspend fun topTracks(artist: MediaKey, offset: Int, limit: Int): Page<Track> {
        val all = snapshot().tracks.filter { it.artist == artist.id }.sortedBy { it.title.lowercase() }.map { it.toTrack() }
        return Page.of(all.drop(offset).take(limit), offset, limit, all.size)
    }

    override suspend fun search(query: String, scopes: Set<SearchScope>, offset: Int, limit: Int): SearchResults {
        val needle = query.trim()
        if (needle.isEmpty()) return SearchResults()
        val snapshot = snapshot()
        fun String.matches() = contains(needle, ignoreCase = true)

        fun <T> List<T>.page(): List<T> = drop(offset).take(limit)

        return SearchResults(
            tracks = if (SearchScope.Tracks in scopes) {
                snapshot.tracks.filter { it.title.matches() || it.artist.matches() || it.album.matches() }
                    .sortedBy { it.title.lowercase() }
                    .page()
                    .map { it.toTrack() }
            } else {
                emptyList()
            },
            artists = if (SearchScope.Artists in scopes) {
                artistsOf(snapshot.tracks).filter { it.name.matches() }.page()
            } else {
                emptyList()
            },
            albums = if (SearchScope.Albums in scopes) {
                albumsOf(snapshot.tracks).filter { it.title.matches() || it.artist.matches() }.page()
            } else {
                emptyList()
            },
            playlists = if (SearchScope.Playlists in scopes) {
                playlists().filter { it.name.matches() }.page()
            } else {
                emptyList()
            }
        )
    }

    /** The source is the row itself, so ElapsedTime, buffering and the notification all work. */
    override suspend fun resolvePlaybackSource(item: MediaItem): PlaybackSource {
        if (item.service != service) {
            throw ServiceException(ServiceFailure.TrackUnavailable, "This item belongs to another service")
        }
        val track = snapshot().byId[item.id]
            ?: throw ServiceException(ServiceFailure.TrackUnavailable, "${item.title} is no longer on this device")
        return PlaybackSource(id = item.key.encode(), uri = mediaUri(track.id))
    }

    override suspend fun isSaved(item: MediaKey): Boolean? = when (item.type) {
        MediaType.Track -> favorites.contains(item.id)

        MediaType.Album -> snapshot().tracks.filter { it.albumId == item.id }
            .takeIf { it.isNotEmpty() }
            ?.let { tracks -> tracks.all { favorites.contains(it.id) } }

        else -> null
    }

    override suspend fun isFollowed(artist: MediaKey): Boolean? = null

    override suspend fun setSaved(item: MediaKey, saved: Boolean) = when (item.type) {
        MediaType.Track -> favorites.set(item.id, saved)

        // Saving an album is saving its tracks: there is no album-level flag on the device, and
        // the Liked section is a list of tracks, so a partly liked album stays an offer to like.
        MediaType.Album -> favorites.setAll(snapshot().tracks.filter { it.albumId == item.id }.map { it.id }, saved)

        else -> Unit
    }

    override suspend fun setFollowed(artist: MediaKey, followed: Boolean) = Unit

    override suspend fun addToPlaylist(playlist: MediaKey, item: MediaItem): Int? {
        val entries = writableEntries(playlist)
        val track = snapshot().byId[item.id]
            ?: throw ServiceException(ServiceFailure.TrackUnavailable, "${item.title} is no longer on this device")
        val appended = entries + track.path
        files.save(fileId(playlist), appended)
        return entries.size
    }

    override suspend fun removeFromPlaylist(playlist: MediaKey, item: MediaItem, index: Int?) {
        val entries = writableEntries(playlist)
        val snap = snapshot()
        // The same rule as the server providers: the row the caller named wins while it still holds
        // the track, otherwise the last match — the copy an Undo of `addToPlaylist` means.
        val named = index?.takeIf { entries.getOrNull(it)?.let { path -> snap.byPath[path]?.id } == item.id }
        val target = named ?: entries.indexOfLast { snap.byPath[it]?.id == item.id }
        if (target < 0) return
        files.save(fileId(playlist), entries.toMutableList().apply { removeAt(target) })
    }

    override suspend fun renamePlaylist(playlist: MediaKey, name: String): Boolean {
        writableEntries(playlist)
        files.rename(fileId(playlist), name)
        return true
    }

    override suspend fun deletePlaylist(playlist: MediaKey): Boolean {
        writableEntries(playlist)
        files.delete(fileId(playlist))
        return true
    }

    override suspend fun createPlaylist(name: String, seed: List<MediaKey>): Playlist {
        val snap = snapshot()
        val entries = seed.mapNotNull { snap.byId[it.id]?.path }
        val file = files.create(name, entries)
        return file.toPlaylist(snap)
    }

    /** A snapshot of the media database: the rows, and the two lookups a playlist needs. */
    private data class Snapshot(
        val tracks: List<LocalTrack>,
        val byId: Map<String, LocalTrack>,
        val byPath: Map<String, LocalTrack>
    )

    private fun snapshot(): Snapshot {
        val tracks = source().tracks()
        return Snapshot(
            tracks = tracks,
            byId = tracks.associateBy { it.id },
            byPath = tracks.associateBy { it.path }
        )
    }

    private fun likedTracks(): List<Track> = snapshot().let { snap ->
        // Newest first: the store appends, and the section reads like a history.
        favorites.ids().asReversed().mapNotNull { snap.byId[it]?.toTrack() }
    }

    private fun albumsOf(tracks: List<LocalTrack>): List<Album> = tracks
        .groupBy { it.albumId }
        .filterKeys { it.isNotEmpty() }
        .map { (id, rows) ->
            val ordered = rows.sortedWith(compareBy({ it.trackNumber }, { it.title.lowercase() }))
            val first = ordered.first()
            Album(
                id = id,
                title = first.album,
                artist = first.artist,
                artistId = first.artist,
                year = first.year,
                artRes = 0,
                tracks = ordered.map { it.toTrack() },
                artUrl = artworkUri(id),
                trackCount = ordered.size,
                service = service
            )
        }
        .sortedBy { it.title.lowercase() }

    private fun artistsOf(tracks: List<LocalTrack>): List<Artist> = tracks
        .groupBy { it.artist }
        .filterKeys { it.isNotEmpty() }
        .map { (name, rows) ->
            val albumIds = rows.map { it.albumId }.distinct()
            Artist(
                id = name,
                name = name,
                artRes = 0,
                albumIds = albumIds,
                artUrl = albumIds.firstOrNull()?.let(::artworkUri),
                albumCount = albumIds.size,
                service = service
            )
        }
        .sortedBy { it.name.lowercase() }

    private fun playlists(): List<Playlist> {
        val snap = snapshot()
        val system = source().playlists().map { it.toPlaylist(snap) }
        val own = files.list().map { it.toPlaylist(snap) }
        return (system + own).sortedBy { it.name.lowercase() }
    }

    /**
     * The entries of a playlist this adapter may write, or a refusal.
     *
     * A `store:` playlist is the media database's: Android only lets the app that created a
     * playlist row change it, so writing there would fail on some devices and silently succeed on
     * others. The UI never offers it ([PlaylistActions.None]) and a direct call is refused.
     */
    private fun writableEntries(playlist: MediaKey): List<String> {
        if (playlist.type != MediaType.Playlist) {
            throw ServiceException(ServiceFailure.NotAllowed, "This item is not a playlist")
        }
        if (!isFilePlaylist(playlist)) {
            throw ServiceException(ServiceFailure.NotAllowed, "This playlist belongs to the device, not to Sidetrack")
        }
        return files.list().firstOrNull { it.id == fileId(playlist) }?.entries
            ?: throw ServiceException(ServiceFailure.TrackUnavailable, "This playlist file is gone")
    }

    private fun LocalTrack.toTrack(): Track = Track(
        id = id,
        title = title,
        artist = artist,
        artistId = artist,
        albumId = albumId,
        artRes = 0,
        durationMs = durationMs,
        uri = mediaUri(id),
        artUrl = artworkUri(albumId),
        service = service
    )

    private fun LocalSystemPlaylist.toPlaylist(snap: Snapshot): Playlist {
        val tracks = audioIds.mapNotNull { snap.byId[it]?.toTrack() }
        return Playlist(
            id = STORE_PREFIX + id,
            name = name,
            owner = "This device",
            ownerId = null,
            artRes = 0,
            tracks = tracks,
            artUrl = tracks.firstOrNull()?.artUrl,
            trackCount = tracks.size,
            actions = PlaylistActions.None,
            service = service
        )
    }

    private fun LocalPlaylistFile.toPlaylist(snap: Snapshot): Playlist {
        val tracks = entries.mapNotNull { snap.byPath[it]?.toTrack() }
        return Playlist(
            id = FILE_PREFIX + id,
            name = name,
            owner = "Sidetrack",
            ownerId = null,
            artRes = 0,
            tracks = tracks,
            artUrl = tracks.firstOrNull()?.artUrl,
            trackCount = tracks.size,
            // Sidetrack wrote the file, so it may always rewrite, rename and delete it.
            actions = PlaylistActions.All,
            service = service
        )
    }

    private fun isFilePlaylist(key: MediaKey): Boolean = key.id.startsWith(FILE_PREFIX)

    private fun fileId(key: MediaKey): String = key.id.removePrefix(FILE_PREFIX)

    private companion object {
        const val STORE_PREFIX = "store:"
        const val FILE_PREFIX = "file:"

        /** MediaStore's own playback endpoint; a `content://` row Media3 opens directly. */
        fun mediaUri(id: String): String = "content://media/external/audio/media/$id"

        /** The album-art endpoint, which is what every local player on Android uses. */
        fun artworkUri(albumId: String): String? = albumId.takeIf { it.isNotEmpty() }?.let { "content://media/external/audio/albumart/$it" }
    }
}
