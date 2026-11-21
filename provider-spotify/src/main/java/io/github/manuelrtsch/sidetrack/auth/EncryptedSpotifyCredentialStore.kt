package io.github.manuelrtsch.sidetrack.auth

import android.content.Context
import io.github.manuelrtsch.sidetrack.storage.GuardedEncryptedStore
import io.github.manuelrtsch.sidetrack.storage.JsonStore
import org.json.JSONObject

/**
 * Token storage backed by the SDK's encrypted file store: an atomic app-private file encrypted with
 * a non-exportable Android Keystore key. Tokens must never go into plain SharedPreferences.
 *
 * Both calls are total. [read] never throws — the store is read while the UI composes, and an
 * invalidated keystore key (ROM flash, GSI install, device restore) must not become a launch
 * crash. [save] reports an unreadable device as [CredentialPersistenceException], which the
 * sign-in flow turns into "signed in, but you will have to sign in again next time".
 */
class EncryptedSpotifyCredentialStore(
    context: Context,
    // Legacy name kept so existing dev installs keep their Spotify session.
    name: String = "sidetracks_spotify",
    store: JsonStore = GuardedEncryptedStore(context, name)
) : EncryptedJsonCredentialStore<SpotifyCredentials>(store),
    SpotifyCredentialStore {

    override fun encode(value: SpotifyCredentials): JSONObject = JSONObject()
        .put(KEY_ACCESS, value.accessToken)
        .put(KEY_REFRESH, value.refreshToken ?: "")
        .put(KEY_EXPIRES_AT, value.expiresAtMs)
        .put(KEY_SCOPE, value.scope)
        .put(KEY_TOKEN_TYPE, value.tokenType)

    override fun decode(json: JSONObject): SpotifyCredentials? {
        val access = json.readString(KEY_ACCESS) ?: return null
        return SpotifyCredentials(
            accessToken = access,
            refreshToken = json.readString(KEY_REFRESH),
            expiresAtMs = json.optLong(KEY_EXPIRES_AT, 0L),
            scope = json.optString(KEY_SCOPE),
            tokenType = json.optString(KEY_TOKEN_TYPE, "Bearer")
        )
    }

    private companion object {
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_EXPIRES_AT = "expires_at_ms"
        const val KEY_SCOPE = "scope"
        const val KEY_TOKEN_TYPE = "token_type"
    }
}
