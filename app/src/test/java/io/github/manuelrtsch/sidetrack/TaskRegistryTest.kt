package io.github.manuelrtsch.sidetrack

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The registry is the one owner of the running set, so its dedupe and replacement rules are pinned. */
@RunWith(AndroidJUnit4::class)
class TaskRegistryTest {

    @Test
    fun `a key runs once and clears when the block returns`() = runTest {
        val store = UiStateStore(SidetrackUiState())
        val registry = TaskRegistry(store, this)
        val gate = CompletableDeferred<Unit>()
        var runs = 0

        registry.start(TaskKey.PlaylistChoices) {
            runs++
            gate.await()
        }
        registry.start(TaskKey.PlaylistChoices) { runs++ }

        assertTrue(TaskKey.PlaylistChoices in store.value.runningTasks)
        runCurrent()
        assertEquals(1, runs)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, runs)
        assertTrue(store.value.runningTasks.isEmpty())
    }

    @Test
    fun `restart keeps the key running until the newer block ends`() = runTest {
        val store = UiStateStore(SidetrackUiState())
        val registry = TaskRegistry(store, this)
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()

        registry.restart(TaskKey.PlaylistChoices) { first.await() }
        runCurrent()
        registry.restart(TaskKey.PlaylistChoices) { second.await() }
        runCurrent()

        // The superseded block's cancellation must not clear the record owned by the new one.
        first.complete(Unit)
        advanceUntilIdle()
        assertTrue(TaskKey.PlaylistChoices in store.value.runningTasks)

        second.complete(Unit)
        advanceUntilIdle()
        assertTrue(store.value.runningTasks.isEmpty())
    }

    @Test
    fun `cancel clears the record immediately`() = runTest {
        val store = UiStateStore(SidetrackUiState())
        val registry = TaskRegistry(store, this)

        registry.start(TaskKey.SignIn) { awaitCancellation() }
        runCurrent()
        assertTrue(TaskKey.SignIn in store.value.runningTasks)

        registry.cancel(TaskKey.SignIn)
        assertFalse(TaskKey.SignIn in store.value.runningTasks)
    }

    @Test
    fun `cancelWhere leaves the other keys alone`() = runTest {
        val store = UiStateStore(SidetrackUiState())
        val registry = TaskRegistry(store, this)
        val tracks = TaskKey.SearchMore(SearchScope.Tracks)
        val albums = TaskKey.SearchMore(SearchScope.Albums)

        registry.start(tracks) { awaitCancellation() }
        registry.start(albums) { awaitCancellation() }
        registry.start(TaskKey.PlaylistChoices) { awaitCancellation() }
        runCurrent()

        registry.cancelWhere { it is TaskKey.SearchMore }

        assertFalse(tracks in store.value.runningTasks)
        assertFalse(albums in store.value.runningTasks)
        assertTrue(TaskKey.PlaylistChoices in store.value.runningTasks)
        registry.cancelAll()
    }
}
