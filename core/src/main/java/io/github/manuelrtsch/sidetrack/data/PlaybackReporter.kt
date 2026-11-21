package io.github.manuelrtsch.sidetrack.data

import io.github.manuelrtsch.sidetrack.MediaItem
import io.github.manuelrtsch.sidetrack.MediaKey
import io.github.manuelrtsch.sidetrack.MediaType
import io.github.manuelrtsch.sidetrack.ServiceId
import io.github.manuelrtsch.sidetrack.playback.PlaybackEngineState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Keeps a server's session and play history in step with Sidetrack's shared player.
 *
 * Jellyfin and Plex need the same machine: a start report per item, akin to a periodic progress
 * report, one stop report per item, and — D12 — a play marked only when the player reports that
 * the track actually ended, never when the user skips. Only the request shape differs.
 *
 * Nothing here is on the playback path: every request is fire-and-forget, failures are swallowed
 * by the provider, and a slow server can never stall audio. Two signals drive it:
 *
 * - the engine's [PlaybackEngineState], which says which source is loaded, whether it is playing
 *   and how far it has got;
 * - the engine's end-of-track flow, which is the only reliable "finished" signal.
 *
 * The reporter only claims tracks ([MediaType.Track]); neither service's music model has episodes.
 */
abstract class PlaybackReporter<A : PlaybackReporter.Active>(
    private val service: ServiceId,
    private val state: StateFlow<PlaybackEngineState>,
    private val ended: Flow<String?>,
    private val currentItem: () -> MediaItem?,
    private val position: () -> Long,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private var job: Job? = null

    /** Only touched by the single collector coroutine, so no locking is needed. */
    private var active: A? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            merge(
                state.map { Signal.State(it) },
                ended.map { Signal.Ended(it) }
            ).collect { signal ->
                when (signal) {
                    is Signal.State -> onState(signal.value)
                    is Signal.Ended -> onEnded(signal.uri)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        active = null
    }

    /** A new item is loaded and [active] describes it. */
    protected abstract suspend fun started(active: A)

    /** A throttled progress tick while the item keeps playing. */
    protected abstract suspend fun progress(active: A)

    /** The item was playing and is now paused. Never reported for a pause that never played. */
    protected abstract suspend fun paused(active: A)

    /** The item is left behind — skipped, replaced or stopped — at [positionMs]. */
    protected abstract suspend fun stopped(active: A, positionMs: Long)

    /**
     * The item reached its own end: the only point a play may be marked. A skip comes through
     * [stopped] instead, so it can never be counted as a listen.
     */
    protected abstract suspend fun completed(active: A)

    /**
     * Playback resumed after a pause. The default lets the throttle decide, so a quick pause and
     * resume does not add a request; a provider whose server wants the state change immediately
     * overrides this.
     */
    protected open suspend fun onPlayingAgain(active: A) = maybeReportProgress(active)

    /**
     * Builds the per-item bookkeeping. Jellyfin overrides this to carry the `playbackInfo` its
     * reports are keyed on; Plex needs nothing beyond the shared fields.
     */
    protected abstract fun createActive(itemId: String, sourceUri: String, durationMs: Long): A

    /** The known length of the loaded item, or 0 when it is not the current one any more. */
    protected fun durationMsFor(itemId: String): Long = currentItem()?.takeIf { it.id == itemId }?.durationMs ?: 0L

    /** Records that a report just went out, restarting the progress interval. */
    protected fun noteReport(active: A) {
        active.lastReportAt = clock()
    }

    private suspend fun maybeReportProgress(active: A) {
        val now = clock()
        if (now - active.lastReportAt < PROGRESS_INTERVAL_MS) return
        active.lastReportAt = now
        progress(active)
    }

    private suspend fun onState(state: PlaybackEngineState) {
        val key = state.source?.id?.let(MediaKey::decode)
            ?.takeIf { it.service == service && it.type == MediaType.Track }
        val current = active
        if (key?.id != current?.itemId) {
            // A different source means the old item is done; it was not ended, so it stops.
            current?.let { finish(it, it.lastPositionMs) }
            active = key?.let { start(it.id, state.source?.uri.orEmpty(), state.positionMs) }
            return
        }
        if (current == null) return

        current.lastPositionMs = position().coerceAtLeast(0L)
        if (!state.isPlaying) {
            if (current.wasPlaying) {
                current.wasPlaying = false
                paused(current)
            }
            return
        }
        if (!current.wasPlaying) {
            current.wasPlaying = true
            onPlayingAgain(current)
            return
        }
        maybeReportProgress(current)
    }

    private suspend fun onEnded(uri: String?) {
        val current = active ?: return
        // The ViewModel may already have advanced to the next item, so match the player's own uri
        // rather than whatever is current now; a null uri carries no identity but is still ours.
        if (uri != null && current.sourceUri.isNotEmpty() && !uri.equals(current.sourceUri, ignoreCase = true)) return
        val atEnd = current.durationMs.takeIf { it > 0L } ?: position()
        stopped(current, atEnd)
        current.ended = true
        // The engine also emits a paused state for the same source; do not report that as a pause.
        current.wasPlaying = false
        completed(current)
        current.lastPositionMs = atEnd
    }

    private suspend fun start(itemId: String, sourceUri: String, startPositionMs: Long): A {
        val active = createActive(itemId, sourceUri, durationMsFor(itemId))
        active.lastPositionMs = startPositionMs.coerceAtLeast(0L)
        active.lastReportAt = clock()
        active.wasPlaying = state.value.isPlaying
        started(active)
        return active
    }

    private suspend fun finish(active: A, position: Long) {
        if (!active.ended) stopped(active, position.coerceAtLeast(0L))
        this.active = null
    }

    /** The item the reporter believes is loaded, and what it has already told the server. */
    open class Active(
        val itemId: String,
        val sourceUri: String,
        val durationMs: Long
    ) {
        var lastPositionMs = 0L
        var wasPlaying = false
        var lastReportAt = 0L
        var ended = false
    }

    private sealed interface Signal {
        data class State(val value: PlaybackEngineState) : Signal
        data class Ended(val uri: String?) : Signal
    }

    private companion object {
        /** Clients report roughly every 10 seconds; more only adds requests. */
        const val PROGRESS_INTERVAL_MS = 10_000L
    }
}
