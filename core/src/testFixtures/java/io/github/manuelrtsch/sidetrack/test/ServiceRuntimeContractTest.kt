package io.github.manuelrtsch.sidetrack.test

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.manuelrtsch.sidetrack.AudioQuality
import io.github.manuelrtsch.sidetrack.MediaKey
import io.github.manuelrtsch.sidetrack.MediaType
import io.github.manuelrtsch.sidetrack.ServiceCapabilities
import io.github.manuelrtsch.sidetrack.ServiceId
import io.github.manuelrtsch.sidetrack.Track
import io.github.manuelrtsch.sidetrack.data.FilePositionStore
import io.github.manuelrtsch.sidetrack.data.PlaybackSettings
import io.github.manuelrtsch.sidetrack.data.ResumePoint
import io.github.manuelrtsch.sidetrack.data.ServiceRuntime
import io.github.manuelrtsch.sidetrack.data.cacheSizeBytes
import io.github.manuelrtsch.sidetrack.data.clearAudioCache
import io.github.manuelrtsch.sidetrack.data.setAudioQuality
import io.github.manuelrtsch.sidetrack.data.setGapless
import io.github.manuelrtsch.sidetrack.data.setNormalization
import io.github.manuelrtsch.sidetrack.storage.JsonStore
import java.io.File
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The account-scoped contract every [ServiceRuntime] must keep, whatever the service.
 *
 * These are the behaviours that used to be asserted once per provider in near-identical copies:
 * a signed-out runtime holds no account state, settings apply immediately, the queue, history and
 * resume point belong to the account and survive a rebuilt runtime, signing out clears all of it,
 * and the audio cache is the account's own. A provider extends this and supplies its factory, so
 * the contract cannot drift between services.
 *
 * Provider-specific behaviour — identity, sign-in kind, server quirks, mapping — stays in the
 * provider's own test class, which is also where the Robolectric runner is declared.
 */
abstract class ServiceRuntimeContractTest {
    protected val context: Context = ApplicationProvider.getApplicationContext()

    /** This account's store key, e.g. `jellyfin`; see [io.github.manuelrtsch.sidetrack.data.StoreNames]. */
    protected abstract val storeKey: String

    protected abstract val service: ServiceId

    protected abstract val capabilities: ServiceCapabilities

    /** A runtime over [store], as a relaunch would build it. */
    protected abstract fun runtime(store: JsonStore): ServiceRuntime

    /** What this runtime's credential store holds once the account is signed in. */
    protected abstract fun storedCredential(): JSONObject

    protected fun runtime(): ServiceRuntime = runtime(MemoryStore())

    protected fun track(id: String) = Track(
        id = id,
        title = "Track $id",
        artist = "Aurora",
        artistId = "artist-1",
        albumId = "album-1",
        artRes = 0,
        durationMs = 180_000L,
        service = service
    )

    protected fun stateFile(name: String) = File(context.filesDir, "sidetracks-$storeKey-$name.bin")

    @Before
    fun clearState() {
        listOf("queue", "history", "resume").forEach { stateFile(it).delete() }
    }

    @Test
    fun `an empty credential store starts signed out with empty account state`() {
        val runtime = runtime()

        assertFalse(runtime.signedIn.value)
        assertNull(runtime.resumePoint)
        assertNull(runtime.queue.snapshot.current)
        assertTrue(runtime.history.snapshot().isEmpty())
        assertEquals(service, runtime.service)
        assertEquals(capabilities, runtime.capabilities)
        assertEquals(PlaybackSettings(), runtime.settings.value)
    }

    @Test
    fun `a stored credential starts the runtime signed in`() {
        val store = MemoryStore().apply { write(storedCredential()) }

        assertTrue(runtime(store).signedIn.value)
    }

    @Test
    fun `settings changes apply immediately`() {
        val runtime = runtime()
        runtime.setAutoplay(false)
        runtime.setAudioQuality(AudioQuality.Normal)
        runtime.setNormalization(true)
        runtime.setGapless(false)

        assertEquals(
            PlaybackSettings(
                audioQuality = AudioQuality.Normal,
                normalization = true,
                gapless = false,
                autoplay = false
            ),
            runtime.settings.value
        )
    }

    @Test
    fun `the queue survives a new runtime for the same account`() {
        runtime().queue.playItem(track("one"))

        val second = runtime()

        assertEquals("one", second.queue.snapshot.current?.id)
        assertEquals(service, second.queue.snapshot.current?.key?.service)
    }

    @Test
    fun `history survives a new runtime for the same account`() {
        runtime().history.record(track("one"), playedAtMs = 1_000L)

        assertEquals(listOf("one"), runtime().history.items().map { it.id })
    }

    @Test
    fun `the resume point is read from this account's own file`() {
        FilePositionStore(stateFile("resume")).write(ResumePoint(MediaKey(service, MediaType.Track, "one"), 42_000L))

        val runtime = runtime()

        assertEquals(MediaKey(service, MediaType.Track, "one"), runtime.resumePoint?.key)
        assertEquals(42_000L, runtime.resumePoint?.positionMs)
    }

    @Test
    fun `signing out clears the session and the account state`() {
        val store = MemoryStore().apply { write(storedCredential()) }
        val runtime = runtime(store)
        runtime.queue.playItem(track("one"))
        runtime.history.record(track("one"), playedAtMs = 1_000L)

        runtime.signOut()

        assertFalse(runtime.signedIn.value)
        assertNull(runtime.resumePoint)
        assertNull(runtime.queue.snapshot.current)
        assertTrue(runtime.history.snapshot().isEmpty())
        assertEquals(0, store.read().length())
    }

    @Test
    fun `clearing the audio cache removes cached artwork`() = runTest {
        val artwork = File(context.cacheDir, "artwork").apply { mkdirs() }
        File(artwork, "cover").writeBytes(ByteArray(64))

        val runtime = runtime()

        assertTrue(runtime.cacheSizeBytes() >= 64L)
        runtime.clearAudioCache()
        assertEquals(0L, runtime.cacheSizeBytes())
    }
}
