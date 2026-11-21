package io.github.manuelrtsch.sidetrack.test

import io.github.manuelrtsch.sidetrack.storage.EncryptedStoreException
import io.github.manuelrtsch.sidetrack.storage.JsonStore
import org.json.JSONObject

/**
 * A [JsonStore] that keeps its value in memory, so a test can exercise a credential store without
 * the Android keystore. Writes are copied in and out, so a caller mutating what it passed or read
 * cannot silently change the store.
 */
open class MemoryStore : JsonStore {
    private var value: JSONObject? = null

    override fun read(): JSONObject = value?.let { JSONObject(it.toString()) } ?: JSONObject()

    override fun write(value: JSONObject) {
        this.value = JSONObject(value.toString())
    }

    override fun clear() {
        value = null
    }
}

/** A keystore that lost its key while the server accepted the sign-in. */
class FailingWriteStore : JsonStore {
    override fun read(): JSONObject = JSONObject()

    override fun write(value: JSONObject): Unit = throw EncryptedStoreException("keystore unavailable")

    override fun clear() = Unit
}
