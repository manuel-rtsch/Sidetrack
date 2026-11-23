package io.github.manuelrtsch.sidetrack.auth

import android.content.Context
import io.github.manuelrtsch.sidetrack.ServiceId
import io.github.manuelrtsch.sidetrack.data.ArtworkAccess
import io.github.manuelrtsch.sidetrack.data.AuthInput
import io.github.manuelrtsch.sidetrack.data.RuntimeAuth
import io.github.manuelrtsch.sidetrack.data.ServiceAccount
import io.github.manuelrtsch.sidetrack.data.ServiceException
import io.github.manuelrtsch.sidetrack.data.ServiceFailure
import io.github.manuelrtsch.sidetrack.data.normalizeServerUrl
import io.github.manuelrtsch.sidetrack.jellyfin.BuildConfig
import io.github.manuelrtsch.sidetrack.storage.GuardedEncryptedStore
import io.github.manuelrtsch.sidetrack.storage.JsonStore
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.SecureConnectionException
import org.jellyfin.sdk.api.client.exception.TimeoutException
import org.jellyfin.sdk.api.client.extensions.authenticateUserByName
import org.jellyfin.sdk.api.client.extensions.authenticationApi
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.json.JSONObject

/** A signed-in Jellyfin session: the token, and who it belongs to. */
data class JellyfinCredentials(
    val serverUrl: String,
    val accessToken: String,
    val authorizationHeader: String,
    val userId: String,
    val userName: String
) {
    /** The header every request for this account carries: audio, artwork and the API client. */
    fun headers(): Map<String, String> = mapOf(HEADER_AUTHORIZATION to authorizationHeader)

    private companion object {
        const val HEADER_AUTHORIZATION = "Authorization"
    }
}

/**
 * Trims a typed server address into the base URL the SDK expects, filling in a missing port with
 * Jellyfin's default so a bare `192.168.1.20` reaches a default install.
 *
 * Only `http` gets the default filled in. An `https://` address keeps its implicit 443, because 8920
 * is Jellyfin's HTTPS port only when the certificate is configured inside Jellyfin itself, while a
 * proxied server answers on 443: appending 8920 would turn a working address into a dead one.
 */
fun normalizeJellyfinServerUrl(value: String): String = normalizeServerUrl(value, "Enter the Jellyfin server address", JELLYFIN_HTTP_PORT)

/** The port a default Jellyfin install listens on; see [normalizeJellyfinServerUrl]. */
private const val JELLYFIN_HTTP_PORT = 8096

/**
 * Jellyfin signs in with a password once and keeps the token, unlike Navidrome, which needs the
 * password for every request. So the store holds the token — never the password — in the same
 * encrypted store as the other providers' sessions.
 */
class JellyfinCredentialStore(store: JsonStore) : EncryptedJsonCredentialStore<JellyfinCredentials>(store) {
    /** Production wiring: the encrypted, write-through store under a provider-specific name. */
    constructor(context: Context, name: String = "sidetracks_jellyfin") :
        this(GuardedEncryptedStore(context, name))

    override fun encode(value: JellyfinCredentials): JSONObject = JSONObject()
        .put(KEY_SERVER, value.serverUrl)
        .put(KEY_TOKEN, value.accessToken)
        .put(KEY_HEADER, value.authorizationHeader)
        .put(KEY_USER_ID, value.userId)
        .put(KEY_USER_NAME, value.userName)

    override fun decode(json: JSONObject): JellyfinCredentials? {
        val server = json.readString(KEY_SERVER) ?: return null
        val token = json.readString(KEY_TOKEN) ?: return null
        val header = json.readString(KEY_HEADER) ?: return null
        val userId = json.readString(KEY_USER_ID) ?: return null
        val userName = json.readString(KEY_USER_NAME) ?: return null
        return JellyfinCredentials(server, token, header, userId, userName)
    }

    private companion object {
        const val KEY_SERVER = "server_url"
        const val KEY_TOKEN = "access_token"
        const val KEY_HEADER = "authorization_header"
        const val KEY_USER_ID = "user_id"
        const val KEY_USER_NAME = "user_name"
    }
}

