package io.github.manuelrtsch.sidetrack.playback

import android.content.Context
import android.media.AudioManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.manuelrtsch.sidetrack.MediaKey
import io.github.manuelrtsch.sidetrack.MediaType
import io.github.manuelrtsch.sidetrack.ServiceId
import io.github.manuelrtsch.sidetrack.Track
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.Shadows.shadowOf

/**
 * The controller's own contract, with a fake engine: audio focus, the platform media session, the
 * notification hooks and the recovery/stop paths. The librespot engine is exercised next to its
 * code in `:provider-spotify`; here the engine is only a recording stand-in, so each assertion is
 * about the controller's decision rather than the native player's state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlaybackControllerSessionTest : PlaybackControllerTestBase() {

    @Test fun `start denied by audio focus leaves the engine untouched`() = runTest {
        focusResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val engine = FakeEngine()

        newController(engine, context = context)
            .start(MediaKey(ServiceId.Spotify, MediaType.Track, "a"), "spotify:track:a", 0L)

        assertFalse("a denied focus must not mark the session playing", controller.isActive)
        assertEquals(0, engine.starts)
    }

    @Test fun `start claims audio focus and publishes to the notification`() = runTest {
        focusResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val engine = FakeEngine()
        var notifications = 0
        controller = newController(engine, context = context, notifySync = { _, _ -> notifications++ })

        controller.start(MediaKey(ServiceId.Spotify, MediaType.Track, "a"), "spotify:track:a", 12_000L)

        assertTrue(controller.isActive)
        assertSame(controller, registry.active)
        assertEquals(1, engine.starts)
        assertEquals("spotify:track:a", engine.lastSource?.uri)
        assertEquals(12_000L, engine.lastPosition)
        assertEquals(1, notifications)
        assertEquals("spotify:track:a", controller.currentSource?.uri)
    }

    @Test fun `a failed start releases focus and the registry`() = runTest {
        focusResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val engine = FakeEngine().apply { failStart = IllegalStateException("decode failed") }

        val error = runCatching {
            newController(engine, context = context)
                .start(MediaKey(ServiceId.Spotify, MediaType.Track, "a"), "spotify:track:a", 0L)
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertFalse(controller.isActive)
        assertNull(registry.active)
        assertNotNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
    }

    @Test fun `pause gives audio focus back and invalidates in-flight commands`() = runTest {
        focusResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val engine = FakeEngine()
        controller = newController(engine, context = context)
        controller.start(MediaKey(ServiceId.Spotify, MediaType.Track, "a"), "spotify:track:a", 0L)

        controller.pause()
        runCurrent()

        assertFalse(controller.isActive)
        assertEquals(1, engine.invalidations)
        assertEquals(1, engine.pauses)
        assertNotNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
    }

    @Test fun `resume denied by audio focus reports false without playing`() = runTest {
        focusResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val engine = FakeEngine()
        controller = newController(engine, context = context)
        controller.setNowPlaying(track(), 0L, PlaybackSource("a", "spotify:track:a"))

        assertFalse(controller.resume())

        assertEquals(0, engine.resumes)
        assertFalse(controller.isActive)
    }

    @Test fun `resume reports false when the engine cannot start audio`() = runTest {
        focusResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val engine = FakeEngine().apply { resumeResult = false }
        controller = newController(engine, context = context)
        controller.setNowPlaying(track(), 0L, PlaybackSource("a", "spotify:track:a"))

        assertFalse(controller.resume())

        assertEquals(1, engine.resumes)
        assertFalse(controller.isActive)
    }

    @Test fun `a failed resume releases focus and the registry`() = runTest {
        focusResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val engine = FakeEngine().apply { resumeResult = false }
        controller = newController(engine, context = context)
        controller.setNowPlaying(track(), 0L, PlaybackSource("a", "spotify:track:a"))

        assertFalse(controller.resume())

        // The resume path claims the session and the focus request before the engine decides, so
        // a refusal has to give both back — otherwise the registry names a silent owner and the
        // app that paused for us stays paused.
        assertNull(registry.active)
        assertNotNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
    }

    @Test fun `resume claims the session and hands the position to the engine`() = runTest {
        focusResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val engine = FakeEngine()
        controller = newController(engine, context = context)
        controller.setNowPlaying(track(), 42_000L, PlaybackSource("a", "spotify:track:a"))

        assertTrue(controller.resume())

        assertEquals(1, engine.resumes)
        assertEquals(42_000L, engine.lastPosition)
        assertTrue(controller.isActive)
        assertSame(controller, registry.active)
    }

    @Test fun `resume without a loaded source asks the owner instead of the engine`() = runTest {
        val engine = FakeEngine()
        controller = newController(engine)
        var restarts = 0

        controller.onResumeWithoutSource = { restarts++ }
        assertTrue(controller.resume())
        assertEquals(1, restarts)

        controller.onResumeWithoutSource = null
        controller.fallbackResume = { restarts++ }
        assertTrue(controller.resume())
        assertEquals(2, restarts)

        // A cold process with nobody left to start the item must report failure, not silence.
        controller.fallbackResume = null
        assertFalse(controller.resume())
        assertEquals(0, engine.resumes)
    }

    @Test fun `the engine's buffering state is mirrored for the seek row`() = runTest {
        val engine = FakeEngine()
        controller = newController(engine)
        runCurrent()

        engine.state.value = PlaybackEngineState(isPlaying = true, isBuffering = true)
        runCurrent()
        assertTrue(controller.buffering.value)

        engine.state.value = PlaybackEngineState(isPlaying = true, isBuffering = false)
        runCurrent()
        assertFalse(controller.buffering.value)
    }

    @Test fun `a paused engine never shows the loading state`() = runTest {
        val engine = FakeEngine()
        controller = newController(engine)
        runCurrent()

        // The field log had a seek while paused end in `Loading`: the sink reported a starvation
        // that nobody was waiting for.
        engine.state.value = PlaybackEngineState(isPlaying = false, isBuffering = true)
        runCurrent()

        assertFalse(controller.buffering.value)
    }

    @Test fun `pollEvent and the ended flow come from the engine`() = runTest {
        val engine = FakeEngine().apply {
            event = PlaybackEvent.Position(1_234L)
            ended.value = "spotify:track:a"
        }
        controller = newController(engine)

        assertEquals(PlaybackEvent.Position(1_234L), controller.pollEvent())
        assertEquals("spotify:track:a", controller.ended.first())
    }

    @Test fun `toggle pauses a playing session and resumes a paused one`() = runTest {
        focusResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val engine = FakeEngine()
        controller = newController(engine, context = context)
        controller.setNowPlaying(track(uri = "spotify:track:a"), 0L)
        controller.start(MediaKey(ServiceId.Spotify, MediaType.Track, "a"), "spotify:track:a", 0L)
        runCurrent()

        controller.togglePlayPause()
        runCurrent()
        assertFalse(controller.isActive)
        assertEquals(1, engine.pauses)

        focusResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        controller.togglePlayPause()
        runCurrent()

        assertEquals(1, engine.resumes)
        assertTrue(controller.isActive)
    }

    @Test fun `seek is synchronous while requestSeek prefers the attached UI`() = runTest {
        val engine = FakeEngine()
        controller = newController(engine)

        controller.seek(1_000L)
        controller.requestSeek(2_000L)
        runCurrent()

        assertEquals(2_000L, engine.seekedTo)
        var requested: Long? = null
        controller.onSeek = { requested = it }
        controller.requestSeek(3_000L)

        assertEquals(3_000L, requested)
        assertEquals(2_000L, engine.seekedTo)
    }

    @Test fun `next and previous prefer the UI and fall back when it is gone`() = runTest {
        controller = newController(FakeEngine())
        var uiNext = 0
        var fallbackNext = 0
        var uiPrevious = 0
        var fallbackPrevious = 0
        controller.onNext = { uiNext++ }
        controller.fallbackNext = { fallbackNext++ }
        controller.onPrevious = { uiPrevious++ }
        controller.fallbackPrevious = { fallbackPrevious++ }

        controller.requestNext()
        controller.requestPrevious()

        assertEquals(1, uiNext)
        assertEquals(1, uiPrevious)
        assertEquals(0, fallbackNext)
        assertEquals(0, fallbackPrevious)

        controller.onNext = null
        controller.onPrevious = null
        controller.requestNext()
        controller.requestPrevious()

        assertEquals(1, fallbackNext)
        assertEquals(1, fallbackPrevious)
    }

    @Test fun `reconfigure reloads the source derived from the current item`() = runTest {
        val engine = FakeEngine()
        controller = newController(engine)
        controller.setNowPlaying(track(uri = "spotify:track:a"), 12_000L)

        controller.reconfigure()

        assertEquals("spotify:track:a", engine.reconfiguredSource?.uri)
        assertEquals(12_000L, engine.reconfiguredPosition)
        assertFalse(engine.reconfiguredWhilePlaying)
        assertTrue(engine.reconfigureResult)
        assertFalse(controller.isActive)
    }

    @Test fun `a reconfigure that was not rebuilt leaves the session state alone`() = runTest {
        val engine = FakeEngine().apply { reconfigureResult = false }
        controller = newController(engine)
        controller.setNowPlaying(track(uri = "spotify:track:a"), 0L)

        controller.reconfigure()

        assertFalse(controller.isActive)
        assertFalse(engine.reconfiguredWhilePlaying)
    }

    @Test fun `stop clears the slot and reports it to the queue owner`() = runTest {
        val engine = FakeEngine()
        controller = newController(engine)
        controller.setNowPlaying(track(uri = "spotify:track:a"), 0L, PlaybackSource("a", "spotify:track:a"))
        var stopped = 0
        controller.onStopped = { stopped++ }

        controller.stop()

        assertEquals(1, stopped)
        assertEquals(1, engine.stops)
        assertNull(controller.current)
        assertNull(controller.currentSource)
        assertFalse(controller.isActive)
    }

    @Test fun `stop can keep the queue for an app restart`() = runTest {
        val engine = FakeEngine()
        controller = newController(engine)
        controller.setNowPlaying(track(), 0L)
        var stopped = 0
        controller.onStopped = { stopped++ }

        controller.stop(keepQueue = true)

        assertEquals(0, stopped)
        assertEquals(1, engine.stops)
    }

    @Test fun `disconnect stops playback and drops the engine session`() = runTest {
        focusResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val engine = FakeEngine()
        controller = newController(engine, context = context)
        controller.start(MediaKey(ServiceId.Spotify, MediaType.Track, "a"), "spotify:track:a", 0L)
        assertNotNull(controller.sessionToken)

        controller.disconnect()

        assertEquals(1, engine.stops)
        assertEquals(1, engine.disconnects)
        assertFalse(controller.isActive)
    }

    @Test fun `a native event that contradicts the last command is ignored`() = runTest {
        focusResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        controller = newController(FakeEngine(), context = context)
        controller.start(MediaKey(ServiceId.Spotify, MediaType.Track, "a"), "spotify:track:a", 0L)

        assertTrue(controller.shouldApplyEvent(isPlaying = true))
        assertFalse(controller.shouldApplyEvent(isPlaying = false))

        controller.pause()

        assertTrue(controller.shouldApplyEvent(isPlaying = false))
        assertFalse(controller.shouldApplyEvent(isPlaying = true))
    }

    /** The clock the started controller reads; each case moves it to travel in time. */
    private var elapsed = 0L

    /**
     * Granted focus and track a started at 0 s. The two unconfirmed-resume cases below differ only
     * in when they move [elapsed], so they share this much of the setup.
     */
    private suspend fun TestScope.startedTrackA(): PlaybackController {
        focusResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        elapsed = 0L
        val playback = newController(FakeEngine(), context = context, elapsedTime = { elapsed })
        playback.setNowPlaying(track(), 0L, PlaybackSource("a", "spotify:track:a"))
        playback.start(MediaKey(ServiceId.Spotify, MediaType.Track, "a"), "spotify:track:a", 0L)
        assertTrue(playback.shouldApplyEvent(isPlaying = true))
        return playback
    }

    /**
     * Field log 2026-11-22 (SP-01): a resume that raced a silent rebuild load was followed by
     * librespot's `paused` for that load 2.5 s later, outside the old 2 s window, so the session
     * and the notification showed paused while audio was on its way. The resume stayed
     * unconfirmed for 8.5 s in that capture.
     */
    @Test fun `a late paused event cannot flip the UI while a resume is unconfirmed`() = runTest {
        val controller = startedTrackA()

        elapsed = 1_000L
        controller.pause()
        assertTrue(controller.shouldApplyEvent(isPlaying = false))

        elapsed = 2_000L
        assertTrue(controller.resume())
        elapsed = 4_500L
        assertFalse(
            "the rebuild's paused event must not override the resume the user just asked for",
            controller.shouldApplyEvent(isPlaying = false)
        )

        elapsed = 10_500L
        assertTrue("the player's playing event ends the wait", controller.shouldApplyEvent(isPlaying = true))
        assertTrue("a confirmed resume stops filtering", controller.shouldApplyEvent(isPlaying = false))
    }

    @Test fun `an unconfirmed command stops filtering after the deadline`() = runTest {
        val controller = startedTrackA()

        elapsed = 5_000L
        assertTrue(controller.resume())
        elapsed = 5_000L + 12_001L

        assertTrue(
            "a command the player never confirms must not silence events forever",
            controller.shouldApplyEvent(isPlaying = false)
        )
    }

    @Test fun `a stall asks the owner to recover and tells the UI`() = runTest {
        controller = newController(FakeEngine())
        controller.updateState(isPlaying = true, position = 5_000L)
        var recoveredAt: Long? = null
        controller.onStalled = { recoveredAt = it }

        controller.onAudioStalled(IllegalStateException("drain died"))
        runCurrent()

        assertEquals(5_000L, recoveredAt)
        assertFalse(controller.isActive)
        assertEquals("Reconnecting…", controller.notice.value)
    }

    @Test fun `a stall with no recovery path stops the engine`() = runTest {
        val engine = FakeEngine()
        controller = newController(engine)
        controller.updateState(isPlaying = true, position = 5_000L)

        controller.onAudioStalled(IllegalStateException("drain died"))
        runCurrent()

        assertEquals(1, engine.stops)
        assertEquals("Reconnecting…", controller.notice.value)
    }

    @Test fun `a stall while already paused changes nothing`() = runTest {
        val engine = FakeEngine()
        controller = newController(engine)
        var stopped = 0
        controller.onStalled = { stopped++ }

        controller.onAudioStalled(IllegalStateException("late failure"))
        runCurrent()

        assertEquals(0, stopped)
        assertEquals(0, engine.stops)
        assertNull(controller.notice.value)
    }

    @Test fun `the noisy broadcast pauses playback`() = runTest {
        focusResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val engine = FakeEngine()
        controller = newController(engine, context = context)
        controller.setNowPlaying(track(uri = "spotify:track:a"), 0L)
        controller.start(MediaKey(ServiceId.Spotify, MediaType.Track, "a"), "spotify:track:a", 0L)
        runCurrent()

        context.sendBroadcast(android.content.Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        runCurrent()

        assertFalse(controller.isActive)
        assertEquals(1, engine.pauses)
    }

    @Test fun `a transient focus loss pauses and returning focus resumes`() = runTest {
        focusResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val engine = FakeEngine()
        controller = newController(engine, context = context)
        controller.setNowPlaying(track(uri = "spotify:track:a"), 0L)
        controller.start(MediaKey(ServiceId.Spotify, MediaType.Track, "a"), "spotify:track:a", 0L)
        runCurrent()
        val listener = focusListener()

        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        runCurrent()
        assertFalse(controller.isActive)
        assertEquals(1, engine.pauses)

        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        runCurrent()

        assertTrue(controller.isActive)
        assertEquals(1, engine.resumes)
    }
}
