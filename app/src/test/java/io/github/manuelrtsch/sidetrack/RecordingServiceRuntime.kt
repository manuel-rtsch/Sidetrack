package io.github.manuelrtsch.sidetrack

import android.content.Context
import io.github.manuelrtsch.sidetrack.data.AuthInput
import io.github.manuelrtsch.sidetrack.data.MusicServiceProvider
import io.github.manuelrtsch.sidetrack.data.ServiceAccount
import io.github.manuelrtsch.sidetrack.data.ServiceAuth
import io.github.manuelrtsch.sidetrack.data.ServiceChoice
import io.github.manuelrtsch.sidetrack.data.SignInKind
import io.github.manuelrtsch.sidetrack.playback.Media3PlaybackEngine
import io.github.manuelrtsch.sidetrack.playback.PlaybackController
import io.github.manuelrtsch.sidetrack.test.SilentMedia3Player
import io.github.manuelrtsch.sidetrack.test.SilentServiceRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A provider-neutral runtime that records the transport calls the app makes, so the app-level
 * owners (`AppSession`, the playback service) can be tested without a real provider or player.
 *
 * The state holders are the real ones: only the service's network side is stubbed. Everything a
 * silent runtime answers comes from [SilentServiceRuntime]; this adds the recording.
 */
class RecordingServiceRuntime(
    override val service: ServiceId = ServiceId.Navidrome,
    val calls: MutableList<String> = mutableListOf(),
    val signedInFlow: MutableStateFlow<Boolean> = MutableStateFlow(false),
    /** Pass a context when the test exercises the notification, which needs a media session. */
    playbackContext: Context? = null
) : SilentServiceRuntime(service) {
    override val playback: PlaybackController = PlaybackController(
        Media3PlaybackEngine { SilentMedia3Player() },
        scope = CoroutineScope(Dispatchers.Unconfined),
        context = playbackContext
    )
    override val signedIn: StateFlow<Boolean> = signedInFlow

    override fun pause() {
        calls += "pause"
    }

    override fun togglePlayback() {
        calls += "toggle"
    }

    override fun stopPlayback() {
        calls += "stop"
    }

    override fun haltPlayback() {
        calls += "halt"
    }
}

/** A provider whose sign-in never runs; the app-level owners do not touch it. */
class RecordingProvider(private val choice: ServiceChoice) : MusicServiceProvider {
    override val id: ServiceId = choice.id
    override val displayName: String = choice.displayName
    override val capabilities: ServiceCapabilities = ServiceCapabilities.Subsonic
    override val signInKind: SignInKind = SignInKind.Browser

    override fun createAuth(): ServiceAuth = object : ServiceAuth {
        override suspend fun signIn(input: AuthInput): ServiceAccount = ServiceAccount(choice.id, userId = "user", displayName = "User")
        override suspend fun restore(): ServiceAccount? = null
        override suspend fun signOut() = Unit
    }
}
