package io.github.manuelrtsch.sidetrack.auth

import io.github.manuelrtsch.sidetrack.data.httpFetch
import java.io.IOException
import org.json.JSONObject

/** Swappable so token parsing and refresh logic can be tested without the network. */
interface SpotifyTokenClient {
    suspend fun exchange(
        clientId: String,
        code: String,
        verifier: String,
        redirectUri: String
    ): SpotifyCredentials

    suspend fun refresh(clientId: String, refreshToken: String): SpotifyCredentials
}

/**
 * Minimal form-encoded client for Spotify's token endpoint. Authorization-code exchange is the
 * only place the PKCE verifier leaves the device; refresh tokens are rotated when Spotify returns
 * a new one.
 */
class HttpSpotifyTokenClient(
    // Swappable so the request/response handling can run against a local server in tests.
    private val tokenUrl: String = TOKEN_URL
) : SpotifyTokenClient {
    override suspend fun exchange(
        clientId: String,
        code: String,
        verifier: String,
        redirectUri: String
    ): SpotifyCredentials = request(
        mapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "client_id" to clientId,
            "code_verifier" to verifier
        ),
        previousRefresh = null
    )

    override suspend fun refresh(clientId: String, refreshToken: String): SpotifyCredentials = request(
        mapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "client_id" to clientId
        ),
        previousRefresh = refreshToken
    )

    private suspend fun request(form: Map<String, String>, previousRefresh: String?): SpotifyCredentials {
        val body = form.entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        val response = httpFetch(
            tokenUrl,
            method = "POST",
            headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded",
                "Accept" to "application/json"
            ),
            body = body,
            timeoutMs = TIMEOUT_MS
        )
        val json = runCatching { JSONObject(response.body) }.getOrElse {
            throw IOException("Spotify returned an unreadable token response (HTTP ${response.status})")
        }
        if (!response.isSuccess) {
            throw IOException(
                json.optString("error_description")
                    .ifEmpty { "Spotify token request failed (HTTP ${response.status})" }
            )
        }
        val access = json.optString("access_token")
        if (access.isEmpty()) throw IOException("Spotify did not return an access token")
        val expiresIn = json.optLong("expires_in", 0L)
        return SpotifyCredentials(
            accessToken = access,
            refreshToken = json.optString("refresh_token").takeIf { it.isNotEmpty() } ?: previousRefresh,
            expiresAtMs = System.currentTimeMillis() + expiresIn.coerceAtLeast(0L) * 1_000L,
            scope = json.optString("scope"),
            tokenType = json.optString("token_type", "Bearer")
        )
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

    private companion object {
        const val TOKEN_URL = "https://accounts.spotify.com/api/token"
        const val TIMEOUT_MS = 15_000
    }
}
