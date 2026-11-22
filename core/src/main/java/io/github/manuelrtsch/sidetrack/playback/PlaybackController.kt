package io.github.manuelrtsch.sidetrack.playback

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.session.MediaSession
import android.os.SystemClock
import hrln.interactive.sidekit.sdk.media.RemoteArtwork
import io.github.manuelrtsch.sidetrack.MediaItem
import io.github.manuelrtsch.sidetrack.MediaKey
import io.github.manuelrtsch.sidetrack.SidetrackDebug
import io.github.manuelrtsch.sidetrack.data.ArtworkAccess
import io.github.manuelrtsch.sidetrack.mediaTextOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-scoped playback owner: the media session, the foreground notification, audio focus and
 * the sleep timer outlive the activity and its screens.
 *
 * The pipeline behind it is replaceable ([PlaybackEngine]), and the notification is app-owned:
 * rather than depending on a concrete service, the controller asks the app-installed
 * [PlaybackNotificationHooks] to refresh or start it. Overridable per instance for tests.
 *
 * Main-thread only, like [MediaSessionOwner]; long-running engine work runs on [scope].
 */
class PlaybackController(
    val engine: PlaybackEngine,
    private val scope: CoroutineScope,
    private val registry: MediaSessionRegistry = MediaSessions,
    private val context: Context? = null,
    /** Monotonic clock seam: the sleep timer's deadline math is the only consumer. */
    private val elapsedTime: () -> Long = { SystemClock.elapsedRealtime() },
    /** Rebuilds the notification for a state-only change, such as a sleep-timer tick. */
    private val notifyRefresh: (Context, PlaybackController) -> Unit = { c, p -> PlaybackNotificationHooks.refresh(c, p) },
    /** Starts or brings the notification in line with the player. */
    private val notifySync: (Context, PlaybackController) -> Unit = { c, p -> PlaybackNotificationHooks.sync(c, p) }
) : MediaSessionOwner {
    override val sessionId = "sidetrack"
    override val notificationChannelId = PlaybackNotificationHooks.CHANNEL_ID
    override val notificationChannelName = PlaybackNotificationHooks.CHANNEL_NAME
    override val notificationId = PlaybackNotificationHooks.NOTIFICATION_ID

    @Volatile
    private var playing = false

    @Volatile
    private var positionMs = 0L

    @Volatile
    var current: MediaItem? = null
        private set

    /**
     * The resolved source for [current]. HTTP services keep their stream URL and auth headers
     * here; a service's source is just its URI. [reconfigure] reloads this, not the item's URI.
     */
    @Volatile
    var currentSource: PlaybackSource? = null
        private set

    val position: Long get() = positionMs

    private val _notice = MutableStateFlow<String?>(null)

    /**
     * Transient message the UI should surface about playback upkeep ("Reconnecting…" after a
     * stall). Latest wins; there is nothing to queue.
     */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _buffering = MutableStateFlow(false)

    /** True while the engine has no audio for the current position. */
    val buffering: StateFlow<Boolean> = _buffering.asStateFlow()

    private val sleepTimers = SleepTimerRunner(
        scope = scope,
        playing = { playing },
        elapsedTime = elapsedTime,
        onExpire = { pause() }
    )

    /**
     * The sleep timer lives on the process-wide player, not the ViewModel, so backing out of
     * Sidetrack cannot silently cancel it.
     */
    val sleepTimer: StateFlow<SleepTimerState> = sleepTimers.state

    @Volatile
    var notificationArt: Bitmap? = null
        private set

    override val isActive: Boolean get() = playing

    /**
     * Queue ops live in the ViewModel so its screen state stays in sync; when it is gone,
     * [fallbackNext]/[fallbackPrevious] keep the notification and lock-screen transport working by
     * advancing the process-wide queue.
     *
     * librespot 0.8 has no shuffle/repeat setters: the queue repository owns those.
     */
    var onNext: (() -> Unit)? = null
    var onPrevious: (() -> Unit)? = null
    var onSeek: ((Long) -> Unit)? = null

    /**
     * A play command arrived with nothing loaded, so there is no source to resume. The owner has
     * to start the current item instead; the player cannot invent one.
     */
    var onResumeWithoutSource: (() -> Unit)? = null
    var fallbackNext: (() -> Unit)? = null
    var fallbackPrevious: (() -> Unit)? = null

    /** [onResumeWithoutSource] for a process with no ViewModel attached. */
    var fallbackResume: (() -> Unit)? = null

    /**
     * Recovery for a pipeline that went silent, installed by the session that owns the native
     * player: only the gateway owner can reconnect a dead librespot session.
     */
    var onStalled: ((Long) -> Unit)? = null

    /** Lets the process owner clear queue state whenever playback is stopped from any control. */
    var onStopped: (() -> Unit)? = null

    private var artJob: Job? = null

    private val sessionBinder = PlaybackSessionBinder(
        context,
        onPlay = { scope.launch { runCatching { resume() } } },
        onPause = { pause() },
        onNext = { requestNext() },
        onPrevious = { requestPrevious() },
        onSeekTo = { requestSeek(it) },
        onStop = { scope.launch { runCatching { stop() } } }
    )

    private val noisy = NoisyAudioGuard(context) { onAudioBecomingNoisy() }

    /**
     * The last play/pause intent: librespot events arrive out of order after rapid taps, and one
     * that belongs to a superseded command must be ignored.
     */
    @Volatile
    private var lastCommandWasPause = false

    @Volatile
    private var lastCommandAt = 0L

    /** Headphones unplugged: pause rather than play out loud. */
    fun onAudioBecomingNoisy() = pause()

    private val focus = context?.let {
        AudioFocus(
            it,
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
            onPause = { pauseInternal(abandonFocus = false) },
            onResume = { scope.launch { runCatching { resume() } } }
        )
    }

    val sessionToken: MediaSession.Token? get() = sessionBinder.token

    init {
        engine.onFailure = ::onAudioStalled
        scope.launch {
            engine.state.collect { state -> _buffering.value = state.isBuffering }
        }
        // A tick only rebuilds the notification; the refresh path deliberately does not publish().
        if (context != null) {
            scope.launch {
                sleepTimer.collect { notifyRefresh(context, this@PlaybackController) }
            }
        }
    }

    /** Registers once so starting local Music can pause this owner even before the first track. */
    fun register() = registry.register(this)

    fun emitNotice(text: String?) {
        _notice.value = text
    }

    /**
     * Called by the engine on its drain thread when no PCM arrived for the stall budget; hops to
     * the main thread, which owns the player's state and the media session.
     */
    internal fun onAudioStalled(error: Throwable) {
        SidetrackDebug.log("audio stalled", error)
        scope.launch { handleAudioStalled() }
    }

    /**
     * A silent drain looks like playback in the media session, so the session is marked paused and
     * handed back for a reconnect; otherwise the user sees a running player and hears nothing.
     */
    private fun handleAudioStalled() {
        if (!playing) return
        playing = false
        publish()
        emitNotice("Reconnecting…")
        val recover = onStalled
        if (recover != null) {
            recover(positionMs)
        } else {
            scope.launch { runCatching { engine.stop() } }
        }
    }

    fun setSleepTimer(minutes: Int) = sleepTimers.set(minutes)

    fun setSleepTimerEndOfTrack() = sleepTimers.setEndOfTrack()

    fun clearSleepTimer() = sleepTimers.clear()

    /**
     * Called by the ViewModel when librespot reports the end of a track. Returns true once when
     * the end-of-track mode consumed the event, so normal repeat/advance must not run.
     */
    fun consumeEndOfTrackTimer(): Boolean = sleepTimers.consumeEndOfTrack()

    fun setNowPlaying(
        item: MediaItem?,
        position: Long = 0L,
        source: PlaybackSource? = null
    ) {
        if (item != null && item.id != current?.id) sleepTimers.clearFinished()
        current = item
        currentSource = source ?: item?.uri?.let { PlaybackSource(item.key.encode(), it) }
        positionMs = position
        engine.updatePosition(position)
        artJob?.cancel()
        notificationArt = null
        sessionBinder.updateMetadata(current, notificationArt)
        publish()
        val artUrl = item?.artUrl.mediaTextOrNull()
        if (artUrl != null && context != null) {
            artJob = scope.launch {
                val bitmap = try {
                    RemoteArtwork.load(context, artUrl, ART_PX, ART_PX, ArtworkAccess.current())
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (current?.id == item?.id) {
                    notificationArt = bitmap
                    sessionBinder.clearMetadataThrottle()
                    sessionBinder.updateMetadata(current, notificationArt)
                    publish()
                }
            }
        }
    }

    /** Mirrors native player events into the media session and notification. */
    fun updateState(isPlaying: Boolean, position: Long) {
        playing = isPlaying
        if (isPlaying) sleepTimers.onPlaybackStarted() else sleepTimers.hold()
        positionMs = position
        engine.updateState(isPlaying, position)
        publish()
    }

    /** Keeps the playback owner's position in step with the UI clock without publishing. */
    fun updatePosition(position: Long) {
        positionMs = position
        engine.updatePosition(position)
    }

    /**
     * Whether a native playing/paused event still matches the user's latest intent. After the
     * command window, events from other clients are accepted again.
     */
    fun shouldApplyEvent(isPlaying: Boolean): Boolean {
        val age = SystemClock.elapsedRealtime() - lastCommandAt
        if (age > COMMAND_EVENT_WINDOW_MS) return true
        return if (isPlaying) !lastCommandWasPause else lastCommandWasPause
    }

    /**
     * [keepAudioSink] is set for a gapless automatic transition: librespot has already pre-buffered
     * the track, so tearing down the AudioTrack here would create the gap the setting removes.
     */
    suspend fun start(
        key: MediaKey,
        uri: String,
        positionMs: Long,
        keepAudioSink: Boolean = false,
        headers: Map<String, String> = emptyMap()
    ) {
        SidetrackDebug.log("start key=${key.encode()} uri=$uri positionMs=$positionMs keepAudioSink=$keepAudioSink")
        val source = PlaybackSource(id = key.encode(), uri = uri, headers = headers)
        currentSource = source
        lastCommandWasPause = false
        lastCommandAt = SystemClock.elapsedRealtime()
        register()
        if (focus?.request() == false) {
            SidetrackDebug.log("start aborted: audio focus denied")
            return
        }
        registry.activate(this)
        playing = true
        this.positionMs = positionMs
        publish()
        try {
            engine.start(source, positionMs, keepAudioSink)
        } catch (t: Throwable) {
            SidetrackDebug.log("start failed", t)
            playing = false
            focus?.abandon()
            registry.deactivate(this)
            publish()
            throw t
        }
        sleepTimers.onPlaybackStarted()
    }

    suspend fun resume(): Boolean {
        SidetrackDebug.log("resume")
        if (current == null) {
            // A cold process restores the queue but not the native player, so a transport command
            // would resume nothing and deliver silence. Starting the item belongs to the queue
            // owner; this only reports whether anyone could do it.
            val start = onResumeWithoutSource ?: fallbackResume
            SidetrackDebug.log("resume without source restarted=${start != null}")
            start?.invoke()
            return start != null
        }
        lastCommandWasPause = false
        lastCommandAt = SystemClock.elapsedRealtime()
        register()
        if (focus?.request() == false) {
            SidetrackDebug.log("resume aborted: audio focus denied")
            return false
        }
        registry.activate(this)
        playing = true
        publish()
        val started = engine.resume(positionMs)
        if (started) sleepTimers.onPlaybackStarted()
        if (!started) {
            // An engine that cannot deliver audio has not earned the session or the focus request:
            // leaving the registry on a silent owner contradicts what [MediaSessionRegistry.active]
            // means, and holding focus keeps the app that just paused for us paused.
            playing = false
            focus?.abandon()
            registry.deactivate(this)
            publish()
            return false
        }
        return true
    }

    fun togglePlayPause() {
        if (playing) pause() else scope.launch { runCatching { resume() } }
    }

    override fun pause() = pauseInternal(abandonFocus = true)

    /**
     * A transient focus loss keeps the request held so [AudioFocus] can resume us; an explicit
     * pause gives it back.
     */
    private fun pauseInternal(abandonFocus: Boolean) {
        SidetrackDebug.log("pauseInternal abandonFocus=$abandonFocus")
        // The UI clock owns the position: the decoder's `paused` event is ahead by the PCM buffer.
        playing = false
        sleepTimers.hold()
        _buffering.value = false
        lastCommandWasPause = true
        lastCommandAt = SystemClock.elapsedRealtime()
        if (abandonFocus) focus?.abandon()
        registry.deactivate(this)
        publish()
        engine.invalidateCommands()
        scope.launch { runCatching { engine.pause() } }
    }

    suspend fun seek(positionMs: Long) {
        this.positionMs = positionMs
        publish()
        engine.seek(positionMs)
    }

    fun requestSeek(positionMs: Long) {
        val callback = onSeek
        if (callback != null) callback(positionMs) else scope.launch { runCatching { seek(positionMs) } }
    }

    fun requestNext() = (onNext ?: fallbackNext)?.invoke()

    fun requestPrevious() = (onPrevious ?: fallbackPrevious)?.invoke()

    /** Rebuilds the player after an audio-setting change, reloading the item where it stood. */
    suspend fun reconfigure() {
        val item = current
        val source = currentSource
            ?: item?.uri?.let { PlaybackSource(id = item.key.encode(), uri = it) }
        val position = positionMs
        val wasPlaying = playing
        SidetrackDebug.log("reconfigure uri=${source?.uri} positionMs=$position wasPlaying=$wasPlaying")
        val rebuilt = engine.reconfigure(source, position, wasPlaying)
        if (!rebuilt) return
        playing = wasPlaying
        publish()
    }

    /**
     * Ends playback. [keepQueue] leaves the queue, history and selected track alone: a close must
     * silence the audio, but the next launch should still find what was playing.
     */
    suspend fun stop(keepQueue: Boolean = false) {
        clearSleepTimer()
        playing = false
        _buffering.value = false
        lastCommandWasPause = true
        lastCommandAt = SystemClock.elapsedRealtime()
        focus?.abandon()
        registry.deactivate(this)
        engine.invalidateCommands()
        engine.stop()
        sessionBinder.markStopped()
        current = null
        currentSource = null
        notificationArt = null
        artJob?.cancel()
        if (!keepQueue) onStopped?.invoke()
        publish()
    }

    suspend fun disconnect() {
        stop()
        engine.disconnect()
        sessionBinder.release()
    }

    /** One engine event, or null when nothing has happened since the last poll. */
    suspend fun pollEvent(): PlaybackEvent? = engine.pollEvent()

    /**
     * End-of-track notifications that do not consume [pollEvent], so a provider can report a
     * finished item without competing with the ViewModel for the event queue.
     */
    val ended: Flow<String?> get() = engine.ended

    private fun publish() {
        SidetrackDebug.log { "publish playing=$playing positionMs=$positionMs current=${current?.uri}" }
        sessionBinder.publish(current, playing, positionMs, notificationArt)
        noisy.update(playing)
        context?.let { notifySync(it, this) }
    }

    private companion object {
        const val ART_PX = 256

        /** How long after a command a contradicting native event is treated as stale. */
        const val COMMAND_EVENT_WINDOW_MS = 2_000L
    }
}
