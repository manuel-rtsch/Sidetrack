package io.github.manuelrtsch.sidetrack.test

import io.github.manuelrtsch.sidetrack.MediaItem
import io.github.manuelrtsch.sidetrack.ServiceCapabilities
import io.github.manuelrtsch.sidetrack.ServiceId
import io.github.manuelrtsch.sidetrack.data.AuthInput
import io.github.manuelrtsch.sidetrack.data.EpisodeMetaRepository
import io.github.manuelrtsch.sidetrack.data.EpisodeProgressRepository
import io.github.manuelrtsch.sidetrack.data.HistoryRepository
import io.github.manuelrtsch.sidetrack.data.MediaCatalog
import io.github.manuelrtsch.sidetrack.data.MediaLibraryWriter
import io.github.manuelrtsch.sidetrack.data.NetworkMonitor
import io.github.manuelrtsch.sidetrack.data.PlaybackSettings
import io.github.manuelrtsch.sidetrack.data.QueueRepository
import io.github.manuelrtsch.sidetrack.data.ResumePoint
import io.github.manuelrtsch.sidetrack.data.ServiceRuntime
import io.github.manuelrtsch.sidetrack.playback.Media3PlaybackEngine
import io.github.manuelrtsch.sidetrack.playback.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A runtime with no service behind it: signed out, online, no cache, no native player, and every
 * transport call accepted without doing anything.
 *
 * The state holders are the production ones, because the tests that use this range over them: the
 * queue, history, settings and episode stores are real, and only the service's network side is
 * missing. A test extends it and overrides what it drives — [RecordingServiceRuntime] records the
 * transport calls, an extensions test adds one of the optional runtime interfaces.
 */
open class SilentServiceRuntime(
    override val service: ServiceId = ServiceId.Navidrome,
    override val capabilities: ServiceCapabilities = ServiceCapabilities.Subsonic
) : ServiceRuntime {
    override val catalog: MediaCatalog = SilentMediaCatalog(capabilities)
    override val writer: MediaLibraryWriter? = null
    override val queue = QueueRepository()
    override val history = HistoryRepository()
    override val settings: StateFlow<PlaybackSettings> = MutableStateFlow(PlaybackSettings())
    override val playback: PlaybackController = PlaybackController(
        Media3PlaybackEngine { SilentMedia3Player() },
        scope = CoroutineScope(Dispatchers.Unconfined)
    )
    override val signedIn: StateFlow<Boolean> = MutableStateFlow(false)
    override val network: NetworkMonitor? = null
    override val isOnline: Boolean = true
    override val resumePoint: ResumePoint? = null
    override val episodeProgress = EpisodeProgressRepository()
    override val episodeMeta = EpisodeMetaRepository()

    override suspend fun signIn(input: AuthInput) = Unit

    override fun signOut() = Unit

    override suspend fun play(item: MediaItem, positionMs: Long, gapless: Boolean) = Unit

    override suspend fun resume(): Boolean = false

    override suspend fun seek(positionMs: Long) = Unit

    override fun pause() = Unit

    override fun togglePlayback() = Unit

    override fun stopPlayback() = Unit

    override fun haltPlayback() = Unit

    override fun setAutoplay(enabled: Boolean) = Unit

    override fun clearCaches() = Unit
}
