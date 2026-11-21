package io.github.manuelrtsch.sidetrack.auth

import io.github.manuelrtsch.sidetrack.storage.JsonStore
import org.json.JSONObject

/**
 * One session stored as one JSON object in the encrypted store.
 *
 * Every provider grew the same three methods around its fields, and the guard is the part that
 * has to behave identically everywhere: a store that cannot be read means "signed out", never a
 * crash while the UI composes, and a store that cannot be written says so instead of pretending
 * the session was saved. Only the fields differ, so a provider supplies [encode] and [decode].
 */
abstract class EncryptedJsonCredentialStore<T>(protected val store: JsonStore) {
    /** This session's fields. */
    protected abstract fun encode(value: T): JSONObject

    /** The session in [json], or null when it is not a complete session any more. */
    protected abstract fun decode(json: JSONObject): T?

    /** Never throws: an unreadable or incomplete store reads as signed out. */
    fun read(): T? = runCatching { decode(store.read()) }.getOrNull()

    /** Throws [CredentialPersistenceException] when the device could not store the session. */
    fun save(value: T) {
        try {
            store.write(encode(value))
        } catch (e: Exception) {
            throw CredentialPersistenceException(SAVE_FAILED, e)
        }
    }

    /** Never throws: clearing a store that is already gone is still signed out. */
    fun clear() {
        runCatching { store.clear() }
    }

    /** The text under [key], or null when it was never written or was left empty. */
    protected fun JSONObject.readString(key: String): String? = optString(key).takeIf { it.isNotEmpty() }

    companion object {
        /** Shown when sign-in worked but the session could not be kept on this device. */
        const val SAVE_FAILED =
            "Signed in, but this device could not store the session securely. " +
                "You will need to sign in again next time."
    }
}
