package io.github.manuelrtsch.sidetrack

import hrln.interactive.sidekit.design.action.NoticeKind
import io.github.manuelrtsch.sidetrack.data.MediaCatalog
import io.github.manuelrtsch.sidetrack.data.MediaLibraryWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The playlist writes the UI can reach: create a playlist, append one track, take one entry back
 * out, and load the playlists that are actually addable.
 *
 * Add and remove are separate permissions ([PlaylistActions]), so both operations check the
 * playlist row itself instead of trusting the service flag alone. The picker only shows playlists
 * that pass the add check; a read-only row never becomes a target that fails at the server.
 */
internal class PlaylistController(
    private val store: UiStateStore,
    private val catalog: MediaCatalog,
    private val writer: MediaLibraryWriter?,
    private val capabilities: () -> ServiceCapabilities,
    private val onCreated: () -> Unit,
    private val online: () -> Boolean,
    private val scope: CoroutineScope,
    private val tasks: TaskRegistry
) {

    private fun catalogNotice(error: Exception): String = catalogMessage(error, online())

    private var choicesOffset = 0

    private fun action(kind: TaskKey.Action.Kind, target: String = "") = TaskKey.Action(kind, target)

    /**
     * Creates a playlist named [name], seeded with [seed] in that order, on the active service.
     * Returns null on failure so the caller can stay on the form; the reason is already in the bar.
     *
     * The write goes through the registry, so a second press while the first is still with the
     * service is refused instead of creating a second playlist with the same name.
     */
    suspend fun create(name: String, seed: List<MediaKey> = emptyList()): Playlist? = tasks.submit(action(TaskKey.Action.Kind.CreatePlaylist)) { createNow(name, seed) }

    private suspend fun createNow(name: String, seed: List<MediaKey>): Playlist? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            store.update { it.say("Enter a playlist name") }
            return null
        }
        val writer = writer ?: run {
            store.update { it.say("This service cannot create playlists") }
            return null
        }
        return try {
            val playlist = writer.createPlaylist(trimmed, seed)
            if (playlist == null) {
                store.update { it.say("This service cannot create playlists") }
            } else {
                // The new row belongs in the library and in a picker that was already loaded.
                invalidateChoices()
                onCreated()
            }
            playlist
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            store.update { it.say(catalogNotice(e)) }
            null
        }
    }

    /** Loads the first page once per picker visit; a later visit reuses the loaded list. */
    fun ensureChoices() {
        val state = store.value
        if (!state.signedIn || state.playlistChoicesLoaded) return
        if (tasks.isRunning(TaskKey.PlaylistChoices)) return
        loadChoices(reset = true)
    }

    /**
     * Opens the picker on its first page.
     *
     * The cursor here counts catalog rows, while the list shows only the playlists that accept
     * items, so an expanded picker cannot be truncated without breaking the offset it would page
     * from. It is refetched from the top instead: one page of playlists, and a list that reflects
     * what the account can add to today.
     */
    fun openChoices() {
        if (store.value.playlistChoices.size > CATALOG_PAGE_SIZE) {
            choicesOffset = 0
            store.update { it.copy(playlistChoicesLoaded = false) }
        }
        ensureChoices()
    }

    fun retryChoices() {
        if (tasks.isRunning(TaskKey.PlaylistChoices)) return
        choicesOffset = 0
        loadChoices(reset = true)
    }

    fun loadMoreChoices() {
        val state = store.value
        if (!state.playlistChoicesHasMore || tasks.isRunning(TaskKey.PlaylistChoices)) return
        loadChoices(reset = false)
    }

    private fun loadChoices(reset: Boolean) {
        if (!store.value.signedIn) return
        val offset = if (reset) 0 else choicesOffset
        store.update {
            it.copy(playlistChoicesError = if (reset) null else it.playlistChoicesError)
        }
        tasks.start(TaskKey.PlaylistChoices) {
            try {
                val page = catalog.library(LibrarySection.Playlists, offset, CATALOG_PAGE_SIZE)
                val addable = page.items.filterIsInstance<Playlist>()
                    .filter { capabilities().playlistActions(it).canAddItems }
                choicesOffset = offset + page.items.size
                store.update {
                    val merged = if (reset) {
                        addable
                    } else {
                        (it.playlistChoices + addable).distinctBy { playlist -> playlist.key.encode() }
                    }
                    it.copy(
                        playlistChoices = merged,
                        playlistChoicesLoaded = true,
                        playlistChoicesHasMore = page.hasMore,
                        playlistChoicesError = null
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (reset) choicesOffset = 0
                store.update {
                    it.copy(
                        playlistChoicesLoaded = if (reset) false else it.playlistChoicesLoaded,
                        playlistChoicesError = catalogNotice(e)
                    )
                }
            }
        }
    }

    private fun invalidateChoices() {
        choicesOffset = 0
        tasks.cancel(TaskKey.PlaylistChoices)
        store.update {
            it.copy(
                playlistChoices = emptyList(),
                playlistChoicesLoaded = false,
                playlistChoicesHasMore = false,
                playlistChoicesError = null
            )
        }
    }

    /**
     * Appends one item and returns the index the service placed it at, which is what Undo removes.
     * Null means nothing was written; the failure is already in the bar.
     */
    suspend fun add(playlist: Playlist, item: MediaItem): Int? = tasks.submit(
        action(TaskKey.Action.Kind.AddToPlaylist, playlist.key.encode())
    ) { addNow(playlist, item) }

    private suspend fun addNow(playlist: Playlist, item: MediaItem): Int? {
        if (!capabilities().playlistActions(playlist).canAddItems) {
            store.update { it.say("This playlist is read-only") }
            return null
        }
        val writer = writer ?: run {
            store.update { it.say("This service cannot edit playlists") }
            return null
        }
        return try {
            val index = writer.addToPlaylist(playlist.key, item)
            if (index == null) {
                store.update { it.say("This service cannot add to playlists") }
            } else {
                store.update { state ->
                    val count = state.playlistTrackCount(playlist.key, playlist.trackCount) + 1
                    // The target may already be on the back stack with its old track list cached.
                    state.copy(collections = state.collections - playlist.key.encode())
                        .withPlaylistTrackCount(playlist.key, count)
                }
                // Keep the confirmation a fixed width: a long track title would stretch the bar.
                store.update { it.say("Added track to ${playlist.name}", NoticeKind.Success) }
            }
            index
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            store.update { it.say(catalogNotice(e)) }
            null
        }
    }

    /** Takes back an append by removing exactly the index the service reported. */
    suspend fun undoAdd(playlist: Playlist, item: MediaItem, index: Int) {
        val writer = writer ?: return
        try {
            writer.removeFromPlaylist(playlist.key, item, index)
            store.update { state ->
                val count = (state.playlistTrackCount(playlist.key, playlist.trackCount) - 1).coerceAtLeast(0)
                state.copy(collections = state.collections - playlist.key.encode())
                    .withPlaylistTrackCount(playlist.key, count)
            }
            store.update { it.say("Removed ${item.title} from ${playlist.name}", NoticeKind.Success) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            store.update { it.say(catalogNotice(e)) }
        }
    }

    /**
     * Renames an open playlist. The title in the page header is the only field the service changes,
     * so every cached copy is patched instead of dropping the page and refetching it.
     */
    suspend fun rename(collection: Collection, name: String): Boolean = tasks.submit(
        action(TaskKey.Action.Kind.RenamePlaylist, collection.key.encode())
    ) { renameNow(collection, name) } ?: false

    private suspend fun renameNow(collection: Collection, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            store.update { it.say("Enter a playlist name") }
            return false
        }
        if (!capabilities().playlistActions(collection).canRename) {
            store.update { it.say("This playlist cannot be renamed") }
            return false
        }
        val writer = writer ?: run {
            store.update { it.say("This service cannot rename playlists") }
            return false
        }
        return try {
            if (!writer.renamePlaylist(collection.key, trimmed)) {
                store.update { it.say("This service cannot rename playlists") }
                return false
            }
            store.update { it.withPlaylistRenamed(collection.key, trimmed).say("Renamed to $trimmed", NoticeKind.Success) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            store.update { it.say(catalogNotice(e)) }
            false
        }
    }

    /**
     * Deletes an open playlist after the caller's Undo window has passed. The page and every cached
     * row are dropped only once the service confirms the deletion.
     */
    suspend fun delete(collection: Collection): Boolean = tasks.submit(
        action(TaskKey.Action.Kind.DeletePlaylist, collection.key.encode())
    ) { deleteNow(collection) } ?: false

    private suspend fun deleteNow(collection: Collection): Boolean {
        if (!capabilities().playlistActions(collection).canDelete) {
            store.update { it.say("This playlist cannot be deleted") }
            return false
        }
        val writer = writer ?: run {
            store.update { it.say("This service cannot delete playlists") }
            return false
        }
        return try {
            if (!writer.deletePlaylist(collection.key)) {
                store.update { it.say("This service cannot delete playlists") }
                return false
            }
            store.update { it.withPlaylistRemoved(collection.key).say("Deleted ${collection.title}", NoticeKind.Success) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            store.update { it.say(catalogNotice(e)) }
            false
        }
    }

    /** Runs [delete] from the ViewModel's scope, for a deletion committed while leaving the page. */
    fun deleteInBackground(collection: Collection) {
        scope.launch { delete(collection) }
    }

    /**
     * Removes the row at [index] after the writer confirms it. The local page drops exactly that
     * occurrence; `hasMore` stays as it was, so the next page's offset is still the number of rows
     * currently loaded.
     */
    fun remove(collection: Collection, item: MediaItem, index: Int) {
        if (!capabilities().playlistActions(collection).canRemoveItems) {
            store.update { it.say("This playlist is read-only") }
            return
        }
        val writer = writer ?: run {
            store.update { it.say("This service cannot edit playlists") }
            return
        }
        val cacheKey = collection.key.encode()
        scope.launch {
            try {
                writer.removeFromPlaylist(collection.key, item, index)
                store.update { state ->
                    val current = state.collections[cacheKey] ?: collection
                    val items = current.items.toMutableList()
                    val at = index.takeIf { items.getOrNull(it)?.id == item.id }
                        ?: items.indexOfFirst { it.id == item.id }.takeIf { it >= 0 }
                    if (at != null) items.removeAt(at)
                    val next = current.copy(
                        items = items,
                        totalItems = (current.totalItems - if (at != null) 1 else 0).coerceAtLeast(items.size)
                    )
                    state.copy(collections = state.collections + (cacheKey to next))
                        .withPlaylistTrackCount(collection.key, next.totalItems)
                        .say("Removed ${item.title} from ${current.title}", NoticeKind.Success)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                store.update { it.say(catalogNotice(e)) }
            }
        }
    }

    /**
     * The count currently shown for a playlist, wherever a cached row holds it. The object passed
     * from the UI can be older than the state after an earlier add or remove, so Undo must read the
     * patched count rather than `Playlist.trackCount` from the argument.
     */
    private fun SidetrackUiState.playlistTrackCount(key: MediaKey, fallback: Int): Int {
        val encoded = key.encode()
        fun Playlist.matches() = this.key.encode() == encoded

        collections[encoded]?.let { return it.totalItems }
        playlistChoices.firstOrNull { it.matches() }?.let { return it.trackCount }
        libraryPages.values.forEach { page ->
            page.items.filterIsInstance<Playlist>().firstOrNull { it.matches() }?.let { return it.trackCount }
        }
        librarySections.values.forEach { items ->
            items.filterIsInstance<Playlist>().firstOrNull { it.matches() }?.let { return it.trackCount }
        }
        searchResultsFor(searchPagesForQuery).run {
            playlists.firstOrNull { it.matches() }?.let { return it.trackCount }
        }
        return fallback
    }
}
