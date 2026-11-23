package io.github.manuelrtsch.sidetrack

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.manuelrtsch.sidetrack.data.EpisodeMetaRepository
import io.github.manuelrtsch.sidetrack.data.EpisodeProgressRepository
import io.github.manuelrtsch.sidetrack.data.HistoryRepository
import io.github.manuelrtsch.sidetrack.data.QueueRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Clearing the cache is a write, so it fails like one.
 *
 * The clear used to launch without a `catch`: a directory the process may not touch took the app
 * down instead of telling the user. It now runs through the record and lands in the bar.
 */
@RunWith(AndroidJUnit4::class)
class ClearCacheFailureTest {

    @Test
    fun `a throwing clear becomes a notice and the size is refreshed`() = runTest {
        val store = UiStateStore(SidetrackUiState(signedIn = true, cacheSizeBytes = 7L))
        val controller = controller(store, this, clearArtworkCache = { error("read-only directory") })

        controller.clearCache()
        // The clear hops to Dispatchers.IO, which the test scheduler does not control: wait for
        // the record to settle instead of for the scheduler to run dry.
        store.state.first { it.notice != null && it.cacheSizeBytes == 0L }

        assertEquals("Couldn't clear the cache", store.value.notice)
        // The size is re-read either way: a failed clear may still have removed part of it, and
        // the row must not keep a number nobody can vouch for.
        assertEquals(0L, store.value.cacheSizeBytes)
        assertEquals(emptySet<TaskKey>(), store.value.runningTasks)
    }

    @Test
    fun `a clear that works says nothing and refreshes the size`() = runTest {
        val store = UiStateStore(SidetrackUiState(signedIn = true, cacheSizeBytes = 7L))
        var cleared = 0
        val controller = controller(store, this, clearArtworkCache = { cleared++ })

        controller.clearCache()
        store.state.first { it.runningTasks.isEmpty() && it.cacheSizeBytes == 0L }

        assertEquals(1, cleared)
        assertNull(store.value.notice)
        assertEquals(0L, store.value.cacheSizeBytes)
    }

    private fun controller(
        store: UiStateStore,
        scope: CoroutineScope,
        clearArtworkCache: () -> Unit
    ): SettingsController {
        val history = HistoryRepository()
        val queue = QueueRepository()
        val clock = PlaybackClock(
            snapshot = { store.value },
            onPosition = {},
            onPlaying = {},
            onEpisodeProgress = {}
        )
        val effects = PlaybackEffects(
            store = store,
            queue = queue,
            history = history,
            episodes = EpisodeProgressRepository(),
            episodeMeta = EpisodeMetaRepository(),
            clock = clock,
            playback = { null },
            resumePoint = { null },
            scope = scope
        )
        return SettingsController(
            store = store,
            session = null,
            history = history,
            queue = queue,
            effects = effects,
            clearArtworkCache = clearArtworkCache,
            scope = scope,
            tasks = TaskRegistry(store, scope)
        )
    }
}
