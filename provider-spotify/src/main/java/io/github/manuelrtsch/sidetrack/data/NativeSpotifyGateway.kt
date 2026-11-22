package io.github.manuelrtsch.sidetrack.data

import io.github.manuelrtsch.sidetrack.SidetrackDebug
import io.github.manuelrtsch.sidetrack.bridge.SpotifyNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [SpotifyGateway] backed by librespot. Every JNI call is blocking and therefore runs on IO.
 * Native code returns only success/failure; stable failure mapping happens here.
 */
class NativeSpotifyGateway(
    private val native: SpotifyNative,
    private val settings: () -> PlaybackSettings = { PlaybackSettings() },
    private val networkAvailable: () -> Boolean = { true },
    /** Receives the transport's own reason for a failed connect. Developer-facing, never shown. */
    private val onConnectFailure: (String) -> Unit = {},
    private val accessToken: suspend () -> String?
) : SpotifyGateway {
    /**
     * Serialises everything that changes the native session: catalog reads run in parallel, and
     * `engine.connect` starts with `disconnect`, so two of them racing tear down the session the
     * other is authenticating. A monitor, not a `Mutex`: every guarded call is blocking JNI on an IO
     * thread and never suspends, so nothing can deadlock on a suspended holder.
     */
    private val sessionLock = Any()

    /**
     * True when a player is attached to the live session; see [ensureConnected] for why that is not
     * the same as "the session is connected".
     */
    @Volatile
    private var connected = false

    @Volatile
    private var sessionConnected = false

    @Volatile
    private var sessionToken: String? = null

    override suspend fun connect(accessToken: String) = onIo {
        requireNative()
        synchronized(sessionLock) {
            // `connected` alone is not enough: the catalog path rebuilds a session and drops the
            // player without touching the flag. Re-checking under the lock also means a second
            // caller finds the session the first one built instead of rebuilding it.
            if (connected && native.hasPlayer()) return@onIo
            // An invalid session, or one opened with a different OAuth token, must be rebuilt before
            // a player is attached: otherwise it loads through a dead access point and every load fails.
            if (sessionConnected && !native.setAccessToken(accessToken)) {
                sessionConnected = false
                sessionToken = null
            }
            connectSessionLocked(accessToken)
            createPlayerLocked()
        }
    }

    override suspend fun disconnect() = onIo {
        synchronized(sessionLock) {
            connected = false
            sessionConnected = false
            sessionToken = null
            if (native.isAvailable) native.disconnect()
        }
    }

    override suspend fun recreatePlayer(): Boolean = onIo {
        requireNative()
        synchronized(sessionLock) {
            // No player yet: the next connect creates one with the new settings.
            if (!connected) return@onIo false
            val current = settings()
            if (!native.recreatePlayer(current.bitrateKbps, current.normalization, current.gapless)) {
                connected = false
                fail(SpotifyFailure.Unknown, "Could not update the player")
            }
            true
        }
    }

    override suspend fun preload(uri: String) = onIo {
        if (connected && native.isAvailable) native.preload(uri)
    }

    override suspend fun load(uri: String, positionMs: Long, autoplay: Boolean) = onIo {
        requireNative()
        SidetrackDebug.log("gateway.load begin uri=$uri positionMs=$positionMs autoplay=$autoplay")
        val started = System.nanoTime()
        val ok = native.load(uri, positionMs, autoplay)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        SidetrackDebug.log("gateway.load native returned ok=$ok elapsedMs=$elapsedMs")
        if (!ok) {
            if (!networkAvailable()) fail(SpotifyFailure.Network, "No internet connection")
            fail(SpotifyFailure.TrackUnavailable, "Track unavailable")
        }
    }

    override suspend fun play() = onIo {
        requireNative()
        if (!native.play()) fail(SpotifyFailure.NotConnected, "Playback is not ready")
    }

    override suspend fun pause() = onIo {
        requireNative()
        if (!native.pause()) fail(SpotifyFailure.NotConnected, "Playback is not ready")
    }

    override suspend fun stop() = onIo {
        requireNative()
        if (!native.stop()) fail(SpotifyFailure.NotConnected, "Playback is not ready")
    }

    override suspend fun seek(positionMs: Long) = onIo {
        requireNative()
        if (!native.seek(positionMs)) fail(SpotifyFailure.NotConnected, "Playback is not ready")
    }

    /** Forgets the native session after it has been torn down outside this gateway. */
    fun reset() {
        synchronized(sessionLock) {
            connected = false
            sessionConnected = false
            sessionToken = null
        }
    }

    /**
     * Connects with the stored token, refreshing it first when needed.
     *
     * `setAccessToken` runs on every attempt, not only when the token changed: librespot can lose its
     * access-point connection while the OAuth token is still valid, and the native call is a cheap
     * liveness check. It reports a changed token as a rejection, because the live session holds the
     * access point's stored credential, which login5 needs: a refreshed token must rebuild the session
     * rather than overwrite it.
     *
     * The player is checked against the engine rather than trusted from [connected], since
     * [ensureSession] rebuilds a session without creating a player and rebuilding always drops the
     * existing one; otherwise every load would fail with PLAYER_NOT_CREATED.
     */
    suspend fun ensureConnected(forceReconnect: Boolean = false): Boolean {
        if (!networkAvailable()) fail(SpotifyFailure.Network, "No internet connection")
        val token = accessToken() ?: run {
            if (!networkAvailable()) fail(SpotifyFailure.Network, "No internet connection")
            fail(SpotifyFailure.AuthenticationExpired, "Sign in again")
        }
        SidetrackDebug.log("ensureConnected connected=$connected sessionConnected=$sessionConnected tokenLength=${token.length} forceReconnect=$forceReconnect")
        if (forceReconnect) {
            // A stall leaves librespot's access-point connection dead without marking the session
            // invalid, and rebuilding is the only way to get a fresh one before keep-alive notices.
            SidetrackDebug.log("ensureConnected: forced reconnect")
            connected = false
            sessionConnected = false
            sessionToken = null
            connect(token)
            return true
        }
        if (!connected) {
            // A player is process-wide while `connected` is this gateway's memory: after a gateway
            // rebuild the flag says "nothing here" although the session still owns a player.
            // Adopting it avoids rebuilding the session and dropping the current track.
            if (playerAttached()) {
                SidetrackDebug.log("ensureConnected: adopting the attached player")
                connected = true
            } else {
                SidetrackDebug.log("ensureConnected: no player, connecting")
                connect(token)
                return true
            }
        } else if (!playerAttached()) {
            SidetrackDebug.log("ensureConnected: player lost, connecting")
            connect(token)
            return true
        }
        val tokenAccepted = withContext(Dispatchers.IO) {
            synchronized(sessionLock) { native.setAccessToken(token) }
        }
        SidetrackDebug.log("ensureConnected: setAccessToken accepted=$tokenAccepted")
        if (tokenAccepted) {
            sessionToken = token
            return false
        }
        SidetrackDebug.log("ensureConnected: session rejected token, rebuilding")
        connected = false
        sessionConnected = false
        sessionToken = null
        connect(token)
        return true
    }

    /** Connects the librespot session without creating an audio player. Used by catalog reads. */
    suspend fun ensureSession() = onIo {
        requireNative()
        if (!networkAvailable()) fail(SpotifyFailure.Network, "No internet connection")
        val token = accessToken() ?: run {
            if (!networkAvailable()) fail(SpotifyFailure.Network, "No internet connection")
            fail(SpotifyFailure.AuthenticationExpired, "Sign in again")
        }
        synchronized(sessionLock) {
            if (!sessionConnected) {
                refuseToDropPlayer("the session is missing")
                connectSessionLocked(token)
                return@synchronized
            }
            // The first call stores the new token, so a second one only reports a dead session, not a
            // token change: a catalog read must not tear down a live player, but an invalid session
            // still has to be rebuilt.
            if (native.setAccessToken(token)) {
                sessionToken = token
                return@synchronized
            }
            if (native.setAccessToken(token)) {
                sessionToken = token
                return@synchronized
            }
            refuseToDropPlayer("the session is invalid")
            sessionConnected = false
            sessionToken = null
            connectSessionLocked(token)
        }
    }

    /**
     * A catalog read must never tear down a session that is playing: rebuilding always drops the
     * player and the catalog path creates no new one, so audio would stop while the app still reports
     * a playing track. Fail the read instead; the playback path owns the rebuild and the reload.
     *
     * [sessionLock] must be held.
     */
    private fun refuseToDropPlayer(reason: String) {
        if (!native.hasPlayer()) return
        SidetrackDebug.log("ensureSession: $reason while a player is attached; not rebuilding")
        fail(SpotifyFailure.NotConnected, "Spotify session lost; playback will reconnect")
    }

    /** [sessionLock] must be held, so two catalog reads cannot rebuild the session at once. */
    private fun connectSessionLocked(token: String) {
        if (sessionConnected) return
        SidetrackDebug.log("connectSession begin")
        // The failure happens before any request: librespot could not reach an access point. Reporting
        // it as an expired session sends the user to the sign-in screen for a network problem, so the
        // reason is logged and the failure stays a network one.
        val reason = native.connect(token)
        if (reason != null) {
            SidetrackDebug.log("connectSession failed reason=$reason")
            onConnectFailure(reason)
            if (!networkAvailable()) fail(SpotifyFailure.Network, "No internet connection")
            fail(SpotifyFailure.Network, "Couldn't reach Spotify")
        }
        SidetrackDebug.log("connectSession connected")
        sessionConnected = true
        sessionToken = token
        // The player belongs to the session it was created with, so the connect above dropped it.
        // Clearing the flag matters for [ensureSession], which connects without a player: left set,
        // [ensureConnected] would skip the player and every load would fail with PLAYER_NOT_CREATED.
        if (connected) SidetrackDebug.log("connectSession: previous player dropped")
        connected = false
    }

    /** Native truth, so a session rebuilt outside the playback path cannot leave [connected] stale. */
    private suspend fun playerAttached(): Boolean = withContext(Dispatchers.IO) { native.hasPlayer() }

    /** [sessionLock] must be held. */
    private fun createPlayerLocked() {
        val current = settings()
        SidetrackDebug.log("createPlayer bitrateKbps=${current.bitrateKbps} normalization=${current.normalization} gapless=${current.gapless}")
        if (!native.createPlayer(current.bitrateKbps, current.normalization, current.gapless)) {
            SidetrackDebug.log("createPlayer failed")
            native.disconnect()
            sessionConnected = false
            sessionToken = null
            fail(SpotifyFailure.Unknown, "Could not create the player")
        }
        SidetrackDebug.log("createPlayer created")
        connected = true
    }

    private fun requireNative() {
        if (!native.isAvailable) fail(SpotifyFailure.Unavailable, "Spotify playback is unavailable in this build")
    }

    private suspend fun <T> onIo(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun fail(failure: SpotifyFailure, message: String): Nothing = throw SpotifyGatewayException(failure, message)
}
