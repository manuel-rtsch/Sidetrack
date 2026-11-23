package io.github.manuelrtsch.sidetrack

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What the transport is doing, not just whether it claims to play.
 *
 * `isPlaying` could not say "the source is still resolving": the UI drew a pause button and
 * "now playing" while the service had not produced a single sample. [Starting] is that window.
 * [Failed] carries the reason a start ended without audio, so the surface can show it instead of
 * falling back to a pause that never happened.
 */
sealed interface Transport {
    data object Idle : Transport
    data object Starting : Transport
    data object Buffering : Transport
    data object Playing : Transport
    data object Paused : Transport
    data class Failed(val message: String) : Transport
}

/** Whether audio exists or is on its way. The old `isPlaying` flag, now derivable. */
val Transport.isPlaying: Boolean
    get() = this == Transport.Playing || this == Transport.Buffering || this == Transport.Starting

/**
 * Writes [Transport] to the record and owns the one transition that is not an event: a start that
 * neither becomes audio nor throws.
 *
 * Every other phase comes from a player event. A start that never settles has no event to wait
 * for, so it gets a watchdog: after [START_WATCHDOG_MS] the phase is [Transport.Failed] and the
 * caller's timeout action runs (the seek bar releases its hold, the bar says why). Settling any
 * phase cancels the watchdog, so a slow network that did start in time is never blamed.
 */
internal class TransportState(
    private val store: UiStateStore,
    private val scope: CoroutineScope,
    private val watchdogMs: Long = START_WATCHDOG_MS
) {

    private var watchdog: Job? = null

    /** A start is in flight; nothing is audible yet. */
    fun starting(onTimeout: () -> Unit) {
        set(Transport.Starting)
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(watchdogMs)
            if (store.value.transport == Transport.Starting) {
                onTimeout()
                set(Transport.Failed(START_FAILED_MESSAGE))
            }
        }
    }

    fun playing() = settle(Transport.Playing)

    fun paused() = settle(Transport.Paused)

    fun idle() = settle(Transport.Idle)

    fun failed(message: String) = settle(Transport.Failed(message))

    /**
     * A buffering report is only meaningful while an item is being fetched to play. A paused item
     * that reports buffering is the player's memory of the generation before the pause.
     */
    fun buffering(active: Boolean) {
        val current = store.value.transport
        if (active) {
            if (current == Transport.Starting || current == Transport.Playing || current == Transport.Buffering) {
                settle(Transport.Buffering)
            }
        } else if (current == Transport.Buffering) {
            settle(Transport.Playing)
        }
    }

    /** Adopts what the live player is already doing after the screen was recreated. */
    fun restored(playing: Boolean) = settle(if (playing) Transport.Playing else Transport.Idle)

    /** A queue write that also states whether playback should be running; null leaves it alone. */
    fun intent(playing: Boolean?) {
        when (playing) {
            true -> settle(Transport.Playing)
            false -> settle(Transport.Idle)
            null -> Unit
        }
    }

    private fun settle(phase: Transport) {
        watchdog?.cancel()
        watchdog = null
        set(phase)
    }

    private fun set(phase: Transport) = store.update { it.copy(transport = phase) }

    companion object {
        /** How long a start may take before it is called a failure, not a slow network. */
        const val START_WATCHDOG_MS = 20_000L
        const val START_FAILED_MESSAGE = "Couldn't start playback"
    }
}
