package io.github.manuelrtsch.sidetrack.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The three providers that take a typed address share [normalizeServerUrl], so the cases that used to
 * live in each provider's test now run against the one function: the wrappers only add their port and
 * their wording.
 */
class ServerUrlTest {
    @Test fun `a bare host gets http and the default port`() {
        assertEquals("http://music.example.org:8096", normalizeServerUrl(" music.example.org ", INVALID, 8096))
        assertEquals("http://192.168.1.20:4533", normalizeServerUrl("http://192.168.1.20:4533/", INVALID, 8096))
    }

    @Test fun `an https address keeps its implicit 443`() {
        assertEquals("https://music.example.org", normalizeServerUrl("https://music.example.org", INVALID, 8096))
        assertEquals("https://music.example.org:8920", normalizeServerUrl("https://music.example.org:8920", INVALID, 8096))
    }

    @Test fun `a typed base path survives the port`() {
        assertEquals("http://demo.jellyfin.org:8096/stable", normalizeServerUrl("demo.jellyfin.org/stable/", INVALID, 8096))
        assertEquals("https://demo.jellyfin.org/stable", normalizeServerUrl("https://demo.jellyfin.org/stable/", INVALID, 8096))
    }

    @Test fun `no default port leaves the address as typed`() {
        assertEquals("http://nas.local:4533", normalizeServerUrl("nas.local:4533", INVALID))
        assertEquals("https://music.example.org", normalizeServerUrl("https://music.example.org/", INVALID))
    }

    @Test fun `anything that is not an http host is refused with the provider's wording`() {
        val failure = runCatching { normalizeServerUrl("ftp://music.example.org", "Enter the Plex server address") }
            .exceptionOrNull() as ServiceException

        assertEquals(ServiceFailure.NotConnected, failure.failure)
        assertEquals("Enter the Plex server address", failure.message)
    }

    private companion object {
        const val INVALID = "Enter the server address"
    }
}
