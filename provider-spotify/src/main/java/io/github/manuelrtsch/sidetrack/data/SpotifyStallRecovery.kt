package io.github.manuelrtsch.sidetrack.data

import android.os.SystemClock
import io.github.manuelrtsch.sidetrack.MediaKey
import io.github.manuelrtsch.sidetrack.SidetrackDebug
import io.github.manuelrtsch.sidetrack.playback.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Rebuilds playback when the audio pipeline goes silent, and restarts the saved item when a play
 * command arrives with nothing loaded.
 *
 * When the access-point connection dies, librespot stops producing PCM and nothing in the player
 * notices: the app keeps reporting a playing track while delivering silence. Recovery reloads the
 * item where the user left it and gives up visibly after a few attempts instead of looping.
 */
internal class SpotifyStallRecovery(
    private val playback: PlaybackController,
    private val playbackScope: CoroutineScope,
    private val queue: QueueRepository,
    /** Whether the device has a network; null when the caller cannot tell. */
    private val online: StateFlow<Boolean>? = null,
    private val play: suspend (MediaKey, String, Long, Boolean) -> Unit
) {
    /** Stall recoveries started inside [STALL_RECOVERY_WINDOW_MS]; main thread only. */
    private val recoveries = ArrayDeque<Long>()

    /** The one recovery waiting for the network to come back, or null. */
    private var awaitingNetwork: Job? = null

    /**
     * Starts the queue's current item when a play command arrives with nothing loaded.
     *
     * The queue survives process death but the native player does not, so after a restart there is
     * no source to resume. Used when no ViewModel is attached; a ViewModel installs the accurate
     * path via [PlaybackController.onResumeWithoutSource], because only it knows the saved offsets.
     */
    fun startRestoredItem() {
        val item = queue.snapshot.current ?: return
        val uri = item.uri ?: return
        val positionMs = playback.position
        SidetrackDebug.log("startRestoredItem uri=$uri positionMs=$positionMs")
        playbackScope.launch {
            // Nothing is loaded, so this is a cold start or a recovery: a fresh connection is
            // cheaper than discovering a dead one after the first load fails.
            runCatching { play(item.key, uri, positionMs.coerceAtLeast(0L), true) }
                .onFailure {
                    SidetrackDebug.log("startRestoredItem failed", it)
                    playback.pause()
                }
        }
    }

    /**
     * Rebuilds playback after the audio pipeline went silent.
     *
     * When the access-point connection dies, librespot stops producing PCM and nothing in the
     * player notices: the app kept reporting a playing track while delivering silence, and only
     * a manual press of play recovered it. This reloads the item where the user left it, and
     * gives up (visibly) after a few attempts instead of looping through reconnects.
     */
    fun recover(positionMs: Long) {
        val item = playback.current ?: return
        val uri = item.uri ?: return
        val now = SystemClock.elapsedRealtime()
        while (recoveries.isNotEmpty() && now - recoveries.first() > STALL_RECOVERY_WINDOW_MS) {
            recoveries.removeFirst()
        }
        if (recoveries.size >= STALL_RECOVERY_LIMIT) {
            SidetrackDebug.log("recoverPlayback giving up after ${recoveries.size} attempts")
            playback.pause()
            playback.emitNotice("Playback stopped — check the connection")
            return
        }
        if (online?.value == false) {
            // A stall while the radio is off is not a stall the player can recover from: the attempt
            // would walk every access point and report the same DNS failure, and the silence would
            // last as long as the connection walk. Waiting costs nothing and keeps the attempt count.
            SidetrackDebug.log("recoverPlayback deferred: no network uri=$uri positionMs=$positionMs")
            playback.pause()
            playback.emitNotice("Playback stopped — check the connection")
            awaitNetwork(item.key, uri, positionMs)
            return
        }
        attempt(item.key, uri, positionMs, now)
    }

    /**
     * Rebuilds the item once, and falls back to waiting for the network when the rebuild fails
     * because it is gone — the case that used to end with a dead player and no retry.
     */
    private fun attempt(key: MediaKey, uri: String, positionMs: Long, now: Long) {
        recoveries.addLast(now)
        SidetrackDebug.log("recoverPlayback uri=$uri positionMs=$positionMs attempt=${recoveries.size}")
        playbackScope.launch {
            // A stall usually means the access-point connection is dead even when librespot has
            // not marked the session invalid yet, so rebuild it instead of reloading on the same
            // connection and stalling again.
            runCatching { play(key, uri, positionMs.coerceAtLeast(0L), true) }
                .onSuccess {
                    awaitingNetwork?.cancel()
                    awaitingNetwork = null
                    playback.emitNotice(null)
                }
                .onFailure {
                    SidetrackDebug.log("recoverPlayback failed", it)
                    playback.pause()
                    playback.emitNotice("Playback stopped — check the connection")
                    if (online?.value == false) awaitNetwork(key, uri, positionMs)
                }
        }
    }

    /**
     * Retries once the network is back, unless the user moved on in the meantime: a recovery must
     * never start a track the user has already left, and must not fight a later choice.
     */
    private fun awaitNetwork(key: MediaKey, uri: String, positionMs: Long) {
        val network = online ?: return
        if (awaitingNetwork?.isActive == true) return
        SidetrackDebug.log("recoverPlayback waiting for the network uri=$uri")
        awaitingNetwork = playbackScope.launch {
            network.first { it }
            if (playback.current?.key != key || playback.isPlaying) {
                SidetrackDebug.log("recoverPlayback skipped: playback moved on")
                return@launch
            }
            SidetrackDebug.log("recoverPlayback retrying now that the network is back uri=$uri")
            recover(positionMs)
        }
    }

    private companion object {
        /** Stall recoveries allowed inside the window before playback gives up and says so. */
        const val STALL_RECOVERY_LIMIT = 3

        /** Window for [STALL_RECOVERY_LIMIT]; a one-off hiccup must not lock the user out. */
        const val STALL_RECOVERY_WINDOW_MS = 5 * 60_000L
    }
}
