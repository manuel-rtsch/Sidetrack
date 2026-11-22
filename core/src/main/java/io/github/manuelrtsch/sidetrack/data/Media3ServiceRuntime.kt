package io.github.manuelrtsch.sidetrack.data

import android.content.Context
import hrln.interactive.sidekit.sdk.media.RemoteArtwork
import io.github.manuelrtsch.sidetrack.AudioQuality
import io.github.manuelrtsch.sidetrack.MediaItem
import io.github.manuelrtsch.sidetrack.ServiceCapabilities
import io.github.manuelrtsch.sidetrack.ServiceId
import io.github.manuelrtsch.sidetrack.auth.CredentialPersistenceException
import io.github.manuelrtsch.sidetrack.playback.ExoPlayerMedia3Player
import io.github.manuelrtsch.sidetrack.playback.Media3PlaybackEngine
import io.github.manuelrtsch.sidetrack.playback.PlaybackController
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The lifecycle every Media3-backed service shares: settings, queue, history, resume point, the
 * [PlaybackController] over Media3/ExoPlayer, sign-in bookkeeping, transport verbs and the cache
 * surface. A provider supplies its identity, [catalog] and [StoreNames] and nothing more, so the media
 * session, notification, audio focus and resume rules cannot drift between services.
 *
 * What genuinely differs stays in the subclass: client construction per token, a server-side resume
 * seed, playback reporting and artwork headers.
 */
