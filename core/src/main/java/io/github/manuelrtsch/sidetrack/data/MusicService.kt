package io.github.manuelrtsch.sidetrack.data

import io.github.manuelrtsch.sidetrack.Album
import io.github.manuelrtsch.sidetrack.Artist
import io.github.manuelrtsch.sidetrack.Collection
import io.github.manuelrtsch.sidetrack.CollectionType
import io.github.manuelrtsch.sidetrack.LibraryEntry
import io.github.manuelrtsch.sidetrack.LibraryFilter
import io.github.manuelrtsch.sidetrack.LibrarySection
import io.github.manuelrtsch.sidetrack.MediaItem
import io.github.manuelrtsch.sidetrack.MediaKey
import io.github.manuelrtsch.sidetrack.MediaType
import io.github.manuelrtsch.sidetrack.Page
import io.github.manuelrtsch.sidetrack.Playlist
import io.github.manuelrtsch.sidetrack.SearchResults
import io.github.manuelrtsch.sidetrack.SearchScope
import io.github.manuelrtsch.sidetrack.ServiceCapabilities
import io.github.manuelrtsch.sidetrack.ServiceId
import io.github.manuelrtsch.sidetrack.Show
import io.github.manuelrtsch.sidetrack.Station
import io.github.manuelrtsch.sidetrack.Track
import io.github.manuelrtsch.sidetrack.playback.PlaybackSource

/**
 * The account a provider is currently signed in with. [credentials] is deliberately opaque: each
 * provider decides what it needs to reconnect, and nothing outside the provider reads it.
 */
data class ServiceAccount(
    val service: ServiceId,
    val userId: String,
    val displayName: String,
    val serverUrl: String? = null,
    val credentials: Map<String, String> = emptyMap()
)

/** A provider-neutral failure category, so the UI can explain an error without naming a service. */
enum class ServiceFailure {
    Unavailable,
    NotConnected,
    AuthenticationExpired,
    Network,
    RateLimited,
    TrackUnavailable,

    /** The service understood the request and refused it: the account may not change this item. */
    NotAllowed,
    Unknown
}

/**
 * What the sign-in screen must offer for this service. A provider describes its own sign-in instead
 * of the UI comparing service ids, so a new service can reuse an existing form.
 */
sealed interface SignInKind {
    /** OAuth/custom tab, no fields. */
    data object Browser : SignInKind

    /** Server address, username and password. [defaults] pre-fills a public demo, if any. */
    data class Password(val defaults: PasswordDefaults? = null) : SignInKind

    /**
     * A storage access grant instead of an account. The screen asks the platform for the media
     * permission and then signs in; there is no field to type into, and no secret to store.
     */
    data object Device : SignInKind
}

/** The values a public demo prefills on a password sign-in form. */
data class PasswordDefaults(val serverUrl: String, val username: String, val password: String)

