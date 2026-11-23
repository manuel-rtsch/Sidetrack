package io.github.manuelrtsch.sidetrack

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The transport has a phase for "a start is in flight" and one for "it never started".
 *
 * The screen used to claim to play from the moment a source was requested; these pin the window
 * where nothing is audible yet and the watchdog that ends it instead of spinning forever.
 */
@RunWith(AndroidJUnit4::class)
class TransportStateTest {

    private val store = UiStateStore(SidetrackUiState())

    @Test
    fun `a start that never becomes audio fails after the watchdog`() = runTest {
        val transport = TransportState(store, this)
        var timedOut = 0

        transport.starting { timedOut++ }

        assertEquals(Transport.Starting, store.value.transport)
        advanceTimeBy(TransportState.START_WATCHDOG_MS + 1)
        runCurrent()
        assertEquals(1, timedOut)
        assertEquals(Transport.Failed(TransportState.START_FAILED_MESSAGE), store.value.transport)
    }

    @Test
    fun `playing before the watchdog settles the start and cancels it`() = runTest {
        val transport = TransportState(store, this)
        var timedOut = 0

        transport.starting { timedOut++ }
        transport.playing()
        advanceTimeBy(TransportState.START_WATCHDOG_MS + 1)
        runCurrent()

        assertEquals(0, timedOut)
        assertEquals(Transport.Playing, store.value.transport)
    }

    @Test
    fun `buffering is a start that is being fetched, and it settles when the fetch ends`() = runTest {
        val transport = TransportState(store, this)

        transport.starting {}
        transport.buffering(true)
        assertEquals(Transport.Buffering, store.value.transport)

        transport.buffering(false)
        assertEquals(Transport.Playing, store.value.transport)
    }

    @Test
    fun `a paused item does not turn buffering into playing`() = runTest {
        val transport = TransportState(store, this)

        transport.paused()
        transport.buffering(true)
        transport.buffering(false)

        assertEquals(Transport.Paused, store.value.transport)
    }

    @Test
    fun `a queue write states its intent and leaves the phase alone when it has none`() = runTest {
        val transport = TransportState(store, this)

        transport.intent(true)
        assertEquals(Transport.Playing, store.value.transport)

        transport.intent(null)
        assertEquals(Transport.Playing, store.value.transport)

        transport.intent(false)
        assertEquals(Transport.Idle, store.value.transport)
    }

    @Test
    fun `a restored player adopts what it is doing`() = runTest {
        val transport = TransportState(store, this)

        transport.restored(true)
        assertEquals(Transport.Playing, store.value.transport)

        transport.restored(false)
        assertEquals(Transport.Idle, store.value.transport)
    }

    @Test
    fun `starting counts as playing for the clock, buffering only as buffering`() = runTest {
        val transport = TransportState(store, this)

        transport.starting {}
        assertEquals(true, store.value.isPlaying)
        assertEquals(false, store.value.isBuffering)

        transport.buffering(true)
        assertEquals(true, store.value.isPlaying)
        assertEquals(true, store.value.isBuffering)
    }
}
