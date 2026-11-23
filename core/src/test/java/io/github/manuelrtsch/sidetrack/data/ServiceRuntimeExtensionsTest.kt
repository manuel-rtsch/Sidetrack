package io.github.manuelrtsch.sidetrack.data

import io.github.manuelrtsch.sidetrack.AudioQuality
import io.github.manuelrtsch.sidetrack.ServiceId
import io.github.manuelrtsch.sidetrack.test.SilentServiceRuntime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The UI asks every runtime the same questions, so a service that does not implement an optional
 * interface must answer the neutral value instead of crashing on a cast. These are the seams the
 * ViewModel and settings screens rely on.
 */
class ServiceRuntimeExtensionsTest {
    @Test fun `a runtime without native playback reports no native player`() {
        assertFalse(fake().nativeAvailable)
    }

    @Test fun `a runtime without native playback never halts`() {
        assertFalse(fake().halting)
    }

    @Test fun `noting a play request on a runtime without native playback is a no-op`() {
        fake().notePlayRequest(id = 3L, uri = "spotify:track:x")
    }

    @Test fun `a runtime without native playback never consumes a halt stop`() {
        assertFalse(fake().consumeHaltStop(uri = "spotify:track:x", playRequestId = 3L))
    }

    @Test fun `quality setters on a runtime without them are no-ops`() {
        val runtime = fake()

        runtime.setAudioQuality(AudioQuality.VeryHigh)
        runtime.setNormalization(enabled = true)
        runtime.setGapless(enabled = false)
    }

    @Test fun `a runtime without a cache reports an empty cache`() = runTest {
        assertEquals(0L, fake().cacheSizeBytes())
    }

    @Test fun `clearing the cache on a runtime without one is a no-op`() = runTest {
        fake().clearAudioCache()
    }

    @Test fun `native playback answers are forwarded to the runtime`() {
        val runtime = NativeRuntime()

        assertTrue(runtime.nativeAvailable)
        assertTrue(runtime.halting)

        runtime.notePlayRequest(id = 7L, uri = "spotify:track:x")

        assertTrue(runtime.consumeHaltStop(uri = "spotify:track:x", playRequestId = 7L))
        assertEquals(listOf(7L to "spotify:track:x"), runtime.requests)
    }

    @Test fun `quality setters are forwarded to the runtime`() {
        val runtime = QualityRuntime()

        runtime.setAudioQuality(AudioQuality.Normal)
        runtime.setNormalization(enabled = true)
        runtime.setGapless(enabled = false)

        assertEquals(listOf("quality:Normal", "normalization:true", "gapless:false"), runtime.calls)
    }

    @Test fun `cache answers are forwarded to the runtime`() = runTest {
        val runtime = CacheOnlyRuntime()

        assertEquals(4_096L, runtime.cacheSizeBytes())
        runtime.clearAudioCache()

        assertTrue(runtime.cleared)
    }

    private fun fake() = FakeRuntime()

    /** A runtime with no optional interfaces, as a Jellyfin or Navidrome install sees it. */
    private open class FakeRuntime : SilentServiceRuntime(ServiceId.Local)

    private class NativeRuntime :
        FakeRuntime(),
        NativePlaybackRuntime {
        override val nativeAvailable: Boolean = true
        override val halting: Boolean = true
        val requests = mutableListOf<Pair<Long, String?>>()

        override fun notePlayRequest(id: Long, uri: String?) {
            requests += id to uri
        }

        override fun consumeHaltStop(uri: String?, playRequestId: Long): Boolean = true
    }

    private class QualityRuntime :
        FakeRuntime(),
        QualitySettingsRuntime {
        val calls = mutableListOf<String>()

        override fun setAudioQuality(quality: AudioQuality) {
            calls += "quality:${quality.name}"
        }

        override fun setNormalization(enabled: Boolean) {
            calls += "normalization:$enabled"
        }

        override fun setGapless(enabled: Boolean) {
            calls += "gapless:$enabled"
        }
    }

    private class CacheOnlyRuntime :
        FakeRuntime(),
        CacheRuntime {
        var cleared = false

        override suspend fun cacheSizeBytes(): Long = 4_096L

        override suspend fun clearAudioCache() {
            cleared = true
        }
    }
}
