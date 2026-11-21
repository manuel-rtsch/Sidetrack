package io.github.manuelrtsch.sidetrack.data

import android.content.Context
import io.github.manuelrtsch.sidetrack.ServiceCapabilities
import io.github.manuelrtsch.sidetrack.ServiceId
import io.github.manuelrtsch.sidetrack.auth.PlexAccountHttpClient
import io.github.manuelrtsch.sidetrack.auth.PlexAuth
import io.github.manuelrtsch.sidetrack.auth.PlexCredentialStore
import io.github.manuelrtsch.sidetrack.auth.plexClientIdentifier
import io.github.manuelrtsch.sidetrack.playback.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * A signed-in Plex account.
 *
 * Catalog and writer are the Plex adapter; playback runs the shared [Media3ServiceRuntime]
 * lifecycle over Media3/ExoPlayer, so the media session, notification and audio focus behave as
 * they do for the other providers.
 */
class PlexRuntime(
    context: Context,
    val auth: PlexAuth = defaultPlexAuth(context)
) : Media3ServiceRuntime(
    context = context,
    service = ServiceId.Plex,
    capabilities = ServiceCapabilities.Plex,
    auth = auth,
    stores = StoreNames.forService("plex")
) {
    @Volatile
    private var client: PlexApi? = null

    @Volatile
    private var clientToken: String? = null

    /**
     * The API client for the current token. Building one is cheap compared with the SDK clients
     * other providers use, but the token check keeps a signed-out session from making requests.
     */
    private fun api(): PlexApi? {
        val credentials = runCatching { auth.credentials() }.getOrNull() ?: return null
        client?.let { if (clientToken == credentials.token) return it }
        val created = runCatching { HttpPlexApi(credentials) }.getOrNull()
        client = created
        clientToken = credentials.token
        return created
    }

    override val catalog: PlexCatalog = PlexCatalog(::api)

    private val reportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // A stored session builds the client before the first row needs artwork.
        if (signedIn.value) runCatching { api() }
    }

    override fun attachPlaybackFeatures(controller: PlaybackController): PlaybackController {
        PlexPlaybackReporter(
            api = ::api,
            state = controller.engine.state,
            ended = controller.ended,
            currentItem = { controller.current },
            position = { controller.position },
            scope = reportScope
        ).start()
        return controller
    }

    /** A new token gets its own client. */
    override suspend fun onSignedIn() {
        client = null
        clientToken = null
    }

    override fun onSignedOut() {
        client = null
        clientToken = null
    }
}

/** Plex's identity and label, available without building a session. */
object PlexService {
    val id: ServiceId get() = ServiceId.Plex
    const val DISPLAY_NAME: String = "Plex"
}

/** Plex as a registered service; the picker and sign-in screen read these values. */
class PlexProvider(
    private val session: PlexRuntime
) : MusicServiceProvider {
    override val id: ServiceId = PlexService.id
    override val displayName: String = PlexService.DISPLAY_NAME
    override val capabilities: ServiceCapabilities = ServiceCapabilities.Plex

    /** Plex signs in with a PIN on plex.tv; Sidetrack never sees the account password. */
    override val signInKind: SignInKind = SignInKind.Browser

    override fun createAuth(): ServiceAuth = session.auth
}

private fun defaultPlexAuth(context: Context): PlexAuth {
    val clientIdentifier = plexClientIdentifier(context)
    return PlexAuth(
        store = PlexCredentialStore(context),
        clientIdentifier = clientIdentifier,
        client = PlexAccountHttpClient(clientIdentifier)
    )
}
