package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.auth.SubsonicAuth
import io.github.manuelrtsch.sidetrack.auth.SubsonicCredentialStore
import io.github.manuelrtsch.sidetrack.data.ServiceRuntime
import io.github.manuelrtsch.sidetrack.data.SignInKind
import io.github.manuelrtsch.sidetrack.data.SubsonicCredentials
import io.github.manuelrtsch.sidetrack.data.SubsonicFamily
import io.github.manuelrtsch.sidetrack.data.SubsonicProvider
import io.github.manuelrtsch.sidetrack.data.SubsonicRuntime
import io.github.manuelrtsch.sidetrack.storage.JsonStore
import io.github.manuelrtsch.sidetrack.test.MemoryStore
import io.github.manuelrtsch.sidetrack.test.ServiceRuntimeContractTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Subsonic-family runtime is the object the app actually talks to once a server is configured.
 * The account-scoped state every runtime must keep is pinned by [ServiceRuntimeContractTest]; the
 * network-facing parts (catalog, API client) are covered by [SubsonicApiTest]. What is left here
 * is what makes the Subsonic family different: two entries, their identity and their own stores.
 */
@RunWith(RobolectricTestRunner::class)
class SubsonicRuntimeTest : ServiceRuntimeContractTest() {
    private val credentials = SubsonicCredentials("http://nas.local:4533", "joe", "sesame")

    override val storeKey: String = "navidrome"
    override val service: ServiceId = ServiceId.Navidrome
    override val capabilities: ServiceCapabilities = ServiceCapabilities.Subsonic

    private fun auth(store: JsonStore) = SubsonicAuth(SubsonicCredentialStore(context, "navidrome-runtime-test", store))

    private fun subsonicRuntime(
        store: JsonStore = MemoryStore(),
        family: SubsonicFamily = SubsonicFamily.Navidrome
    ) = SubsonicRuntime(context, family, auth(store))

    override fun runtime(store: JsonStore): ServiceRuntime = subsonicRuntime(store)

    override fun storedCredential(): JSONObject = JSONObject()
        .put("server_url", credentials.serverUrl)
        .put("username", credentials.username)
        .put("password", credentials.password)

    @Test fun `the provider identity and password defaults come from the runtime`() {
        val runtime = subsonicRuntime()
        val provider = SubsonicProvider(runtime)

        assertEquals(ServiceId.Navidrome, provider.id)
        assertEquals("Navidrome", provider.displayName)
        assertEquals(ServiceCapabilities.Subsonic, provider.capabilities)
        assertEquals(SubsonicFamily.Navidrome.displayName, provider.displayName)
        assertTrue(provider.createAuth() === runtime.auth)
    }

    @Test fun `a second family entry keeps its own identity, label and stores`() {
        val generic = subsonicRuntime(family = SubsonicFamily.Subsonic)
        val provider = SubsonicProvider(generic)

        assertEquals(ServiceId.Subsonic, generic.service)
        assertEquals(ServiceId.Subsonic, provider.id)
        assertEquals("Subsonic", provider.displayName)
        assertEquals(SubsonicFamily.Subsonic, generic.family)
        assertEquals("sidetracks_subsonic", generic.family.credentialStoreName)
        assertEquals("sidetracks-subsonic-queue.bin", generic.family.localStoreName("queue"))
        // Only the Navidrome entry offers the public demo; the others leave the form literal.
        assertNull((provider.signInKind as SignInKind.Password).defaults)
        assertEquals(
            SubsonicFamily.Navidrome.passwordDefaults,
            (SubsonicProvider(subsonicRuntime()).signInKind as SignInKind.Password).defaults
        )
    }
}
