package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.MediaLibraryWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Saving a track or a collection and following an artist.
 *
 * All three are optimistic: the state flips first, the service is told after, and a failure puts
 * the tile back with a notice. The touched sets in [SavedHints] keep a page load that is still in
 * flight from overwriting the tap.
 */
internal class SavesController(
    private val store: UiStateStore,
    private val writer: MediaLibraryWriter?,
    private val hints: SavedHints,
    private val mediaKeys: MediaKeys,
    private val online: () -> Boolean,
    private val scope: CoroutineScope,
    private val tasks: TaskRegistry
) {

    private fun catalogNotice(error: Exception): String = catalogMessage(error, online())

    /**
     * The one optimistic write: [apply] renders the new value now, [write] tells the service, and a
     * failure renders the *old* value through the same lambda and says why.
     *
     * Taking the value to render rather than separate flip and undo lambdas keeps the rollback
     * from drifting away from the forward move. The write runs under [key], so a rapid second tap
     * supersedes the first: the state already shows the newer intent, and only that intent reaches
     * the service. A service with no writer still flips locally; there is nothing to tell.
     */
    private fun optimistic(
        key: TaskKey,
        desired: Boolean,
        apply: (SidetrackUiState, Boolean) -> SidetrackUiState,
        write: suspend (MediaLibraryWriter) -> Unit
    ) {
        store.update { apply(it, desired) }
        val writer = writer ?: return
        tasks.restart(key) {
            try {
                write(writer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                store.update { apply(it, !desired).say(catalogNotice(e)) }
            }
        }
    }

    fun toggleSaveCurrent() {
        val track = store.value.current as? Track ?: return
        toggleTrackSaved(track.id, track)
    }

    fun toggleSaved(track: Track) = toggleTrackSaved(track.id, track)

    fun toggleTrackSaved(
        id: String,
        track: Track?
    ) {
        val savedKey = mediaKeys.track(id).encode()
        val desired = savedKey !in store.value.savedTrackIds
        hints.markTrack(savedKey)
        optimistic(
            key = TaskKey.Action(TaskKey.Action.Kind.Save, savedKey),
            desired = desired,
            apply = { state, saved ->
                val ids = state.savedTrackIds.toMutableSet()
                if (saved) ids.add(savedKey) else ids.remove(savedKey)
                val next = state.copy(savedTrackIds = ids)
                if (saved) {
                    track?.let { item -> next.withLibraryItemAdded(LibraryFilter.LikedSongs, item, mediaKeys) } ?: next
                } else {
                    next.withLibraryItemRemoved(LibraryFilter.LikedSongs, id, mediaKeys)
                }
            },
            write = { writer -> writer.setSaved(mediaKeys.track(id), desired) }
        )
    }

    fun savedCollection(collection: Collection): Boolean = mediaKeys.collection(collection.type, collection.id) in store.value.savedCollectionKeys

    fun toggleSaved(collection: Collection) = toggleSaved(collection.type, collection.id, collection)

    private fun toggleSaved(type: CollectionType, id: String, collection: Collection) {
        val key = mediaKeys.collection(type, id)
        val desired = key !in store.value.savedCollectionKeys
        val addedItem = libraryItemFor(collection)
        hints.markCollection(key)
        optimistic(
            key = TaskKey.Action(TaskKey.Action.Kind.Save, key),
            desired = desired,
            apply = { state, saved ->
                val keys = state.savedCollectionKeys.toMutableSet()
                if (saved) keys.add(key) else keys.remove(key)
                val next = state.copy(savedCollectionKeys = keys)
                if (saved) {
                    addedItem?.let { item -> next.withLibraryItemAdded(libraryFilterFor(type), item, mediaKeys) } ?: next
                } else {
                    next.withLibraryItemRemoved(libraryFilterFor(type), id, mediaKeys)
                }
            },
            write = { writer -> writer.setSaved(mediaKeys.media(type, id), desired) }
        )
    }

    fun followed(artist: Artist): Boolean = mediaKeys.artist(artist.id).encode() in store.value.followedArtistIds

    fun toggleFollow(artist: Artist) {
        val id = artist.id
        val followedKey = mediaKeys.artist(id).encode()
        val desired = followedKey !in store.value.followedArtistIds
        hints.markFollow(followedKey)
        optimistic(
            key = TaskKey.Action(TaskKey.Action.Kind.Follow, followedKey),
            desired = desired,
            apply = { state, following ->
                val ids = state.followedArtistIds.toMutableSet()
                if (following) ids.add(followedKey) else ids.remove(followedKey)
                val next = state.copy(followedArtistIds = ids)
                if (following) {
                    next.withLibraryItemAdded(LibraryFilter.Artists, artist, mediaKeys)
                } else {
                    next.withLibraryItemRemoved(LibraryFilter.Artists, id, mediaKeys)
                }
            },
            write = { writer -> writer.setFollowed(mediaKeys.artist(id), desired) }
        )
    }

    /**
     * Reads a track's saved state when it first becomes relevant: starting it, or opening its
     * row's gutter. Idempotent, so a list can call it on every reveal.
     */
    fun ensureTrackSaved(id: String) {
        val writer = writer ?: return
        val savedKey = mediaKeys.track(id).encode()
        if (savedKey in store.value.savedTrackIds || hints.trackTouched(savedKey)) return
        scope.launch {
            val saved = runCatching { writer.isSaved(mediaKeys.track(id)) }.getOrNull() ?: return@launch
            if (saved && !hints.trackTouched(savedKey)) {
                store.update { it.copy(savedTrackIds = it.savedTrackIds + savedKey) }
            }
        }
    }
}