abstract class Media3ServiceRuntime(
    context: Context,
    override val service: ServiceId,
    override val capabilities: ServiceCapabilities,
    private val auth: RuntimeAuth,
    private val stores: StoreNames
) : ServiceRuntime,
    CacheRuntime,
    QualitySettingsRuntime {

    protected val appContext: Context = context.applicationContext

    private val _signedIn = MutableStateFlow(runCatching { auth.hasCredentials() }.getOrDefault(false))
    override val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    override val network: NetworkMonitor = NetworkMonitor(appContext)
    override val isOnline: Boolean get() = network.isOnline

    private val settingsStore = PlaybackSettingsStore(appContext, stores.settings)

    /**
     * Mutable because a catalog reads the current preferences when it builds a request; the rest
     * of the app only sees [settings].
     */
    protected val settingsState = MutableStateFlow(
        runCatching { settingsStore.read() }.getOrDefault(PlaybackSettings())
    )
    override val settings: StateFlow<PlaybackSettings> = settingsState.asStateFlow()

    private val queueStore = FileQueueStore(File(appContext.filesDir, stores.queue))
    private val historyStore = FileHistoryStore(File(appContext.filesDir, stores.history))

    /** Kept open to the subclass: Jellyfin's server seed writes the same resume point. */
    protected val positionStore = FilePositionStore(File(appContext.filesDir, stores.resume))

    override val queue = QueueRepository(
        load = { queueStore.read() },
        save = { queueStore.write(it) }
    )
    override val history = HistoryRepository(
        load = { historyStore.read() },
        save = { historyStore.write(it) }
    )
    override val episodeProgress = EpisodeProgressRepository()
    override val episodeMeta = EpisodeMetaRepository()

    @Volatile
    private var currentResumePoint: ResumePoint? = runCatching { positionStore.read() }.getOrNull()

    override val resumePoint: ResumePoint? get() = currentResumePoint

    protected val playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Hooks a provider onto the controller exactly once, on first playback. A reporter is attached
     * here rather than in the constructor so the lazy field is fully initialised first.
     */
    protected open fun attachPlaybackFeatures(controller: PlaybackController): PlaybackController = controller

    override val playback: PlaybackController by lazy {
        attachPlaybackFeatures(
            PlaybackController(
                engine = Media3PlaybackEngine { ExoPlayerMedia3Player(appContext) },
                scope = playbackScope,
                context = appContext
            )
        )
    }

    /** The catalog is the one thing every provider must shape itself. */
    abstract override val catalog: MediaCatalog

    /** A provider that can write to its library exposes it through the catalog. */
    override val writer: MediaLibraryWriter? get() = catalog as? MediaLibraryWriter

    override suspend fun signIn(input: AuthInput) {
        try {
            auth.signIn(input)
        } catch (e: CredentialPersistenceException) {
            // The server accepted the sign-in and the in-memory session works; only the next launch
            // asks again. Provider hooks are skipped: a seed or a client rebuild is not worth doing
            // before the caller even reports the failure.
            _signedIn.value = true
            throw e
        }
        _signedIn.value = true
        onSignedIn()
    }

    override fun signOut() {
        rememberPosition(clear = true)
        queue.stop()
        history.clear()
        playbackScope.launch { runCatching { playback.stop() } }
        auth.clear()
        onSignedOut()
        _signedIn.value = false
    }

    /**
     * A new session means any client keyed by the old token is stale. Providers override this to
     * drop that client; Jellyfin also seeds the resume point from the account here.
     */
    protected open suspend fun onSignedIn() = Unit

    /** The session is gone, so per-token clients must forget it too. */
    protected open fun onSignedOut() = Unit

    override suspend fun play(item: MediaItem, positionMs: Long, gapless: Boolean) {
        val source = catalog.resolvePlaybackSource(item)
        playback.start(item.key, source.uri, positionMs, headers = source.headers)
    }

    override suspend fun resume(): Boolean {
        val current = playback.current
        if (current != null && playback.engine.state.value.source == null) {
            val source = catalog.resolvePlaybackSource(current)
            playback.start(current.key, source.uri, playback.position, headers = source.headers)
            return true
        }
        return playback.resume()
    }

    override suspend fun seek(positionMs: Long) = playback.seek(positionMs)

    override fun pause() {
        rememberPosition()
        playback.pause()
    }

    override fun togglePlayback() {
        if (playback.isActive) pause() else playbackScope.launch { runCatching { resume() } }
    }

    override fun stopPlayback() {
        rememberPosition(clear = true)
        queue.stop()
        playbackScope.launch { runCatching { playback.stop() } }
    }

    override fun haltPlayback() {
        rememberPosition()
        playbackScope.launch { runCatching { playback.stop(keepQueue = true) } }
    }

    override fun setAutoplay(enabled: Boolean) = updateSettings { it.copy(autoplay = enabled) }

    override fun setAudioQuality(quality: AudioQuality) = updateSettings { it.copy(audioQuality = quality) }

    /**
     * A service without a loudness parameter (Jellyfin, by capability) still keeps the preference,
     * so the settings row behaves the same everywhere once the service can use it.
     */
    override fun setNormalization(enabled: Boolean) = updateSettings { it.copy(normalization = enabled) }

    /**
     * Gapless is not surfaced until the Media3 engine applies per-track seamless handoff; the
     * preference is persisted so it is ready when that lands.
     */
    override fun setGapless(enabled: Boolean) = updateSettings { it.copy(gapless = enabled) }

    private fun updateSettings(change: (PlaybackSettings) -> PlaybackSettings) {
        settingsState.update(change)
        settingsStore.write(settingsState.value)
    }

    override fun clearCaches() {
        RemoteArtwork.clear(appContext)
    }

    override suspend fun cacheSizeBytes(): Long = withContext(Dispatchers.IO) {
        CacheDirectory.sizeBytes(CacheDirectory.artwork(appContext))
    }

    override suspend fun clearAudioCache() = withContext(Dispatchers.IO) { clearCaches() }

    /** Saves the current position, or forgets it when [clear] is set, and mirrors it in state. */
    protected fun rememberPosition(clear: Boolean = false) {
        recordResumePoint(
            if (clear) {
                null
            } else {
                playback.current?.let { ResumePoint(it.key, playback.position.coerceAtLeast(0L)) }
            }
        )
    }

    /** Stores a resume point the provider learned elsewhere, such as Jellyfin's server seed. */
    protected fun recordResumePoint(point: ResumePoint?) {
        positionStore.write(point)
        currentResumePoint = point
    }
}
