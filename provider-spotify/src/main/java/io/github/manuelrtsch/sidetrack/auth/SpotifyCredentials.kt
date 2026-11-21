package io.github.manuelrtsch.sidetrack.auth

/**
 * The minimum material needed to reconnect to Spotify later. The password never reaches the app;
 * only the OAuth tokens are kept, encrypted by [SpotifyCredentialStore].
 */
data class SpotifyCredentials(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMs: Long,
    val scope: String,
    val tokenType: String = "Bearer"
) {
    /** Refresh a minute early so a request never races the expiry. */
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = expiresAtMs <= nowMs + REFRESH_WINDOW_MS

    companion object {
        const val REFRESH_WINDOW_MS = 60_000L
    }
}

interface SpotifyCredentialStore {
    fun read(): SpotifyCredentials?
    fun save(credentials: SpotifyCredentials)
    fun clear()
}
