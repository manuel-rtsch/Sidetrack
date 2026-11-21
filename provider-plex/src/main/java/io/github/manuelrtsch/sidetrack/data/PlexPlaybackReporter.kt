package io.github.manuelrtsch.sidetrack.data

import io.github.manuelrtsch.sidetrack.MediaItem
import io.github.manuelrtsch.sidetrack.ServiceId
import io.github.manuelrtsch.sidetrack.playback.PlaybackEngineState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Keeps Plex's dashboard and play history in step with the shared player.
 *
 * Plex wants a `/:/timeline` report when playback changes and periodically while playing, plus one
 * `/:/scrobble` when a track actually ends. [PlaybackReporter] owns that machine; this class only
 * maps a signal onto Plex's state strings.
 */
internal class PlexPlaybackReporter(
    private val api: () -> PlexApi?,
    state: StateFlow<PlaybackEngineState>,
    ended: Flow<String?>,
    currentItem: () -> MediaItem?,
    position: () -> Long,
    scope: CoroutineScope,
    clock: () -> Long = System::currentTimeMillis
) : PlaybackReporter<PlaybackReporter.Active>(
    service = ServiceId.Plex,
    state = state,
    ended = ended,
    currentItem = currentItem,
    position = position,
    scope = scope,
    clock = clock
) {
    override fun createActive(itemId: String, sourceUri: String, durationMs: Long): Active = Active(itemId, sourceUri, durationMs)

    override suspend fun started(active: Active) {
        reportTimeline(active, if (active.wasPlaying) "playing" else "paused")
    }

    override suspend fun progress(active: Active) {
        reportTimeline(active, "playing")
    }

    override suspend fun paused(active: Active) {
        reportTimeline(active, "paused")
    }

    override suspend fun stopped(active: Active, positionMs: Long) {
        reportTimeline(active, "stopped", positionMs)
    }

    override suspend fun completed(active: Active) {
        runCatching { api()?.reportScrobble(active.itemId) }
    }

    /**
     * A resume is a state change and Plex wants it immediately; the interval only throttles the
     * progress reports that follow.
     */
    override suspend fun onPlayingAgain(active: Active) {
        noteReport(active)
        reportTimeline(active, "playing")
    }

    private suspend fun reportTimeline(active: Active, state: String, positionMs: Long = active.lastPositionMs) {
        runCatching {
            api()?.reportTimeline(active.itemId, state, positionMs.coerceAtLeast(0L), active.durationMs)
        }
    }
}
