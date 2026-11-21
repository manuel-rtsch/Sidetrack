package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.auth.CredentialPersistenceException
import io.github.manuelrtsch.sidetrack.auth.JellyfinAuth
import io.github.manuelrtsch.sidetrack.auth.JellyfinAuthenticator
import io.github.manuelrtsch.sidetrack.auth.JellyfinCredentialStore
import io.github.manuelrtsch.sidetrack.auth.JellyfinCredentials
import io.github.manuelrtsch.sidetrack.data.AuthInput
import io.github.manuelrtsch.sidetrack.data.JellyfinKind
import io.github.manuelrtsch.sidetrack.data.JellyfinProvider
import io.github.manuelrtsch.sidetrack.data.JellyfinRuntime
import io.github.manuelrtsch.sidetrack.data.JellyfinService
import io.github.manuelrtsch.sidetrack.data.ServiceRuntime
import io.github.manuelrtsch.sidetrack.data.SignInKind
import io.github.manuelrtsch.sidetrack.data.toMediaType
import io.github.manuelrtsch.sidetrack.storage.JsonStore
import io.github.manuelrtsch.sidetrack.test.FailingWriteStore
import io.github.manuelrtsch.sidetrack.test.MemoryStore
import io.github.manuelrtsch.sidetrack.test.ServiceRuntimeContractTest
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Jellyfin runtime owns the account-scoped state the app reads before a server call.
 * [ServiceRuntimeContractTest] pins the part every runtime shares; the SDK mapping is covered by
 * [JellyfinApiTest] and the sign-in flow by [JellyfinAuthTest]. What is left here is the token
 * session and the provider identity.
 */
@RunWith(RobolectricTestRunner::class)
class JellyfinRuntimeTest : ServiceRuntimeContractTest() {
    private val credentials = JellyfinCredentials(
        serverUrl = "http://127.0.0.1:1",
        accessToken = "token",
        authorizationHeader = "MediaBrowser Token=\"token\"",
        userId = "user-1",
        userName = "joe"
    )

    override val storeKey: String = "jellyfin"
    override val service: ServiceId = ServiceId.Jellyfin
    override val capabilities: ServiceCapabilities = ServiceCapabilities.Jellyfin

    override fun runtime(store: JsonStore): ServiceRuntime = jellyfinRuntime(store)

    private fun jellyfinRuntime(
        store: JsonStore,
        authenticator: JellyfinAuthenticator = JellyfinAuthenticator { _, _, _ -> credentials }
    ) = JellyfinRuntime(context, JellyfinAuth(JellyfinCredentialStore(store), authenticator))

    override fun storedCredential(): JSONObject = JSONObject()
        .put("server_url", credentials.serverUrl)
        .put("access_token", credentials.accessToken)
        .put("authorization_header", credentials.authorizationHeader)
        .put("user_id", credentials.userId)
        .put("user_name", credentials.userName)

    @Test fun `signing in stores the session and reports it to the UI`() = runTest {
        val store = MemoryStore()
        val runtime = jellyfinRuntime(store)

        runtime.signIn(AuthInput.Password(credentials.serverUrl, "joe", "secret"))

        assertTrue(runtime.signedIn.value)
        assertEquals(credentials.serverUrl, store.read().optString("server_url"))
    }

    @Test fun `a session that could not be persisted still works for this process`() = runTest {
        val runtime = jellyfinRuntime(FailingWriteStore())

        val failure = runCatching {
            runtime.signIn(AuthInput.Password(credentials.serverUrl, "joe", "secret"))
        }.exceptionOrNull()

        assertTrue(failure is CredentialPersistenceException)
        assertTrue(runtime.signedIn.value)
    }

    @Test fun `a server resume item maps only the kinds a music library can hold`() {
        assertEquals(MediaType.Track, JellyfinKind.Audio.toMediaType())
        assertEquals(MediaType.Album, JellyfinKind.Album.toMediaType())
        assertEquals(MediaType.Artist, JellyfinKind.Artist.toMediaType())
        assertEquals(MediaType.Playlist, JellyfinKind.Playlist.toMediaType())
        assertNull(JellyfinKind.Other.toMediaType())
    }

    @Test fun `the provider identity and sign-in kind come from the runtime`() {
        val runtime = jellyfinRuntime(MemoryStore())
        val provider = JellyfinProvider(runtime)

        assertEquals(ServiceId.Jellyfin, provider.id)
        assertEquals("Jellyfin", provider.displayName)
        assertEquals(JellyfinService.DISPLAY_NAME, provider.displayName)
        assertEquals(ServiceCapabilities.Jellyfin, provider.capabilities)
        assertTrue(provider.signInKind is SignInKind.Password)
        assertTrue(provider.createAuth() === runtime.auth)
    }
}
