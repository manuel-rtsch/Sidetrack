package io.github.manuelrtsch.sidetrack

import androidx.test.ext.junit.runners.AndroidJUnit4
import hrln.interactive.sidekit.design.action.NoticeKind
import io.github.manuelrtsch.sidetrack.data.MediaCatalog
import io.github.manuelrtsch.sidetrack.data.MediaLibraryWriter
import io.github.manuelrtsch.sidetrack.playback.PlaybackSource
import io.github.manuelrtsch.sidetrack.test.SilentMediaCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistControllerTest {

    private val first = track("first")
    private val second = track("second")
    private val third = track("third")

    @Test
    fun `picker keeps only playlists that accept items`() = runTest {
        val editable = playlist("editable", PlaylistActions.All)
        val readOnly = playlist("readonly", PlaylistActions.None)
        val catalog = FakeCatalog(playlistPages = mapOf(0 to listOf(readOnly, editable)))
        val store = store()
        val controller = controller(catalog, store, this)

        controller.ensureChoices()
        advanceUntilIdle()

        assertEquals(listOf(editable), store.value.playlistChoices)
        assertTrue(store.value.playlistChoicesLoaded)
    }

    @Test
    fun `picker pages by catalog rows even when a page has no editable playlist`() = runTest {
        val readOnly = playlist("readonly", PlaylistActions.None)
        val editable = playlist("editable", PlaylistActions.All)
        val secondEditable = playlist("later", PlaylistActions.All)
        val catalog = FakeCatalog(
            playlistPages = mapOf(
                0 to listOf(readOnly, editable),
                2 to listOf(secondEditable)
            )
        )
        val store = store()
        val controller = controller(catalog, store, this)

        controller.ensureChoices()
        advanceUntilIdle()
        assertEquals(listOf(editable), store.value.playlistChoices)
        assertEquals(listOf(0), catalog.requestedOffsets)

        controller.loadMoreChoices()
        advanceUntilIdle()

        assertEquals(listOf(0, 2), catalog.requestedOffsets)
        assertEquals(listOf(editable, secondEditable), store.value.playlistChoices)
    }

    @Test
    fun `the picker reopens on its first page`() = runTest {
        val firstPage = (1..CATALOG_PAGE_SIZE).map { playlist("p$it", PlaylistActions.All) }
        val secondPage = (CATALOG_PAGE_SIZE + 1..CATALOG_PAGE_SIZE + 5).map { playlist("p$it", PlaylistActions.All) }
        val catalog = FakeCatalog(
            playlistPages = mapOf(0 to firstPage, CATALOG_PAGE_SIZE to secondPage)
        )
        val store = store()
        val controller = controller(catalog, store, this)

        controller.ensureChoices()
        advanceUntilIdle()
        controller.loadMoreChoices()
        advanceUntilIdle()
        assertEquals(CATALOG_PAGE_SIZE + 5, store.value.playlistChoices.size)

        controller.openChoices()
        advanceUntilIdle()

        // The picker's cursor counts catalog rows, so an expanded list is refetched from the top
        // rather than truncated: it opens on one page, with the rest offered again.
        assertEquals(CATALOG_PAGE_SIZE, store.value.playlistChoices.size)
        assertEquals(listOf(0, CATALOG_PAGE_SIZE, 0), catalog.requestedOffsets)
    }

    @Test
    fun `add reports the index and invalidates the target collection`() = runTest {
        val target = playlist("target", PlaylistActions.All)
        val cacheKey = target.key.encode()
        val stale = collection("target", listOf(first), PlaylistActions.All)
        val row = target.copy(trackCount = 1)
        val writer = FakeWriter(addIndex = 7)
        val store = store(
            collections = mapOf(cacheKey to stale),
            libraryPages = mapOf(LibraryFilter.Playlists to LibraryPage(listOf(row)))
        )
        val controller = controller(FakeCatalog(), store, this, writer = writer)

        val index = controller.add(target, second)

        assertEquals(7, index)
        assertEquals(target.key, writer.addedPlaylist)
        assertEquals(second, writer.addedItem)
        assertTrue(cacheKey !in store.value.collections)
        assertEquals(2, store.value.playlistRow("target").trackCount)
        assertEquals("Added track to ${target.name}", store.value.notice)
        assertEquals(NoticeKind.Success, store.value.noticeKind)
    }

    @Test
    fun `remove drops exactly the acted-on occurrence from the cached page`() = runTest {
        val target = playlist("target", PlaylistActions.All)
        val cacheKey = target.key.encode()
        val cached = collection("target", listOf(first, second, third), PlaylistActions.All)
        val row = target.copy(trackCount = 3)
        val writer = FakeWriter()
        val store = store(
            collections = mapOf(cacheKey to cached),
            libraryPages = mapOf(LibraryFilter.Playlists to LibraryPage(listOf(row)))
        )
        val controller = controller(FakeCatalog(), store, this, writer = writer)

        controller.remove(cached, second, 1)
        advanceUntilIdle()

        val updated = store.value.collections.getValue(cacheKey)
        assertEquals(listOf(first, third), updated.items)
        assertEquals(2, updated.totalItems)
        assertEquals(2, store.value.playlistRow("target").trackCount)
        assertEquals(target.key, writer.removedPlaylist)
        assertEquals(second, writer.removedItem)
        assertEquals(1, writer.removedIndex)
    }

    @Test
    fun `create forwards the trimmed name and the seed in order`() = runTest {
        val writer = FakeWriter()
        val controller = controller(FakeCatalog(), store(), this, writer = writer)

        controller.create("  Trip  ", listOf(first.key, second.key))

        assertEquals("Trip", writer.createdName)
        assertEquals(listOf(first.key, second.key), writer.createdSeed)
    }

    @Test
    fun `read-only playlists are never written`() = runTest {
        val readOnly = playlist("readonly", PlaylistActions.None)
        val writer = FakeWriter()
        val controller = controller(FakeCatalog(), store(), this, writer = writer)

        assertEquals(null, controller.add(readOnly, first))
        assertEquals(0, writer.addCalls)
    }

    @Test
    fun `rename patches the cached page and every cached row`() = runTest {
        val cached = cachedTarget()
        val target = cached.target
        val cacheKey = target.key.encode()

        assertTrue(cached.controller.rename(cached.collection, "  New name  "))

        assertEquals(target.key, cached.writer.renamedPlaylist)
        assertEquals("New name", cached.writer.renamedName)
        assertEquals("New name", cached.store.value.collections.getValue(cacheKey).title)
        assertEquals("New name", cached.store.value.playlistRow("target").name)
        assertEquals("Renamed to New name", cached.store.value.notice)
        assertEquals(NoticeKind.Success, cached.store.value.noticeKind)
    }

    @Test
    fun `read-only playlists are never renamed or deleted`() = runTest {
        val readOnly = collection("readonly", listOf(first), PlaylistActions.None)
        val writer = FakeWriter()
        val controller = controller(FakeCatalog(), store(), this, writer = writer)

        assertEquals(false, controller.rename(readOnly, "Nope"))
        assertEquals(false, controller.delete(readOnly))
        assertEquals(0, writer.renamedCalls)
        assertEquals(0, writer.deletedCalls)
    }

    @Test
    fun `delete drops the page and the row only after the service confirms`() = runTest {
        val cached = cachedTarget()
        val target = cached.target
        val cacheKey = target.key.encode()

        assertTrue(cached.controller.delete(cached.collection))

        assertEquals(target.key, cached.writer.deletedPlaylist)
        assertTrue(cacheKey !in cached.store.value.collections)
        assertTrue(cached.store.value.libraryPages.getValue(LibraryFilter.Playlists).items.isEmpty())
        assertEquals("Deleted Target", cached.store.value.notice)
        assertEquals(NoticeKind.Success, cached.store.value.noticeKind)
    }

    @Test
    fun `a second create is refused while the first is in flight`() = runTest {
        val writer = FakeWriter(gate = CompletableDeferred())
        val controller = controller(FakeCatalog(), store(), this, writer = writer)

        var created: Playlist? = null
        val first = launch { created = controller.create("Sunday") }
        runCurrent()
        assertEquals("Sunday", writer.createdName)

        // The button is still on screen and still pressable; the write itself is the guard, so a
        // second playlist cannot be created from two taps on the same press.
        assertNull(controller.create("Sunday"))
        assertEquals(1, writer.createCalls)

        (writer.gate as CompletableDeferred<Unit>).complete(Unit)
        first.join()
        assertEquals("Sunday", created?.name)
    }

    @Test
    fun `a second add to the same playlist is refused while the first is in flight`() = runTest {
        val target = playlist("target", PlaylistActions.All)
        val writer = FakeWriter(gate = CompletableDeferred())
        val controller = controller(FakeCatalog(), store(), this, writer = writer)

        var addedAt: Int? = null
        val first = launch { addedAt = controller.add(target, second) }
        runCurrent()
        assertEquals(1, writer.addCalls)

        assertNull(controller.add(target, third))
        assertEquals(1, writer.addCalls)

        (writer.gate as CompletableDeferred<Unit>).complete(Unit)
        first.join()
        assertEquals(3, addedAt)
    }

    private fun SidetrackUiState.playlistRow(id: String): Playlist = libraryPages.values
        .flatMap { it.items }
        .filterIsInstance<Playlist>()
        .first { it.id == id }

    /** A cached "target" playlist, the store holding it, and the writer the controller writes through. */
    private class CachedTarget(
        val target: Playlist,
        val collection: Collection,
        val store: UiStateStore,
        val writer: FakeWriter,
        val controller: PlaylistController
    )

    private fun TestScope.cachedTarget(): CachedTarget {
        val target = playlist("target", PlaylistActions.All)
        val collection = collection("target", listOf(first), PlaylistActions.All)
        val writer = FakeWriter()
        val store = store(
            collections = mapOf(target.key.encode() to collection),
            libraryPages = mapOf(LibraryFilter.Playlists to LibraryPage(listOf(target)))
        )
        return CachedTarget(target, collection, store, writer, controller(FakeCatalog(), store, this, writer = writer))
    }

    private fun store(
        collections: Map<String, Collection> = emptyMap(),
        libraryPages: Map<LibraryFilter, LibraryPage> = emptyMap()
    ) = UiStateStore(
        SidetrackUiState(signedIn = true, collections = collections, libraryPages = libraryPages)
    )

    private fun controller(
        catalog: MediaCatalog,
        store: UiStateStore,
        scope: kotlinx.coroutines.CoroutineScope,
        writer: MediaLibraryWriter? = FakeWriter()
    ) = PlaylistController(
        store = store,
        catalog = catalog,
        writer = writer,
        capabilities = { ServiceCapabilities.All },
        onCreated = {},
        online = { true },
        scope = scope,
        tasks = TaskRegistry(store, scope)
    )

    private fun track(id: String) = Track(
        id = id,
        title = id.replaceFirstChar { it.uppercase() },
        artist = "Artist",
        artistId = "artist",
        albumId = "album",
        artRes = 0,
        durationMs = 180_000L
    )

    private fun playlist(id: String, actions: PlaylistActions) = Playlist(
        id = id,
        name = id.replaceFirstChar { it.uppercase() },
        owner = "You",
        ownerId = null,
        artRes = 0,
        tracks = emptyList(),
        actions = actions
    )

    private fun collection(id: String, items: List<Track>, actions: PlaylistActions) = Collection(
        type = CollectionType.Playlist,
        id = id,
        title = id.replaceFirstChar { it.uppercase() },
        subtitle = "You",
        artRes = 0,
        items = items,
        playlistActions = actions
    )

    private class FakeCatalog(
        private val playlistPages: Map<Int, List<Playlist>> = emptyMap()
    ) : SilentMediaCatalog(ServiceCapabilities.All) {
        val requestedOffsets = mutableListOf<Int>()

        override suspend fun library(section: LibrarySection, offset: Int, limit: Int): Page<LibraryEntry> {
            if (section != LibrarySection.Playlists) return Page()
            requestedOffsets += offset
            return Page(
                items = playlistPages[offset].orEmpty(),
                total = playlistPages.values.sumOf { it.size },
                hasMore = playlistPages.keys.any { it > offset }
            )
        }

        override suspend fun resolvePlaybackSource(item: MediaItem): PlaybackSource = PlaybackSource(id = item.key.encode(), uri = "")
    }

    private class FakeWriter(
        val addIndex: Int = 3,
        /** Holds the write open so a test can press the same button again mid-flight. */
        val gate: CompletableDeferred<Unit>? = null
    ) : MediaLibraryWriter {
        var addCalls = 0
        var createCalls = 0
        var addedPlaylist: MediaKey? = null
        var addedItem: MediaItem? = null
        var removedPlaylist: MediaKey? = null
        var removedItem: MediaItem? = null
        var removedIndex: Int? = null
        var createdName: String? = null
        var createdSeed: List<MediaKey>? = null
        var renamedCalls = 0
        var renamedPlaylist: MediaKey? = null
        var renamedName: String? = null
        var deletedCalls = 0
        var deletedPlaylist: MediaKey? = null

        override suspend fun isSaved(item: MediaKey): Boolean? = null
        override suspend fun isFollowed(artist: MediaKey): Boolean? = null
        override suspend fun setSaved(item: MediaKey, saved: Boolean) = Unit
        override suspend fun setFollowed(artist: MediaKey, followed: Boolean) = Unit

        override suspend fun addToPlaylist(playlist: MediaKey, item: MediaItem): Int? {
            addCalls++
            gate?.await()
            addedPlaylist = playlist
            addedItem = item
            return addIndex
        }

        override suspend fun removeFromPlaylist(playlist: MediaKey, item: MediaItem, index: Int?) {
            removedPlaylist = playlist
            removedItem = item
            removedIndex = index
        }

        override suspend fun renamePlaylist(playlist: MediaKey, name: String): Boolean {
            renamedCalls++
            renamedPlaylist = playlist
            renamedName = name
            return true
        }

        override suspend fun deletePlaylist(playlist: MediaKey): Boolean {
            deletedCalls++
            deletedPlaylist = playlist
            return true
        }

        override suspend fun createPlaylist(name: String, seed: List<MediaKey>): Playlist {
            createCalls++
            createdName = name
            createdSeed = seed
            gate?.await()
            return Playlist(
                id = "created",
                name = name,
                owner = "You",
                ownerId = null,
                artRes = 0,
                tracks = emptyList(),
                actions = PlaylistActions.All
            )
        }
    }
}