/**
 * The one call the adapter needs from the SDK during sign-in, as an interface so the auth tests need
 * no server, no `Context` and no Robolectric.
 */
fun interface JellyfinAuthenticator {
    /** Authenticates and returns the session, or throws [ServiceException]. */
    suspend fun signIn(serverUrl: String, username: String, password: String): JellyfinCredentials
}

/**
 * The SDK-backed authenticator. One `createJellyfin` per instance; an `ApiClient` per server.
 *
 * The authorization header comes from the SDK's own [AuthorizationHeaderBuilder] so the device id
 * matches the one the API client sends: Jellyfin keys a session on it, and a hand-rolled header
 * would create a second session per request.
 */
class SdkJellyfinAuthenticator(private val context: Context) : JellyfinAuthenticator {
    /**
     * The SDK's suspend API is generated for multiplatform use and runs OkHttp synchronously on the
     * calling thread: called from `viewModelScope`, it would throw
     * [android.os.NetworkOnMainThreadException] once the response body is read.
     */
    override suspend fun signIn(serverUrl: String, username: String, password: String): JellyfinCredentials = withContext(Dispatchers.IO) { signInBlocking(serverUrl, username, password) }

    private suspend fun signInBlocking(serverUrl: String, username: String, password: String): JellyfinCredentials {
        val jellyfin = createJellyfin {
            context = this@SdkJellyfinAuthenticator.context.applicationContext
            clientInfo = ClientInfo(name = CLIENT_NAME, version = BuildConfig.VERSION_NAME)
        }
        val api = try {
            jellyfin.createApi(baseUrl = serverUrl)
        } catch (error: Throwable) {
            throw jellyfinFailure(error)
        }
        // Asked before the password travels: a server older than 12.0 accepts the sign-in and then
        // fails every read, which is worse than this refusal.
        val version = try {
            api.systemApi.getPublicSystemInfo().content.version
        } catch (error: Throwable) {
            throw jellyfinFailure(error)
        }
        requireJellyfinVersion(version)
        val result = try {
            api.authenticationApi.authenticateUserByName(username, password).content
        } catch (error: Throwable) {
            throw jellyfinFailure(error)
        }
        val token = result.accessToken
            ?: throw ServiceException(ServiceFailure.AuthenticationExpired, "Jellyfin did not return a session")
        val user = result.user
        // Positional on purpose: the SDK can rename its "client…"/"device…" parameters between minor
        // releases without this line noticing.
        val header = AuthorizationHeaderBuilder.buildHeader(
            api.clientInfo.name,
            api.clientInfo.version,
            api.deviceInfo.name,
            api.deviceInfo.id,
            token
        )
        return JellyfinCredentials(
            serverUrl = serverUrl,
            accessToken = token,
            authorizationHeader = header,
            userId = user?.id?.toString().orEmpty(),
            userName = user?.name ?: username
        )
    }

    private companion object {
        const val CLIENT_NAME = "Sidetrack"
    }
}

