package io.github.manuelrtsch.sidetrack

/**
 * Stable identity of a music service. The value is persisted, so it must not change once shipped.
 */
@JvmInline
value class ServiceId(val value: String) {
    companion object {
        val Spotify = ServiceId("spotify")
        val Jellyfin = ServiceId("jellyfin")
        val Plex = ServiceId("plex")

        /**
         * The Subsonic family: one wire protocol, several server products. Each listed product
         * keeps its own id so its credentials, library identity and local queue/history stores
         * stay apart. [Subsonic] is the generic entry for any other Subsonic-compatible server.
         */
        val Navidrome = ServiceId("navidrome")
        val NextcloudMusic = ServiceId("nextcloud-music")
        val Subsonic = ServiceId("subsonic")

        /**
         * The music library already on the device, read through MediaStore: a real service like
         * any other, so it keeps its own queue, history and resume files. Deliberately not [Local],
         * which marks the context-free fixtures the driver treats as a simulation with no URI.
         */
        val Device = ServiceId("device")

        /** A context-free fixture, used by previews and unit tests that have no signed-in service. */
        val Local = ServiceId("local")
    }
}

/** The kinds of object a [MediaKey] can point at. */
enum class MediaType { Track, Episode, Album, Playlist, Artist, Show }

/**
 * Stable identity across the queue, history, resume points, routes and persistence: a service id
 * alone is not enough, as two services can share an opaque id and a track and an episode can too.
 * [encode] is the on-disk and playback-source form; [decode] returns null if this build cannot
 * understand it.
 */
data class MediaKey(
    val service: ServiceId,
    val type: MediaType,
    val id: String
) {
    fun encode(): String = "${service.value}:${type.name.lowercase()}:$id"

    companion object {
        fun decode(value: String): MediaKey? {
            val first = value.indexOf(':')
            if (first <= 0) return null
            val second = value.indexOf(':', first + 1)
            if (second <= first + 1) return null
            val service = ServiceId(value.substring(0, first))
            val type = MediaType.entries.firstOrNull { it.name.equals(value.substring(first + 1, second), ignoreCase = true) }
                ?: return null
            val id = value.substring(second + 1)
            if (id.isEmpty()) return null
            return MediaKey(service, type, id)
        }
    }
}

/** Anything a library page can contain. Playable items are [MediaItem]s; the rest are containers. */
sealed interface LibraryEntry {
    val key: MediaKey
}

/** Library sections a service may expose. The UI renders only the supported ones. */
enum class LibrarySection { Liked, Playlists, Albums, Artists, Shows }

/**
 * Per-playlist permissions: the service-level question is [ServiceCapabilities.supportsPlaylistEdit];
 * add/remove and rename/delete stay separate, as a service can allow one and refuse the other;
 * [unconfirmed] (Jellyfin) cannot state permissions, so the UI offers and reports the refusal.
 */
data class PlaylistActions(
    val canAddItems: Boolean = false,
    val canRemoveItems: Boolean = false,
    val canRename: Boolean = false,
    val canDelete: Boolean = false,
    val unconfirmed: Boolean = false
) {
    val canEditItems: Boolean get() = canAddItems || canRemoveItems

    companion object {
        /** The service says this playlist is read-only for the signed-in account. */
        val None = PlaylistActions()

        /** The service says the signed-in account owns this playlist and may change it. */
        val All = PlaylistActions(
            canAddItems = true,
            canRemoveItems = true,
            canRename = true,
            canDelete = true
        )

        /** The service does not report permissions; offer the actions and let the server decide. */
        val ServerDecides = PlaylistActions(
            canAddItems = true,
            canRemoveItems = true,
            canRename = true,
            canDelete = true,
            unconfirmed = true
        )
    }
}

/**
 * The service's own capability flags gate the per-playlist answer: the UI has one place to ask,
 * and a service that cannot do something never offers it.
 */
fun ServiceCapabilities.playlistActions(playlist: Playlist): PlaylistActions = playlist.actions.gatedBy(this)

fun ServiceCapabilities.playlistActions(collection: Collection): PlaylistActions = (collection.playlistActions ?: PlaylistActions.None).gatedBy(this)

private fun PlaylistActions.gatedBy(capabilities: ServiceCapabilities) = PlaylistActions(
    canAddItems = capabilities.supportsPlaylistEdit && canAddItems,
    canRemoveItems = capabilities.supportsPlaylistEdit && canRemoveItems,
    canRename = capabilities.supportsPlaylistEdit && capabilities.supportsPlaylistRename && canRename,
    canDelete = capabilities.supportsPlaylistEdit && capabilities.supportsPlaylistDelete && canDelete,
    unconfirmed = unconfirmed
)

/**
 * What a service can do. The UI asks these questions instead of asking which service is active.
 */
