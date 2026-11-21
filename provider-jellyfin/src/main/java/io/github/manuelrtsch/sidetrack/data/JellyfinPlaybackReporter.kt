package io.github.manuelrtsch.sidetrack.data

import io.github.manuelrtsch.sidetrack.MediaItem
import io.github.manuelrtsch.sidetrack.ServiceId
import io.github.manuelrtsch.sidetrack.playback.PlaybackEngineState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Keeps the Jellyfin server's session in step with Sidetrack's shared player.
 *
 * The server needs `reportPlaybackStart`, periodic `reportPlaybackProgress` and one
 * `reportPlaybackStopped` per item, and D12 says a track is marked played only when the player
 * reports that it actually ended — not when the user skips it. [PlaybackReporter] owns that machine
 * and the throttling; this class only says what a Jellyfin report looks like, and carries the
 * `playbackInfo` (media source and play session) the reports are keyed on.
 */
internal class JellyfinPlaybackReporter(
    private val api: () -> JellyfinApi?,
    private val playbackInfo: (String) -> JellyfinPlaybackInfo?,
    state: StateFlow<PlaybackEngineState>,
    ended: Flow<String?>,
    currentItem: () -> MediaItem?,
    position: () -> Long,
    scope: CoroutineScope,
    clock: () -> Long = System::currentTimeMillis
) : PlaybackReporter<JellyfinPlaybackReporter.JellyfinActive>(
    service = ServiceId.Jellyfin,
    state = state,
    ended = ended,
    currentItem = currentItem,
    position = position,
    scope = scope,
    clock = clock
) {
    override fun createActive(itemId: String, sourceUri: String, durationMs: Long): JellyfinActive = JellyfinActive(itemId, sourceUri, durationMs, playbackInfo(itemId))

    override suspend fun started(active: JellyfinActive) {
        val report = active.report(active.lastPositionMs, paused = !active.wasPlaying)
        runCatching { api()?.reportStart(report) }
    }

    override suspend fun progress(active: JellyfinActive) {
        val report = active.report(active.lastPositionMs, paused = false)
        runCatching { api()?.reportProgress(report) }
    }

    override suspend fun paused(active: JellyfinActive) {
        val report = active.report(active.lastPositionMs, paused = true)
        runCatching { api()?.reportProgress(report) }
    }

    override suspend fun stopped(active: JellyfinActive, positionMs: Long) {
        val report = active.report(positionMs, paused = false)
        runCatching { api()?.reportStopped(report, failed = false) }
    }

    override suspend fun completed(active: JellyfinActive) {
        runCatching { api()?.markPlayed(active.itemId) }
    }

    internal class JellyfinActive(
        itemId: String,
        sourceUri: String,
        durationMs: Long,
        private val info: JellyfinPlaybackInfo?
    ) : Active(itemId, sourceUri, durationMs) {
        fun report(positionMs: Long, paused: Boolean) = JellyfinPlaybackReport(
            itemId = itemId,
            positionMs = positionMs.coerceAtLeast(0L),
            mediaSourceId = info?.mediaSourceId,
            playSessionId = info?.playSessionId,
            paused = paused,
            directPlay = info?.directPlay ?: true
        )
    }
}
