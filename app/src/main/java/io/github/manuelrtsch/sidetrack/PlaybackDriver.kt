package io.github.manuelrtsch.sidetrack

import android.os.SystemClock
import io.github.manuelrtsch.sidetrack.data.MediaCatalog
import io.github.manuelrtsch.sidetrack.data.QueueRepository
import io.github.manuelrtsch.sidetrack.data.ServiceException
import io.github.manuelrtsch.sidetrack.data.ServiceFailure
import io.github.manuelrtsch.sidetrack.data.ServiceRuntime
import io.github.manuelrtsch.sidetrack.data.consumeHaltStop
import io.github.manuelrtsch.sidetrack.data.halting
import io.github.manuelrtsch.sidetrack.data.notePlayRequest
import io.github.manuelrtsch.sidetrack.playback.PlaybackEvent
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * What plays next, and when the screen believes the player: the queue's lifecycle — starting an
 * item on the service, reacting to the player's events, ending a track (repeat, autoplay, end of
 * queue) and the transport commands that drive all of it.
 *
 * It owns no UI state: writes go through [effects] and position claims through [clock], which keeps
 * the seek/resume rules and the event `when` readable without scrolling past library paging.
 */
internal class PlaybackDriver(
    private val store: UiStateStore,
    private val clock: PlaybackClock,
    private val transport: TransportState,
    private val effects: PlaybackEffects,
    private val notifier: Notifier,
    private val queue: QueueRepository,
    private val session: ServiceRuntime?,
    private val catalog: MediaCatalog,
    private val mediaKeys: MediaKeys,
    private val ensureTrackSaved: (String) -> Unit,
    private val scope: CoroutineScope
) {

    private val events = PlaybackEvents(
        clock = clock,
        transport = transport,
        session = session,
        queue = queue,
        effects = effects,
        store = store,
        matchesCurrent = ::matchesCurrent,
        onEnded = ::onPlaybackEnded,
        onUnavailable = ::onPlaybackUnavailable
    )

    private val service: ServiceId get() = session?.service ?: ServiceId.Local

    private var autoplayJob: Job? = null

    /**
     * The queue a collection tap replaced, while the bar still offers it back. [playingId] is the
     * item that tap started, so a stale snapshot can never undo a newer choice.
     */
    private data class ReplacedQueue(
        val snapshot: QueueRepository.Snapshot,
        val positionMs: Long,
        val playingId: String
    )

    private var replacedQueue: ReplacedQueue? = null

    private fun playbackNotice(error: Exception): String = serviceMessage(error, session?.isOnline != false)

    /** [origin] is the collection page the tap came from, if any; see `QueueRepository.play`. */
    fun play(items: List<MediaItem>, index: Int, origin: MediaKey? = null) {
        if (index !in items.indices) return
        val item = items[index]
        if (!readyToPlay(item)) return
        if (store.value.current?.id == item.id) {
            if (!store.value.isPlaying) togglePlayPause()
            store.update { it.copy(notice = null) }
            return
        }
        // A collection tap is the only gesture that throws away a queue the user lined up, so keep
        // the outgoing one for a few seconds; an empty tail has nothing to lose.
        clearReplacedQueue()
        val before = queue.snapshot
        val lost = before.upcoming.isNotEmpty()
        if (lost) {
            replacedQueue = ReplacedQueue(before, clock.value, item.id)
            effects.showQueueReplaced { replacedQueue = null }
        }
        beginPlayback(queue.play(items, index, origin), item, notify = !lost)
    }

    private fun clearReplacedQueue() {
        if (replacedQueue == null) return
        replacedQueue = null
        effects.dismissQueueReplaced()
    }

    /** Withdraws the queue-replaced offer without putting anything back. */
    fun discardQueueReplaceOffer() = clearReplacedQueue()

    /**
     * Puts back the queue the last collection tap replaced and resumes the item that was playing.
     *
     * The album track stays in the history it earned, and the offer is withdrawn once something
     * else started, so a late restore cannot cut off a choice the user made on purpose.
     */
    fun undoQueueReplaced() {
        val replaced = replacedQueue ?: return
        if (store.value.current?.id != replaced.playingId) {
            clearReplacedQueue()
            return
        }
        clearReplacedQueue()
        val restored = queue.restore(replaced.snapshot)
        val previous = restored.current
        if (previous == null) {
            effects.applyQueue(restored, positionMs = 0L, playing = false)
            return
        }
        effects.applyQueue(restored, positionMs = replaced.positionMs, playing = true)
        startRemote(previous, replaced.positionMs, resuming = replaced.positionMs > 0L)
    }

    /**
     * The catalog decides this, before any transport is involved, so the refusal is logged here:
     * the announcement alone left no trace of which row the user tried to play.
     */
    fun readyToPlay(item: MediaItem): Boolean {
        if (!item.playable) {
            SidetrackDebug.log(
                "play refused: playable=false service=${item.service} id=${item.id} " +
                    "title=${item.title} creator=${item.creator} uri=${item.uri}"
            )
            notifier.say("Not available on this account")
            return false
        }
        return true
    }

    fun beginPlayback(snapshot: QueueRepository.Snapshot, item: MediaItem, notify: Boolean = true) {
        if (item is Track) ensureTrackSaved(item.id)
        val resume = effects.itemStartPosition(item)
        effects.applyQueue(snapshot, positionMs = resume, playing = true)
        store.update { it.copy(notice = null) }
        if (notify) effects.showNowPlayingNotice(item)
        startRemote(item, resume, resuming = resume > 0L)
    }

    fun togglePlayPause() {
        val s = store.value
        val item = s.current ?: return
        val play = !s.isPlaying
        // The keypad, an on-screen button and the notification action all land here, so this says
        // which way the transport was asked to go without claiming which of the three asked.
        SidetrackDebug.log("transport toggle: ${if (play) "play" else "pause"} from playing=${s.isPlaying}")
        // A paused seek has been delivered by now; let the next event realign the bar again.
        clock.clearSeek()
        effects.recordEpisodeProgress(force = true)
        clock.anchor(clock.value)
        if (play) transport.playing() else transport.paused()
        if (session == null || item.service == ServiceId.Local) return
        scope.launch {
            try {
                if (play && session.playback.current == null) {
                    // A restored queue outlives the native player: start the item from the offset it
                    // stopped at instead of resuming a source that no longer exists.
                    val resume = effects.savedPositionFor(item)
                    startRemote(item, resume, resuming = resume > 0L)
                } else if (play) {
                    if (!session.resume()) {
                        transport.paused()
                        return@launch
                    }
                } else {
                    session.pause()
                }
                session.playback.updateState(play, clock.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                transport.failed(playbackNotice(e))
                store.update { it.say(playbackNotice(e)) }
            }
        }
    }

    fun next() {
        clearReplacedQueue()
        val before = store.value.current
        val snapshot = queue.next(auto = false)
        if (snapshot.current === before) {
            // The repository has nothing to move to, which is the last item of the queue unless
            // repeat is on. Staying silent made a working key look broken; a press on a finished
            // queue is still a request for the next track.
            if (before != null) {
                SidetrackDebug.log("next refused: end of queue id=${before.id}")
                notifier.say("End of queue")
            }
            return
        }
        val next = snapshot.current
        val resume = next?.let(effects::itemStartPosition) ?: 0L
        effects.applyQueue(snapshot, positionMs = resume, playing = next?.playable == true)
        next?.let { startRemote(it, resume, resuming = resume > 0L) }
    }

    fun previous() {
        clearReplacedQueue()
        val s = store.value
        if (s.current == null) return
        // A podcast's Previous means the previous episode; rewinding belongs to the ±30 s controls,
        // so the restart-after-three-seconds rule does not apply here.
        if (s.current is Episode) {
            val snapshot = queue.previous(0L)
            if (snapshot.current !== s.current) {
                val previous = snapshot.current
                val resume = previous?.let(effects::itemStartPosition) ?: 0L
                effects.applyQueue(snapshot, positionMs = resume, playing = previous?.playable == true)
                previous?.let { startRemote(it, resume, resuming = resume > 0L) }
            } else {
                seekTo(0L)
            }
            return
        }
        if (clock.value > 3_000L) {
            clock.set(0L)
            if (s.current.service != ServiceId.Local) {
                session?.let { session ->
                    scope.launch { runCatching { session.seek(0L) } }
                }
            }
            return
        }
        val snapshot = queue.previous(clock.value)
        if (snapshot.current !== s.current) {
            val previous = snapshot.current
            val resume = previous?.let(effects::itemStartPosition) ?: 0L
            effects.applyQueue(snapshot, positionMs = resume, playing = previous?.playable == true)
            previous?.let { startRemote(it, resume, resuming = resume > 0L) }
        } else {
            clock.set(0L)
            if (s.current.service != ServiceId.Local) {
                session?.let { session ->
                    scope.launch { runCatching { session.seek(0L) } }
                }
            }
        }
    }

    fun seekTo(positionMs: Long) {
        val current = store.value.current ?: return
        val target = positionMs.coerceIn(0L, current.durationMs)
        val now = SystemClock.elapsedRealtime()
        SidetrackDebug.log("viewModel.seekTo target=$target current=${clock.value}")
        clock.beginSeek(target)
        effects.recordEpisodeProgress(force = true)
        if (session != null && current.service != ServiceId.Local) {
            scope.launch { runCatching { session.seek(target) } }
        }
    }

    fun jumpTo(index: Int) {
        clearReplacedQueue()
        val s = store.value
        val snapshot = queue.jumpTo(index)
        if (snapshot.current !== s.current) {
            val next = snapshot.current
            val resume = next?.let(effects::itemStartPosition) ?: 0L
            effects.applyQueue(snapshot, positionMs = resume, playing = next?.playable == true)
            next?.takeIf { it.playable }?.let(effects::showNowPlayingNotice)
            next?.let { startRemote(it, resume, resuming = resume > 0L) }
        }
    }

    fun matchesCurrent(uri: String?): Boolean {
        if (uri.isNullOrEmpty()) return true
        // HTTP services have no stable MediaItem.uri; their event URI is the resolved stream URL.
        val current = store.value.current?.uri ?: session?.playback?.currentSource?.uri
        return current?.equals(uri, ignoreCase = true) ?: true
    }

    fun onPlaybackEnded() {
        val snapshot = store.value
        val current = snapshot.current ?: return
        effects.markEpisodeFinished(current)
        // The sleep timer wins over repeat and autoplay: this is where the queue must stop.
        if (session?.playback?.consumeEndOfTrackTimer() == true) {
            // The native player is already at the end, so leaving it there makes the drain read the
            // finished track as a stalled connection.
            effects.stopAtQueueEnd()
            return
        }
        if (endOfEpisode(current)) return
        if (snapshot.repeat == RepeatMode.Track) {
            clock.set(0L)
            startRemote(current, 0L)
        } else {
            advance(auto = true, forceContextLoop = snapshot.repeat == RepeatMode.Context)
        }
    }

    /**
     * The service refused this item, so it is not playable at all: Spotify answers a regional or
     * removed track this way. Treat it like a finished track and move on, autoplay included.
     *
     * Retrying only lets the stall detector read the rejection as a dead connection and reload the
     * same item until playback gives up. Repeat-track deliberately does not apply.
     */
    fun onPlaybackUnavailable() {
        val skipped = store.value.current
        SidetrackDebug.log("unavailable: skipping on from ${skipped?.key?.encode()}")
        advance(auto = true, forceContextLoop = false)
        // Announced after the advance, which clears the notice on its way in, and only when the
        // queue really moved: the end-of-queue and autoplay messages must not be overwritten.
        if (store.value.current !== skipped) {
            store.update { it.say("Skipped — this track isn't available") }
        }
    }

    /**
     * Ends a finished episode. Episodes have no shuffle or repeat control, so a mode left over from
     * a song must not rewind the show or loop it: with nothing queued behind, the queue stops.
     */
    fun endOfEpisode(current: MediaItem): Boolean {
        if (current !is Episode) return false
        advance(auto = true, forceContextLoop = false)
        return true
    }

    fun tick() {
        val s = store.value
        val current = s.current ?: return
        if (!s.isPlaying) return
        val nextPosition = clock.value + 1_000L
        if (nextPosition < current.durationMs) {
            clock.set(nextPosition)
            effects.recordEpisodeProgress()
        } else if (!endOfEpisode(current)) {
            when (s.repeat) {
                RepeatMode.Track -> clock.set(0L)
                RepeatMode.Context -> advance(auto = true, forceContextLoop = true)
                RepeatMode.Off -> advance(auto = true, forceContextLoop = false)
            }
        }
    }

    fun advance(auto: Boolean, forceContextLoop: Boolean) {
        // Playback moved on by itself, so the replaced queue is no longer what the bar talks about.
        clearReplacedQueue()
        val s = store.value
        val current = s.current ?: return
        if (auto) effects.markEpisodeFinished(current)
        val hasNext = s.upcoming.isNotEmpty()
        if (!hasNext && forceContextLoop) {
            val snapshot = queue.next(auto = true)
            val next = snapshot.current
            val resume = next?.let(effects::itemStartPosition) ?: 0L
            effects.applyQueue(snapshot, positionMs = resume, playing = next?.playable == true)
            next?.let { startRemote(it, resume, gapless = auto && store.value.gapless, resuming = resume > 0L) }
            return
        }
        if (!hasNext && !auto) return
        if (!hasNext) {
            if (store.value.autoplay) autoplaySimilar(current) else effects.stopAtQueueEnd()
            return
        }
        val snapshot = queue.next(auto = true)
        if (snapshot.current !== current) {
            val next = snapshot.current
            val resume = next?.let(effects::itemStartPosition) ?: 0L
            effects.applyQueue(snapshot, positionMs = resume, playing = next?.playable == true)
            next?.let { startRemote(it, resume, gapless = auto && store.value.gapless, resuming = resume > 0L) }
        } else {
            clock.set(0L)
            transport.idle()
        }
    }

    /**
     * The queue ran out with Autoplay on: ask the catalog for the artist's top tracks, drop what the
     * queue and history already hold, and let the advance path start the first. The guard keeps a
     * slow request from hijacking a track the user started meanwhile.
     */
    fun autoplaySimilar(current: MediaItem) {
        if (autoplayJob?.isActive == true) return
        if (session?.isOnline == false) {
            effects.stopAtQueueEnd("No internet connection — autoplay stopped")
            return
        }
        val track = current as? Track
        val artistId = track?.artistId?.mediaIdOrNull()
        if (track == null || artistId == null) {
            effects.stopAtQueueEnd()
            return
        }
        autoplayJob = scope.launch {
            try {
                val artist = Artist(artistId, track.artist, 0, emptyList(), service = service)
                val page = catalog.topTracks(artist.key, 0, CATALOG_PAGE_SIZE)
                if (store.value.current?.id != track.id || !store.value.autoplay) return@launch
                val known = buildSet {
                    add(track.id)
                    addAll(store.value.history.map { it.id })
                    addAll(store.value.upcoming.map { it.id })
                }
                val similar = page.items.filter { it.id !in known && it.playable }
                if (similar.isEmpty()) {
                    effects.stopAtQueueEnd()
                    return@launch
                }
                val tail = if (store.value.shuffle) similar.shuffled() else similar
                effects.applyQueue(queue.addToQueue(tail), syncNowPlaying = false)
                advance(auto = true, forceContextLoop = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (store.value.current?.id == track.id) {
                    val reason = when {
                        session?.isOnline == false -> "No internet connection"
                        (e as? ServiceException)?.failure == ServiceFailure.Network -> "Couldn't reach the service"
                        else -> playbackNotice(e)
                    }
                    effects.stopAtQueueEnd("$reason — autoplay stopped")
                }
            }
        }
    }

    /**
     * The audio runs at [positionMs] once the start call returns, so the seek bar starts from that
     * offset instead of waiting for an event: a reconnecting player replays the previous
     * generation's state — typically 0 — and the bar would stick, then snap back.
     */
    fun releaseResumeHold(item: MediaItem, positionMs: Long) {
        clock.confirmStart(positionMs)
        if (store.value.current?.id == item.id) transport.playing()
    }

    /** Plays one item on its own, recording [origin] when a page's play button started it. */
    fun playItem(item: MediaItem, origin: MediaKey? = null) = play(listOf(item), 0, origin)

    /**
     * Plays one track picked from a list row without discarding the queue: hearing it now is not a
     * request to throw away the tail the user built with Add to queue, so only the current item is
     * replaced ([play] is the gesture that takes the collection instead).
     */
    fun playNow(item: MediaItem) {
        if (!readyToPlay(item)) return
        clearReplacedQueue()
        if (store.value.current?.id == item.id) {
            if (!store.value.isPlaying) togglePlayPause()
            store.update { it.copy(notice = null) }
            return
        }
        beginPlayback(queue.playNow(item), item)
    }

    /**
     * Plays one track from an album or playlist page without dropping the queue behind it: a row tap
     * there plays in collection context and throws away what was lined up. This is the same request
     * read the other way round — hear this track now, then pick the queue back up.
     */
    fun playFirst(item: MediaItem) {
        if (!readyToPlay(item)) return
        clearReplacedQueue()
        if (store.value.current?.id == item.id) {
            if (!store.value.isPlaying) togglePlayPause()
            store.update { it.copy(notice = null) }
            return
        }
        beginPlayback(queue.playFirst(item), item)
    }

    fun playEpisode(episode: Episode, origin: MediaKey? = null) = playItem(episode, origin)

    /**
     * Adds the pages behind a collection that is already playing: the page on screen starts the queue
     * immediately, so a long playlist does not spin before its first note and one failing page
     * cannot stop playback. [QueueRepository.extendContext] drops a load that lands too late.
     */
    fun extendCollection(page: List<MediaItem>, complete: List<MediaItem>) {
        val before = queue.snapshot
        val after = queue.extendContext(page, complete)
        if (after != before) effects.applyQueue(after, syncNowPlaying = false)
    }

    /** Podcast rewind. [seekTo] clamps to the item's duration and never goes below zero. */
    fun skipBack(milliseconds: Long = PODCAST_SKIP_MS) = seekTo(clock.value - milliseconds)

    /** Podcast fast-forward. [seekTo] clamps to the item's duration. */
    fun skipForward(milliseconds: Long = PODCAST_SKIP_MS) = seekTo(clock.value + milliseconds)

    /** Adopts the process-wide player and queue after the Sidetrack screen was recreated. */
    fun restore() {
        val session = session ?: return
        val playback = session.playback
        val snapshot = queue.snapshot
        val current = playback.current ?: snapshot.current
        if (current != null && snapshot.current?.id != current.id) {
            queue.restore(snapshot.copy(current = current))
        }
        val restored = queue.snapshot
        // A closed app leaves the item at the offset it reached; a live player owns its position.
        val resume = current?.let(effects::savedPositionFor) ?: 0L
        val position = if (playback.current != null) playback.position else resume
        clock.anchor(position)
        clock.set(position)
        store.update {
            it.copy(
                current = current,
                upcoming = restored.upcoming,
                history = restored.history,
                shuffle = restored.shuffle,
                repeat = restored.repeat,
                origin = restored.origin,
                transport = if (playback.isActive) Transport.Playing else Transport.Idle,
                recent = effects.recentItems(excluding = current)
            )
        }
    }

    /**
     * librespot is the source of truth while it plays: position events keep the bar honest through
     * buffering and seeks, and EndOfTrack advances the queue exactly once.
     */
    fun handlePlaybackEvent(event: PlaybackEvent) = events.handle(event)

    /** Starts a real service item; local test fixtures have no URI and keep the local simulation. */
    fun startRemote(
        item: MediaItem,
        positionMs: Long,
        gapless: Boolean = false,
        resuming: Boolean = false
    ) {
        val session = session ?: return
        if (item.service == ServiceId.Local) return
        SidetrackDebug.log("viewModel.startRemote key=${item.key.encode()} positionMs=$positionMs gapless=$gapless resuming=$resuming")
        // Nothing is audible until the play call or a player event says so. A start that does
        // neither must not leave the now-playing UI claiming to play forever.
        transport.starting {
            clock.endStart()
            store.update { it.copy(nowPlayingNotice = null).say(TransportState.START_FAILED_MESSAGE) }
        }
        clock.beginStart(positionMs, resumeFor = item.id.takeIf { resuming })
        session.playback.setNowPlaying(item, positionMs)
        scope.launch {
            try {
                session.play(item, positionMs, gapless)
                SidetrackDebug.log("viewModel.startRemote returned")
                if (resuming) releaseResumeHold(item, positionMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SidetrackDebug.log("viewModel.startRemote failed", e)
                clock.endStart()
                transport.failed(playbackNotice(e))
                store.update { it.copy(nowPlayingNotice = null).say(playbackNotice(e)) }
            }
        }
    }
}
