package io.github.manuelrtsch.sidetrack.data

import android.content.Context
import io.github.manuelrtsch.sidetrack.MediaKey
import io.github.manuelrtsch.sidetrack.MediaType
import io.github.manuelrtsch.sidetrack.ServiceCapabilities
import io.github.manuelrtsch.sidetrack.ServiceId
import io.github.manuelrtsch.sidetrack.auth.JellyfinAuth
import io.github.manuelrtsch.sidetrack.auth.JellyfinCredentialStore
import io.github.manuelrtsch.sidetrack.auth.SdkJellyfinAuthenticator
import io.github.manuelrtsch.sidetrack.playback.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * A signed-in Jellyfin account.
 *
 * Catalog, auth and writer are the SDK adapter; playback runs the shared [Media3ServiceRuntime]
 * lifecycle over Media3/ExoPlayer, so the media session, notification and audio focus behave
 * exactly as they do for the Subsonic family and Plex.
 *
 * One [SdkJellyfinApi] is kept per access token. The SDK client is expensive to build and carries
 * the device identity the server keys a session on, so it must not be recreated per request.
 */
class JellyfinRuntime(
    context: Context,
    val auth: JellyfinAuth = JellyfinAuth(
        JellyfinCredentialStore(context),
        SdkJellyfinAuthenticator(context)
    )
) : Media3ServiceRuntime(
    context = context,
    service = ServiceId.Jellyfin,
    capabilities = ServiceCapabilities.Jellyfin,
    auth = auth,
    stores = StoreNames.forService("jellyfin")
) {
    @Volatile
    private var client: JellyfinApi? = null

    @Volatile
    private var clientToken: String? = null

    /**
     * The SDK client for the current token, or null before sign-in. Reading the credentials is
     * cheap (cached in [JellyfinAuth]); building the client is not, hence the token check.
     */
    private fun api(): JellyfinApi? {
        val credentials = runCatching { auth.credentials() }.getOrNull() ?: return null
        client?.let { if (clientToken == credentials.accessToken) return it }
        val created = runCatching { SdkJellyfinApi(appContext, credentials) }
            .getOrNull()
        client = created
        clientToken = credentials.accessToken
        return created
    }

    override val catalog: JellyfinCatalog = JellyfinCatalog(
        api = ::api,
        settings = { settingsState.value }
    )

    private val reportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // A stored session installs the artwork header before the first row is drawn.
        if (signedIn.value) runCatching { auth.installArtworkHeaders() }
    }

    override fun attachPlaybackFeatures(controller: PlaybackController): PlaybackController {
        JellyfinPlaybackReporter(
            api = ::api,
            playbackInfo = catalog::playbackInfo,
            state = controller.engine.state,
            ended = controller.ended,
            currentItem = { controller.current },
            position = { controller.position },
            scope = reportScope
        ).start()
        return controller
    }

    /** A new token gets its own SDK client, and the account's resume list seeds the position. */
    override suspend fun onSignedIn() {
        client = null
        clientToken = null
        seedFromServer()
    }

    override fun onSignedOut() {
        client = null
        clientToken = null
    }

    /**
     * Tells the server this client exists (so its session list is honest) and seeds the resume
     * point from the account's own resume list (D13). Both are best-effort: a server that is
     * briefly slow must not turn a successful sign-in into a failure.
     */
    private suspend fun seedFromServer() {
        val client = runCatching { api() }.getOrNull() ?: return
        runCatching { client.announceCapabilities() }
        val resumed = runCatching { client.resumeItems(1) }.getOrNull()?.items?.firstOrNull() ?: return
        val ticks = resumed.playbackPositionTicks ?: 0L
        if (ticks <= 0L) return
        val type = resumed.kind.toMediaType() ?: return
        recordResumePoint(ResumePoint(MediaKey(ServiceId.Jellyfin, type, resumed.id), ticks.ticksToMs()))
    }
}

/** Jellyfin's identity and label, available without building a session or a runtime. */
object JellyfinService {
    val id: ServiceId get() = ServiceId.Jellyfin
    const val DISPLAY_NAME: String = "Jellyfin"
}

/** Jellyfin as a registered service; the picker and sign-in screen read these values. */
class JellyfinProvider(
    private val session: JellyfinRuntime
) : MusicServiceProvider {
    override val id: ServiceId = JellyfinService.id
    override val displayName: String = JellyfinService.DISPLAY_NAME
    override val capabilities: ServiceCapabilities = ServiceCapabilities.Jellyfin

    /**
     * Password sign-in only in v1 (D5). Quick Connect needs an `AuthInput` variant and a code
     * screen, so it stays a follow-up. The public demo prefills the form, and its account has no
     * password, which the provider accepts.
     */
    override val signInKind: SignInKind = SignInKind.Password(
        PasswordDefaults(JellyfinDemo.SERVER_URL, JellyfinDemo.USERNAME, JellyfinDemo.PASSWORD)
    )

    override fun createAuth(): ServiceAuth = session.auth
}

/** The catalog kind a server resume item can seed. Music libraries only resume tracks. */
internal fun JellyfinKind.toMediaType(): MediaType? = when (this) {
    JellyfinKind.Audio -> MediaType.Track
    JellyfinKind.Album -> MediaType.Album
    JellyfinKind.Artist -> MediaType.Artist
    JellyfinKind.Playlist -> MediaType.Playlist
    JellyfinKind.Other -> null
}
