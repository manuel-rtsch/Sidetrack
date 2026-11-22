package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.QueueRepository
import io.github.manuelrtsch.sidetrack.data.SpotifyFailure
import io.github.manuelrtsch.sidetrack.data.SpotifyGateway
import io.github.manuelrtsch.sidetrack.data.SpotifyGatewayException
import io.github.manuelrtsch.sidetrack.data.SpotifyStallRecovery
import io.github.manuelrtsch.sidetrack.playback.MediaSessionRegistry
import io.github.manuelrtsch.sidetrack.playback.PlaybackSource
import io.github.manuelrtsch.sidetrack.playback.librespotPlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyStallRecoveryTest {
    @Test fun `a stall with no network waits for it instead of dialling`() = runTest {
        val online = MutableStateFlow(false)
        val harness = Harness(this, online)
        harness.adopt("a")

        harness.recovery.recover(positionMs = 4_000L)
        runCurrent()

        // No attempt at all: the connect would only walk every access point to report DNS.
        assertEquals(0, harness.attempts)
        assertFalse(harness.playback.isPlaying)
        assertEquals("Playback stopped — check the connection", harness.playback.notice.value)

        online.value = true
        advanceUntilIdle()

        assertEquals(1, harness.attempts)
        assertNull(harness.playback.notice.value)
    }

    @Test fun `a deferred recovery does not start a track the user left`() = runTest {
        val online = MutableStateFlow(false)
        val harness = Harness(this, online)
        harness.adopt("a")
        harness.recovery.recover(positionMs = 4_000L)
        runCurrent()

        // The user picked something else while the radio was off.
        harness.adopt("b")
        online.value = true
        advanceUntilIdle()

        assertEquals(0, harness.attempts)
    }

    @Test fun `a failed attempt waits for the network before trying again`() = runTest {
        val online = MutableStateFlow(true)
        val harness = Harness(this, online) {
            // The attempt itself finds the network gone, as it did in the field capture.
            online.value = false
            throw SpotifyGatewayException(SpotifyFailure.Network, "no network")
        }
        harness.adopt("a")

        harness.recovery.recover(positionMs = 4_000L)
        advanceUntilIdle()
        assertEquals(1, harness.attempts)

        online.value = true
        advanceUntilIdle()

        // The retry is the second attempt, and it succeeds now that the network reported back.
        assertEquals(2, harness.attempts)
    }

    private class Harness(
        scope: TestScope,
        online: MutableStateFlow<Boolean>,
        private val attempt: () -> Unit = {}
    ) {
        val playbackScope = CoroutineScope(StandardTestDispatcher(scope.testScheduler))
        val playback = librespotPlaybackController(FakeGateway(), playbackScope, MediaSessionRegistry())
        var attempts = 0
        val recovery = SpotifyStallRecovery(playback, playbackScope, QueueRepository(), online) { _, _, _, _ ->
            attempts++
            attempt()
        }

        /** Makes [id] the current item, the way the driver does before starting it. */
        suspend fun adopt(id: String) {
            val uri = "spotify:track:$id"
            val item = Track(
                id = id,
                title = id.uppercase(),
                artist = "Artist",
                artistId = "artist",
                albumId = "album",
                artRes = 0,
                durationMs = 180_000L,
                uri = uri
            )
            playback.setNowPlaying(item, 0L, PlaybackSource(id, uri))
            playback.start(item.key, uri, 0L)
        }
    }

    private class FakeGateway : SpotifyGateway {
        override suspend fun connect(accessToken: String) = Unit
        override suspend fun disconnect() = Unit
        override suspend fun recreatePlayer(): Boolean = true
        override suspend fun preload(uri: String) = Unit
        override suspend fun load(uri: String, positionMs: Long, autoplay: Boolean) = Unit
        override suspend fun play() = Unit
        override suspend fun pause() = Unit
        override suspend fun stop() = Unit
        override suspend fun seek(positionMs: Long) = Unit
    }
}