data class ServiceCapabilities(
    val librarySections: Set<LibrarySection>,
    val searchScopes: Set<SearchScope>,
    val supportsPodcasts: Boolean = false,
    val supportsRadio: Boolean = false,
    val supportsFollowArtist: Boolean = false,
    val supportsPlaylistEdit: Boolean = false,
    val supportsPlaylistCreate: Boolean = false,
    val supportsPlaylistRename: Boolean = false,
    val supportsPlaylistDelete: Boolean = false,
    val supportsNormalization: Boolean = false,
    val supportsGapless: Boolean = false,
    val supportsQualitySelection: Boolean = false,
    val supportsProgressReporting: Boolean = false,
    val supportsScrobbling: Boolean = false
) {
    companion object {
        /** A service that supports every capability this build knows about. */
        val All = ServiceCapabilities(
            librarySections = LibrarySection.entries.toSet(),
            searchScopes = SearchScope.entries.toSet(),
            supportsPodcasts = true,
            supportsRadio = true,
            supportsFollowArtist = true,
            supportsPlaylistEdit = true,
            supportsPlaylistRename = true,
            supportsPlaylistDelete = true,
            supportsNormalization = true,
            supportsGapless = true,
            supportsQualitySelection = true,
            supportsProgressReporting = true,
            supportsScrobbling = true
        )

        /**
         * The Subsonic family — Navidrome, Nextcloud Music, Subsonic — has albums, playlists, artists
         * and similar-song radio. Podcast endpoints are 501 stubs on Navidrome and absent elsewhere;
         * loudness maps onto ReplayGain; gapless is engine-level, so no member turns it on.
         */
        val Subsonic = ServiceCapabilities(
            librarySections = setOf(
                LibrarySection.Liked,
                LibrarySection.Playlists,
                LibrarySection.Albums,
                LibrarySection.Artists
            ),
            searchScopes = setOf(
                SearchScope.Tracks,
                SearchScope.Artists,
                SearchScope.Albums,
                SearchScope.Playlists
            ),
            supportsRadio = true,
            supportsPlaylistEdit = true,
            supportsPlaylistCreate = true,
            supportsPlaylistRename = true,
            supportsPlaylistDelete = true,
            supportsNormalization = true,
            supportsQualitySelection = true,
            supportsProgressReporting = true,
            supportsScrobbling = true
        )

        /**
         * Jellyfin has albums, playlists, artists and instant mixes. Music libraries carry no podcast
         * type and the API has no loudness parameter; gapless is engine-level, as in the Subsonic family.
         * Follow maps onto the favorite-artist flag, the server's only artist-level "keep this" state (D2).
         */
        val Jellyfin = ServiceCapabilities(
            librarySections = setOf(
                LibrarySection.Liked,
                LibrarySection.Playlists,
                LibrarySection.Albums,
                LibrarySection.Artists
            ),
            searchScopes = setOf(
                SearchScope.Tracks,
                SearchScope.Artists,
                SearchScope.Albums,
                SearchScope.Playlists
            ),
            supportsRadio = true,
            supportsFollowArtist = true,
            supportsPlaylistEdit = true,
            supportsPlaylistCreate = true,
            supportsPlaylistRename = true,
            supportsPlaylistDelete = true,
            supportsQualitySelection = true,
            supportsProgressReporting = true,
            supportsScrobbling = true
        )

        /**
         * The device's own music library; there is no server behind it, so radio has no seed graph,
         * podcasts and artist follow are undefined, and quality, progress or scrobbles go nowhere —
         * resume and history still work, being the app's own stores, and normalisation and gapless
         * stay off as Media3 applies neither without engine support.
         */
        val Device = ServiceCapabilities(
            librarySections = setOf(
                LibrarySection.Liked,
                LibrarySection.Playlists,
                LibrarySection.Albums,
                LibrarySection.Artists
            ),
            searchScopes = setOf(
                SearchScope.Tracks,
                SearchScope.Artists,
                SearchScope.Albums,
                SearchScope.Playlists
            ),
            supportsPlaylistEdit = true,
            supportsPlaylistCreate = true,
            supportsPlaylistRename = true,
            supportsPlaylistDelete = true
        )

        /**
         * Plex exposes music libraries, playlists, artists and albums. No real radio endpoint, no
         * loudness parameter, no gapless handoff; playlist creation needs a non-empty item URI, so the
         * create form is left out. Liked/saved state is the rating convention (`userRating == 10`);
         * progress and scrobbles go through `/:/timeline` and `/:/scrobble`.
         */
        val Plex = ServiceCapabilities(
            librarySections = setOf(
                LibrarySection.Liked,
                LibrarySection.Playlists,
                LibrarySection.Albums,
                LibrarySection.Artists
            ),
            searchScopes = setOf(
                SearchScope.Tracks,
                SearchScope.Artists,
                SearchScope.Albums,
                SearchScope.Playlists
            ),
            supportsPlaylistEdit = true,
            supportsPlaylistRename = true,
            supportsPlaylistDelete = true,
            supportsProgressReporting = true,
            supportsScrobbling = true
        )
    }
}

/**
 * The row keeps the other buttons in place but mutes them: a category the service cannot fill
 * has nothing to show and no setting would change that, so it must not look selectable.
 */
val ServiceCapabilities.availableLibraryFilters: Set<LibraryFilter>
    get() = LibraryFilter.entries.filterTo(LinkedHashSet()) { it.section() in librarySections }

/** The search categories a service can answer. The scope list mutes the rest the same way. */
val ServiceCapabilities.availableSearchScopes: Set<SearchScope>
    get() = SearchScope.entries.filterTo(LinkedHashSet()) { it in searchScopes }