/** Password sign-in against a Jellyfin server, with the token kept instead of the password. */
class JellyfinAuth(
    private val store: JellyfinCredentialStore,
    private val authenticator: JellyfinAuthenticator
) : RuntimeAuth {
    /**
     * The session for this process: a device that cannot persist the store still stays signed in until
     * the app closes, as Navidrome and Spotify do.
     */
    @Volatile
    private var current: JellyfinCredentials? = null

    override fun hasCredentials(): Boolean = credentials() != null

    fun credentials(): JellyfinCredentials? = current ?: runCatching { store.read() }.getOrNull()?.also { current = it }

    /** Installs the account's header for artwork if a session is already stored. */
    fun installArtworkHeaders() {
        credentials()?.let { ArtworkAccess.install(it.headers()) }
    }

    override suspend fun signIn(input: AuthInput): ServiceAccount {
        val password = input as? AuthInput.Password
            ?: throw ServiceException(ServiceFailure.NotConnected, "Jellyfin needs a server, username and password")
        val serverUrl = normalizeJellyfinServerUrl(password.serverUrl)
        // No password requirement: a public demo server can have accounts without one, and Jellyfin
        // answers an empty password like any other.
        if (password.username.isBlank()) {
            throw ServiceException(ServiceFailure.NotConnected, "Enter your Jellyfin username")
        }
        val credentials = authenticator.signIn(serverUrl, password.username.trim(), password.password)
        current = credentials
        store.save(credentials)
        ArtworkAccess.install(credentials.headers())
        return account(credentials)
    }

    override suspend fun restore(): ServiceAccount? = credentials()?.let {
        ArtworkAccess.install(it.headers())
        account(it)
    }

    override suspend fun signOut() = clear()

    /** Synchronous clear for the runtime's non-suspending sign-out path. */
    override fun clear() {
        current = null
        store.clear()
        ArtworkAccess.clear()
    }

    private fun account(credentials: JellyfinCredentials) = ServiceAccount(
        service = ServiceId.Jellyfin,
        userId = credentials.userId.ifEmpty { credentials.userName },
        displayName = credentials.userName,
        serverUrl = credentials.serverUrl
    )
}

/** The oldest server Sidetrack speaks: 12.0. See [requireJellyfinVersion]. */
private const val MINIMUM_JELLYFIN_MAJOR = 12

/** The major version of a Jellyfin version string, or null when it cannot be read (`"unstable"`). */
internal fun jellyfinMajorVersion(version: String?): Int? = version?.trim()?.takeWhile { it.isDigit() }?.takeIf { it.isNotEmpty() }?.toIntOrNull()

/**
 * Refuses a server the 12.x SDK cannot read, naming it.
 *
 * A 10.9 server accepts the password sign-in and then fails deserialization on the first
 * `GET /Items`, because its `UserItemDataDto` carries no `ItemId`: every screen of a session that
 * looked fine reports "needs Jellyfin 12.0 or newer". Asking `/System/Info/Public` first turns that
 * into a refusal on the sign-in form, before any password is sent.
 *
 * An unreadable version string is let through: a fork or a proxy can answer something we did not
 * anticipate, and its reads still fail honestly through [jellyfinFailure].
 */
internal fun requireJellyfinVersion(version: String?) {
    val major = jellyfinMajorVersion(version) ?: return
    if (major < MINIMUM_JELLYFIN_MAJOR) {
        throw ServiceException(
            ServiceFailure.NotConnected,
            "That server runs Jellyfin ${version!!.trim()}. Sidetrack needs Jellyfin 12.0 or newer."
        )
    }
}

/**
 * Maps an SDK failure onto the neutral category the UI explains. Kept out of the database and
 * store code so the mapping can be unit-tested without a server (decision D14: the dev container
 * is where the real responses get checked).
 */
internal fun jellyfinFailure(error: Throwable): ServiceException = when (error) {
    is ServiceException -> error

    is InvalidStatusException -> when (error.status) {
        401, 403 -> ServiceException(
            ServiceFailure.AuthenticationExpired,
            "Jellyfin rejected that username or password"
        )

        404 -> ServiceException(ServiceFailure.NotConnected, "No Jellyfin server answered at that address")

        in 500..599 -> ServiceException(ServiceFailure.Unavailable, "The Jellyfin server is not available right now")

        else -> ServiceException(ServiceFailure.Unavailable, "Jellyfin sign-in failed (HTTP ${error.status})")
    }

    is TimeoutException -> ServiceException(ServiceFailure.Network, "The Jellyfin server did not answer in time")

    is SecureConnectionException -> ServiceException(
        ServiceFailure.Network,
        "The Jellyfin server's certificate could not be verified"
    )

    is ApiClientException -> ServiceException(
        ServiceFailure.Unavailable,
        "Could not talk to that Jellyfin server. Sidetrack needs Jellyfin 12.0 or newer."
    )

    is IOException -> ServiceException(ServiceFailure.Network, "Could not reach the Jellyfin server")

    else -> ServiceException(ServiceFailure.Unknown, "Jellyfin sign-in failed", error)
}
