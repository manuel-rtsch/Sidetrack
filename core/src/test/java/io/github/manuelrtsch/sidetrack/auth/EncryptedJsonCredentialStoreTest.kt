package io.github.manuelrtsch.sidetrack.auth

import io.github.manuelrtsch.sidetrack.storage.JsonStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The guard every provider's credential store now shares. The three behaviours that must not drift
 * between services are here: an unreadable store is a sign-out rather than a crash, an
 * unwritable one is reported in words the user can act on, and a clear never fails.
 */
// Robolectric: the module's unit tests default the android.jar stubs to null, and org.json comes
// from Android. The store's own tests need the real implementation.
@RunWith(RobolectricTestRunner::class)
class EncryptedJsonCredentialStoreTest {
    private data class Session(val token: String)

    private class FakeStore(
        var value: JSONObject = JSONObject(),
        var readError: Exception? = null,
        var writeError: Exception? = null,
        var clearError: Exception? = null
    ) : JsonStore {
        var cleared = false

        override fun read(): JSONObject = readError?.let { throw it } ?: value

        override fun write(value: JSONObject) {
            writeError?.let { throw it }
            this.value = value
        }

        override fun clear() {
            clearError?.let { throw it }
            cleared = true
        }
    }

    private class Store(private val fake: FakeStore) : EncryptedJsonCredentialStore<Session>(fake) {
        override fun encode(value: Session): JSONObject = JSONObject().put(KEY, value.token)

        override fun decode(json: JSONObject): Session? = json.optString(KEY).takeIf { it.isNotEmpty() }?.let(::Session)

        private companion object {
            const val KEY = "token"
        }
    }

    @Test fun `a stored session reads back as it was saved`() {
        val fake = FakeStore()
        val store = Store(fake)

        store.save(Session("abc"))

        assertEquals(Session("abc"), store.read())
        assertEquals("abc", fake.value.optString("token"))
    }

    @Test fun `an incomplete or unreadable store reads as signed out`() {
        val fake = FakeStore()
        val store = Store(fake)

        // Never written.
        assertNull(store.read())

        // Written, but the field the session needs is gone.
        fake.value = JSONObject().put("token", "")
        assertNull(store.read())

        // The keystore could not open the file at all; a compose-time read must not throw.
        fake.readError = IllegalStateException("bad tag")
        assertNull(store.read())
    }

    @Test fun `a device that cannot store the session is told so`() {
        val store = Store(FakeStore(writeError = IllegalStateException("keystore lost")))

        val failure = runCatching { store.save(Session("abc")) }.exceptionOrNull()

        assertTrue(failure is CredentialPersistenceException)
        assertEquals(EncryptedJsonCredentialStore.SAVE_FAILED, failure?.message)
    }

    @Test fun `clearing a store that fails still signs out quietly`() {
        val fake = FakeStore(clearError = IllegalStateException("disk gone"))

        Store(fake).clear()
    }
}
