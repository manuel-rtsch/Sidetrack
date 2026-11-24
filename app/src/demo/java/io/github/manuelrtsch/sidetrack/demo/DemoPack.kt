package io.github.manuelrtsch.sidetrack.demo

import io.github.manuelrtsch.sidetrack.Album
import io.github.manuelrtsch.sidetrack.Artist
import io.github.manuelrtsch.sidetrack.Collection
import io.github.manuelrtsch.sidetrack.CollectionType
import io.github.manuelrtsch.sidetrack.DemoContent
import io.github.manuelrtsch.sidetrack.Episode
import io.github.manuelrtsch.sidetrack.LibraryEntry
import io.github.manuelrtsch.sidetrack.LibraryFilter
import io.github.manuelrtsch.sidetrack.MediaItem
import io.github.manuelrtsch.sidetrack.Page
import io.github.manuelrtsch.sidetrack.Playlist
import io.github.manuelrtsch.sidetrack.R
import io.github.manuelrtsch.sidetrack.SearchResults
import io.github.manuelrtsch.sidetrack.SearchScope
import io.github.manuelrtsch.sidetrack.Show
import io.github.manuelrtsch.sidetrack.Track
import io.github.manuelrtsch.sidetrack.data.HistoryRepository
import io.github.manuelrtsch.sidetrack.data.QueueRepository
import io.github.manuelrtsch.sidetrack.data.SpotifyCatalog
import io.github.manuelrtsch.sidetrack.data.SpotifyLibraryWriter
import io.github.manuelrtsch.sidetrack.fixtures.catalogLibraryPage
import io.github.manuelrtsch.sidetrack.fixtures.catalogSearch

/**
 * The records a demo pack serves: ten tracks per artist either way, six playlists, six shows, and
 * the two hand-picked lists Home, History and the liked category read.
 *
 * This is the only thing [FictionalPack] and [RealPack] disagree about. Everything that pages,
 * searches or remembers a save is [PackedCatalog] and lives once.
 */
class DemoLibrary(
    val artists: List<Artist>,
    val albums: List<Album>,
    val playlists: List<Playlist>,
    val shows: List<Show>,
    /** Ten saved tracks, spread across the artists, so the liked category has a screen's worth. */
    val liked: List<Track>,
    /** Newest first. Seeded into history so Home and the History screen have rows. */
    val recents: List<Track>
)

/**
 * A demo pack's catalogue: paging, radio, search, the library filters and the in-memory writes.
 *
 * The packs supply their own titles, artwork and playback URIs and nothing else, so a change to how
 * the fixture behaves cannot land in one pack and not the other. Writes reset with the process,
 * which is exactly what a repeatable recording needs: every take starts with the same labels.
 */
