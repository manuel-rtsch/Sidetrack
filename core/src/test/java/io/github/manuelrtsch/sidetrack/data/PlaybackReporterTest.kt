package io.github.manuelrtsch.sidetrack.data

import io.github.manuelrtsch.sidetrack.MediaKey
import io.github.manuelrtsch.sidetrack.MediaType
import io.github.manuelrtsch.sidetrack.ServiceId
import io.github.manuelrtsch.sidetrack.playback.PlaybackEngineState
import io.github.manuelrtsch.sidetrack.playback.PlaybackSource
import io.github.manuelrtsch.sidetrack.test.PlaybackReporterFixture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared reporter is where D12 lives: a track that reaches its own end is marked played, a
 * skip only reports where playback stopped. Pinning it once here keeps the per-provider tests for
 * request shape only; the Jellyfin and Plex suites run the same machine with real report objects.
 */
class PlaybackReporterTest : PlaybackReporterFixture() {
    private val calls = mutableListOf<String>()

    private inner class Recorder(scope: CoroutineScope) :
        PlaybackReporter<PlaybackReporter.Active>(
            service = ServiceId.Jellyfin,
            state = state,
            ended = ended,
            currentItem = { item },
            position = { position },
            scope = scope,
            clock = { now }
        ) {
        override fun createActive(itemId: String, sourceUri: String, durationMs: Long): Active = Active(itemId, sourceUri, durationMs)

        override suspend fun started(active: Active) {
            calls += "start:${active.itemId}"
            noteReport(active)
        }

        override suspend fun progress(active: Active) {
            calls += "progress:${active.lastPositionMs}"
        }

        override suspend fun paused(active: Active) {
            calls += "paused:${active.lastPositionMs}"
        }

        override suspend fun stopped(active: Active, positionMs: Long) {
            calls += "stopped:${active.itemId}@$positionMs"
        }

        override suspend fun completed(active: Active) {
            calls += "completed:${active.itemId}"
        }
    }

    private fun source(id: String, uri: String = "https://stream") = PlaybackEngineState(
        source = PlaybackSource(MediaKey(ServiceId.Jellyfin, MediaType.Track, id).encode(), uri),
        isPlaying = true
    )

    @Test fun `a finished track is completed and a skipped one is only stopped`() = runTest {
        val reporter = Recorder(this)
        reporter.start()

        state.value = source("t1")
        advanceUntilIdle()
        assertEquals(listOf("start:t1"), calls)

        ended.emit("https://stream")
        advanceUntilIdle()
        assertEquals(listOf("start:t1", "stopped:t1@200000", "completed:t1"), calls)

        // The next track starts and is replaced without ending: a skip must never be a play.
        calls.clear()
        state.value = source("t2", "https://stream-2")
        advanceUntilIdle()
        position = 5_000L
        state.value = state.value.copy(positionMs = position)
        advanceUntilIdle()
        state.value = source("t3", "https://stream-3")
        advanceUntilIdle()
        assertEquals(listOf("start:t2", "stopped:t2@5000", "start:t3"), calls)
        reporter.stop()
        advanceUntilIdle()
    }

    @Test fun `progress is throttled to one report per interval`() = runTest {
        val reporter = Recorder(this)
        reporter.start()
        state.value = source("t1")
        advanceUntilIdle()

        position = 40_000L
        now = 12_001L
        state.value = state.value.copy(positionMs = position)
        advanceUntilIdle()
        assertEquals(listOf("start:t1", "progress:40000"), calls)

        now = 15_000L
        position = 43_000L
        state.value = state.value.copy(positionMs = position)
        advanceUntilIdle()
        assertEquals(listOf("start:t1", "progress:40000"), calls)

        now = 25_000L
        state.value = state.value.copy(positionMs = 44_000L)
        advanceUntilIdle()
        assertEquals(listOf("start:t1", "progress:40000", "progress:43000"), calls)
        reporter.stop()
        advanceUntilIdle()
    }