/** A catalog or playback failure carrying a [ServiceFailure] the UI can map to copy. */
class ServiceException(
    val failure: ServiceFailure,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/** What a sign-in attempt needs. A browser flow or a self-hosted server's password. */
sealed interface AuthInput {
    /** A provider that signs in through a browser or custom tab. */
    data class Browser(val openBrowser: suspend (String) -> Unit) : AuthInput

    data class Password(
        val serverUrl: String,
        val username: String,
        val password: String
    ) : AuthInput

    /**
     * A device library sign-in. The provider itself asks the platform whether the media permission
     * is held, so the screen can request it first and then hand over without passing a secret.
     */
    data object Device : AuthInput
}

/** Provider-owned authentication. The registry never sees tokens or passwords. */
interface ServiceAuth {
    suspend fun signIn(input: AuthInput): ServiceAccount
    suspend fun restore(): ServiceAccount?
    suspend fun signOut()
}

/**
 * A [ServiceAuth] whose local credential state a runtime may inspect and drop.
 *
 * The runtime asks once at construction whether a session exists and forgets it on sign-out, so a
 * device whose keystore refused the session can stay signed in for this process.
 */
interface RuntimeAuth : ServiceAuth {
    fun hasCredentials(): Boolean
    fun clear()
}

/**
 * Read side of a music service in the neutral domain model: each provider maps its own catalog onto
 * it, and the UI never sees a provider-specific type. Service ids live inside [MediaKey], and nothing
 * here assumes a particular service's URI or collection vocabulary.
 */
interface MediaCatalog {
    val capabilities: ServiceCapabilities

    suspend fun library(section: LibrarySection, offset: Int, limit: Int): Page<LibraryEntry>
    suspend fun collection(key: MediaKey, offset: Int, limit: Int): Collection?
    suspend fun artist(key: MediaKey): Artist?
    suspend fun albums(artist: MediaKey, offset: Int, limit: Int): Page<Album>
    suspend fun topTracks(artist: MediaKey, offset: Int, limit: Int): Page<Track>

    /**
     * One page of an artist's whole catalogue, ordered by album. The default is empty because most
     * services have no separate listing: [topTracks] already returns every song they hold, and a
     * second page repeating it would be one keypress to see the same rows. Only a service whose top
     * list is a shortlist (see [ServiceCapabilities.supportsArtistSongs]) answers here.
     */
    suspend fun artistSongs(artist: MediaKey, offset: Int, limit: Int): Page<Track> = Page()

    suspend fun search(query: String, scopes: Set<SearchScope>, offset: Int, limit: Int): SearchResults

    /** Resolves the URL and request headers the playback engine needs for [item]. */
    suspend fun resolvePlaybackSource(item: MediaItem): PlaybackSource

    /** Services without radio return an empty page; the UI hides the action by capability. */
    suspend fun radio(seed: MediaKey, offset: Int, limit: Int): Page<Track> = Page()

    /** A station plus the playlist that backs it, when the service can report one. */
    suspend fun radioStation(seed: MediaKey, limit: Int): Station = Station(radio(seed, 0, limit).items)
}

/** Write side of a music service. Optional operations are capability-gated by the caller. */
interface MediaLibraryWriter {
    suspend fun isSaved(item: MediaKey): Boolean?
    suspend fun isFollowed(artist: MediaKey): Boolean?
    suspend fun setSaved(item: MediaKey, saved: Boolean)
    suspend fun setFollowed(artist: MediaKey, followed: Boolean)

    /** Appends [item] to [playlist] and returns the index it landed at, for Undo. */
    suspend fun addToPlaylist(playlist: MediaKey, item: MediaItem): Int? = null

    /**
     * Removes one occurrence of [item] from [playlist]. [index] is the row the caller acted on; since
     * every backend removes by position and a playlist can hold a track twice, a call without an
     * index removes the last match, which is the copy an Undo of [addToPlaylist] means.
     */
    suspend fun removeFromPlaylist(playlist: MediaKey, item: MediaItem, index: Int? = null) = Unit

    /**
     * Renames [playlist] to [name]. False when the service has no rename transport; a refusal is
     * thrown so the caller can report the service's reason.
     */
    suspend fun renamePlaylist(playlist: MediaKey, name: String): Boolean = false

    /**
     * Deletes [playlist] on the service. False when the service has no delete transport; a refusal
     * is thrown so the caller can report the service's reason.
     */
    suspend fun deletePlaylist(playlist: MediaKey): Boolean = false

    /**
     * Creates a playlist named [name], optionally seeded with [seed] in that order. Null when the
     * service cannot create playlists, so the app never claims one it does not own.
     */
    suspend fun createPlaylist(name: String, seed: List<MediaKey> = emptyList()): Playlist? = null
}

/**
 * The id-level playlist calls a server client makes. Each family spells its ids differently — item
 * id, song id, rating key — but the four writes have the same shape, which is why the catalogs share
 * [ClientPlaylistWriter] over this contract.
 */
interface PlaylistTransport {
    /** Appends [itemId] to [playlistId]; returns the index it landed at, when the service reports one. */
    suspend fun addToPlaylist(playlistId: String, itemId: String): Int?

    /** Removes one occurrence of [itemId]; see [MediaLibraryWriter.removeFromPlaylist]. */
    suspend fun removeFromPlaylist(playlistId: String, itemId: String, index: Int? = null)

    /** Renames [playlistId]; a refusal is thrown, so the caller can report the service's reason. */
    suspend fun renamePlaylist(playlistId: String, name: String)

    /** Deletes [playlistId]; a refusal is thrown, so the caller can report the service's reason. */
    suspend fun deletePlaylist(playlistId: String)
}

/**
 * The playlist writes of [MediaLibraryWriter] in terms of the client's [PlaylistTransport].
 *
 * The catalog contract passes a [MediaKey] and a [MediaItem]; the client takes ids. A provider whose
 * client implements [PlaylistTransport] supplies only that client and inherits the unwrapping, plus
 * the `true` the boolean returns promise once the service has answered. A provider that cannot
 * express its edits this way (the device library writes local files; Plex names playlist entries
 * rather than tracks) implements [MediaLibraryWriter] directly instead.
 */
interface ClientPlaylistWriter : MediaLibraryWriter {
    /** The client whose id-level calls perform the writes. */
    val playlistTransport: PlaylistTransport

    override suspend fun addToPlaylist(playlist: MediaKey, item: MediaItem): Int? = playlistTransport.addToPlaylist(playlist.id, item.id)

    override suspend fun removeFromPlaylist(playlist: MediaKey, item: MediaItem, index: Int?) = playlistTransport.removeFromPlaylist(playlist.id, item.id, index)

    override suspend fun renamePlaylist(playlist: MediaKey, name: String): Boolean {
        playlistTransport.renamePlaylist(playlist.id, name)
        return true
    }

    override suspend fun deletePlaylist(playlist: MediaKey): Boolean {
        playlistTransport.deletePlaylist(playlist.id)
        return true
    }
}

/**
 * A service this build can provision, whether or not it is the selected one. The picker needs a name
 * before any runtime exists, so identity and label live here; both come from the provider's own
 * constants, so a name cannot drift between the picker and the running service.
 */
data class ServiceChoice(val id: ServiceId, val displayName: String)

/**
 * One music service. The app owns exactly one selected provider, and the UI asks its [capabilities]
 * instead of asking which service is running.
 */
interface MusicServiceProvider {
    val id: ServiceId
    val displayName: String
    val capabilities: ServiceCapabilities

    /** What the sign-in screen must offer for this service. */
    val signInKind: SignInKind

    fun createAuth(): ServiceAuth
}