abstract class PackedCatalog(private val library: DemoLibrary) :
    DemoContent,
    SpotifyCatalog,
    SpotifyLibraryWriter {

    private val albumTracks: List<Track> = library.albums.flatMap { it.tracks }

    private val savedTracks: MutableSet<String> = library.liked.map { it.id }.toMutableSet()
    private val savedCollections: MutableSet<String> = (
        library.albums.map { "album:${it.id}" } +
            library.playlists.map { "playlist:${it.id}" } +
            library.shows.map { "show:${it.id}" }
        ).toMutableSet()
    private val followedArtists: MutableSet<String> = library.artists.map { it.id }.toMutableSet()

    override fun catalog(): SpotifyCatalog = this

    /**
     * Rewrites history and the queue at every process start: both files are local playback state,
     * not account data, and a recording must begin from the same rows even when the app was used
     * normally before. A warm restart within one take keeps whatever the take did.
     */
    override fun seed(history: HistoryRepository, queue: QueueRepository) {
        history.clear()
        // record() prepends, so insert oldest first to end up newest first.
        val now = System.currentTimeMillis()
        val recents = library.recents
        recents.asReversed().forEachIndexed { index, item ->
            history.record(item, now - (recents.size - index) * 60_000L)
        }
        // A track is current and the next two are tracks too: the transport flow needs Save and
        // Seek, which episodes do not have.
        queue.stop()
        queue.play(recents.take(3), 0)
    }

    override suspend fun isTrackSaved(id: String): Boolean = synchronized(savedTracks) { id in savedTracks }

    override suspend fun isSaved(type: CollectionType, id: String): Boolean = synchronized(savedCollections) { "${type.key}:$id" in savedCollections }

    override suspend fun isArtistFollowed(id: String): Boolean = synchronized(followedArtists) { id in followedArtists }

    override suspend fun setTrackSaved(id: String, saved: Boolean) {
        synchronized(savedTracks) { if (saved) savedTracks.add(id) else savedTracks.remove(id) }
    }

    override suspend fun setSaved(type: CollectionType, id: String, saved: Boolean) {
        val key = "${type.key}:$id"
        synchronized(savedCollections) { if (saved) savedCollections.add(key) else savedCollections.remove(key) }
    }

    override suspend fun setArtistFollowed(id: String, followed: Boolean) {
        synchronized(followedArtists) { if (followed) followedArtists.add(id) else followedArtists.remove(id) }
    }

    override suspend fun artist(id: String): Artist? = library.artists.firstOrNull { it.id == id }

    override suspend fun collection(
        type: CollectionType,
        id: String,
        offset: Int,
        limit: Int
    ): Collection? = when (type) {
        CollectionType.Album -> library.albums.firstOrNull { it.id == id }?.let {
            page(type, it.id, it.title, "${it.artist} · ${it.year} · ${it.tracks.size} tracks", it.artRes, it.tracks, it.artistId, offset, limit)
        }

        CollectionType.Playlist -> library.playlists.firstOrNull { it.id == id }?.let {
            page(type, it.id, it.name, "${it.owner} · ${it.tracks.size} tracks", it.artRes, it.tracks, it.ownerId, offset, limit)
        }

        CollectionType.Show -> library.shows.firstOrNull { it.id == id }?.let {
            page(type, it.id, it.name, "${it.publisher} · ${it.episodes.size} episodes", it.artRes, it.episodes, null, offset, limit)
        }
    }

    private fun page(
        type: CollectionType,
        id: String,
        title: String,
        subtitle: String,
        artRes: Int,
        all: List<MediaItem>,
        artistId: String?,
        offset: Int,
        limit: Int
    ): Collection {
        val items = all.drop(offset).take(limit)
        return Collection(
            type, id, title, subtitle, artRes, items, artistId,
            totalItems = all.size, hasMore = offset + items.size < all.size
        )
    }

    override suspend fun albumsBy(artist: Artist, offset: Int, limit: Int): Page<Album> {
        val all = artist.albumIds.mapNotNull { albumId -> library.albums.firstOrNull { it.id == albumId } }
        return Page.of(all.drop(offset).take(limit), offset, limit, all.size)
    }

    override suspend fun topTracks(artist: Artist, offset: Int, limit: Int): Page<Track> {
        val all = library.albums.filter { it.id in artist.albumIds }.flatMap { it.tracks }
        return Page.of(all.drop(offset).take(limit), offset, limit, all.size)
    }

    override suspend fun songRadio(trackId: String, limit: Int): List<Track> {
        val seed = albumTracks.firstOrNull { it.id == trackId }
        val sameArtist = albumTracks.filter { seed != null && it.artistId == seed.artistId }
        return (sameArtist + albumTracks).distinctBy { it.id }.filter { it.id != trackId }.take(limit)
    }

    override suspend fun artistRadio(artistId: String, limit: Int): List<Track> {
        val artist = library.artists.firstOrNull { it.id == artistId } ?: return emptyList()
        val own = library.albums.filter { it.id in artist.albumIds }.flatMap { it.tracks }
        val rest = library.albums.filterNot { it.id in artist.albumIds }.flatMap { it.tracks }
        return (own + rest).distinctBy { it.id }.take(limit)
    }

    override suspend fun library(filter: LibraryFilter, offset: Int, limit: Int): Page<LibraryEntry> =
        catalogLibraryPage(
            filter = filter,
            offset = offset,
            limit = limit,
            liked = library.liked,
            playlists = library.playlists,
            albums = library.albums,
            artists = library.artists,
            shows = library.shows
        )

    override suspend fun search(
        query: String,
        scopes: Set<SearchScope>,
        offset: Int,
        limit: Int
    ): SearchResults {
        return catalogSearch(
            query = query,
            scopes = scopes,
            offset = offset,
            limit = limit,
            tracks = albumTracks,
            artists = library.artists,
            albums = library.albums,
            playlists = library.playlists,
            shows = library.shows
        )
    }
}