    @Test fun `a pause is reported once and only after the item played`() = runTest {
        val reporter = Recorder(this)
        reporter.start()
        state.value = source("t1")
        advanceUntilIdle()

        position = 30_000L
        now = 5_000L
        state.value = state.value.copy(isPlaying = false, positionMs = position)
        advanceUntilIdle()
        assertEquals(listOf("start:t1", "paused:30000"), calls)

        // A paused player does not keep reporting its pause.
        state.value = state.value.copy(positionMs = 31_000L)
        advanceUntilIdle()
        assertEquals(1, calls.count { it.startsWith("paused") })
        assertTrue(calls.none { it.startsWith("stopped") || it.startsWith("completed") })
        reporter.stop()
        advanceUntilIdle()
    }

    @Test fun `resuming after a pause waits for the progress interval`() = runTest {
        val reporter = Recorder(this)
        reporter.start()
        state.value = source("t1")
        advanceUntilIdle()

        position = 30_000L
        now = 5_000L
        state.value = state.value.copy(isPlaying = false, positionMs = position)
        advanceUntilIdle()
        assertEquals(listOf("start:t1", "paused:30000"), calls)

        // A resume right after the pause is not a report of its own; the interval still applies.
        now = 6_000L
        state.value = state.value.copy(isPlaying = true)
        advanceUntilIdle()
        assertEquals(listOf("start:t1", "paused:30000"), calls)

        now = 12_000L
        state.value = state.value.copy(positionMs = 32_000L)
        advanceUntilIdle()
        assertEquals(listOf("start:t1", "paused:30000", "progress:30000"), calls)
        reporter.stop()
        advanceUntilIdle()
    }

    @Test fun `an end from another source or without an active item changes nothing`() = runTest {
        val reporter = Recorder(this)
        reporter.start()
        state.value = source("t1")
        advanceUntilIdle()

        // The player's uri no longer matches ours, so this end belongs to something else.
        ended.emit("https://elsewhere")
        advanceUntilIdle()
        assertEquals(listOf("start:t1"), calls)

        // An album source is not a track: the reporter lets go of the item.
        state.value = PlaybackEngineState(
            source = PlaybackSource(MediaKey(ServiceId.Jellyfin, MediaType.Album, "a1").encode(), "https://album"),
            isPlaying = true
        )
        advanceUntilIdle()
        assertEquals(listOf("start:t1", "stopped:t1@0"), calls)

        // With nothing active an end signal is ignored rather than reported.
        ended.emit(null)
        advanceUntilIdle()
        assertEquals(listOf("start:t1", "stopped:t1@0"), calls)
        reporter.stop()
        advanceUntilIdle()
    }

    @Test fun `a track without a known duration ends at the player position`() = runTest {
        val reporter = Recorder(this)
        reporter.start()
        // The item the catalog knows is t1; t2 is not it, so its duration is unknown.
        state.value = source("t2")
        advanceUntilIdle()

        position = 42_000L
        ended.emit("https://stream")
        advanceUntilIdle()
        assertEquals(listOf("start:t2", "stopped:t2@42000", "completed:t2"), calls)
        reporter.stop()
        advanceUntilIdle()
    }

    @Test fun `an idle player reports nothing and a source without a uri still ends`() = runTest {
        val reporter = Recorder(this)
        reporter.start()

        // Nothing loaded: there is no item to start and no end to report.
        state.value = PlaybackEngineState()
        advanceUntilIdle()

        // Stopping twice must be safe; the reporter can be started again afterwards.
        reporter.stop()
        reporter.stop()

        val second = Recorder(this)
        second.start()
        state.value = PlaybackEngineState(
            source = PlaybackSource(MediaKey(ServiceId.Jellyfin, MediaType.Track, "t1").encode(), ""),
            isPlaying = true
        )
        advanceUntilIdle()
        ended.emit("https://stream")
        advanceUntilIdle()
        assertEquals(listOf("start:t1", "stopped:t1@200000", "completed:t1"), calls)
        second.stop()
        advanceUntilIdle()
    }
}