/**
 * The five invented shows both packs carry: same titles, hosts, dates and lengths either way, so
 * only each pack's own first show and the URI its episodes play differ. [uri] is that playback
 * source, the one thing a pack changes about them.
 */
internal fun sharedShows(uri: String): List<Show> {
    fun e(
        id: String,
        title: String,
        show: String,
        showId: String,
        published: String,
        art: Int,
        durationMs: Long
    ) = Episode(id, title, show, showId, published, art, durationMs, uri = uri)
    return listOf(
        Show(
            "small_hours",
            "Small Hours",
            "Northline Audio",
            R.drawable.cover_small_hours,
            listOf(
                e("sh1", "3:14 AM", "Small Hours", "small_hours", "4 Feb 2025", R.drawable.cover_small_hours, 1_842_000L),
                e("sh2", "The Night Shift", "Small Hours", "small_hours", "11 Feb 2025", R.drawable.cover_small_hours, 1_976_000L),
                e("sh3", "A Film We Nearly Made", "Small Hours", "small_hours", "18 Feb 2025", R.drawable.cover_small_hours, 2_035_000L),
                e("sh4", "Room Tone", "Small Hours", "small_hours", "25 Feb 2025", R.drawable.cover_small_hours, 1_788_000L)
            )
        ),
        Show(
            "between_takes",
            "Between Takes",
            "Northline Audio",
            R.drawable.cover_between_takes,
            listOf(
                e("bt1", "The First Read", "Between Takes", "between_takes", "6 May 2025", R.drawable.cover_between_takes, 2_208_000L),
                e("bt2", "Notes in the Margin", "Between Takes", "between_takes", "13 May 2025", R.drawable.cover_between_takes, 2_431_000L),
                e("bt3", "The Cut We Kept", "Between Takes", "between_takes", "20 May 2025", R.drawable.cover_between_takes, 2_115_000L)
            )
        ),
        Show(
            "night_bus",
            "The Night Bus",
            "Northline Audio",
            R.drawable.cover_the_night_bus,
            listOf(
                e("nb1", "Two Streets Over", "The Night Bus", "night_bus", "3 Jun 2025", R.drawable.cover_the_night_bus, 1_964_000L),
                e("nb2", "Last Stop", "The Night Bus", "night_bus", "10 Jun 2025", R.drawable.cover_the_night_bus, 2_142_000L),
                e("nb3", "Home Before Light", "The Night Bus", "night_bus", "17 Jun 2025", R.drawable.cover_the_night_bus, 1_878_000L)
            )
        ),
        Show(
            "signal_lost",
            "Signal Lost",
            "Station Nine",
            R.drawable.cover_signal_lost,
            listOf(
                e("sl1", "Carrier Wave", "Signal Lost", "signal_lost", "1 Apr 2025", R.drawable.cover_signal_lost, 2_311_000L),
                e("sl2", "Static Between Stations", "Signal Lost", "signal_lost", "8 Apr 2025", R.drawable.cover_signal_lost, 2_560_000L),
                e("sl3", "The Long Reply", "Signal Lost", "signal_lost", "15 Apr 2025", R.drawable.cover_signal_lost, 2_077_000L)
            )
        ),
        Show(
            "open_studio",
            "Open Studio",
            "Station Nine",
            R.drawable.cover_open_studio,
            listOf(
                e("os1", "First Draft", "Open Studio", "open_studio", "2 Jul 2025", R.drawable.cover_open_studio, 2_485_000L),
                e("os2", "Margins", "Open Studio", "open_studio", "9 Jul 2025", R.drawable.cover_open_studio, 2_196_000L),
                e("os3", "The Edit", "Open Studio", "open_studio", "16 Jul 2025", R.drawable.cover_open_studio, 2_374_000L)
            )
        )
    )
}

/** The `Saved` key a collection type writes under, shown nowhere and stable across processes. */
private val CollectionType.key: String
    get() = when (this) {
        CollectionType.Album -> "album"
        CollectionType.Playlist -> "playlist"
        CollectionType.Show -> "show"
    }
