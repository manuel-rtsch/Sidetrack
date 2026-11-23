package io.github.manuelrtsch.sidetrack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A station screen asks for a load, not for "has an error appeared yet".
 *
 * The screens used to read `pending || error == null`, so an attempt that ended without a station
 * and without an error — cancelled by sign-out, superseded by the next press — left the record
 * turning forever. The attempt's seed or artist is what says it is over.
 */
class RadioStationStateTest {

    private val station = Collection(
        type = CollectionType.Playlist,
        id = "radio:seed",
        title = "Song radio",
        subtitle = "Based on Seed · 2 tracks",
        artRes = 0,
        items = emptyList()
    )

    @Test
    fun aStationWaitsBeforeItsRequestIsStamped() {
        // The first frame renders before the LaunchedEffect asks for the station: the seed is
        // still unknown, so this is a load rather than an empty screen.
        val state = RadioUiState().songStation("seed")

        assertNull(state.station)
        assertTrue(state.loading)
        assertNull(state.error)
    }

    @Test
    fun aRunningRequestIsLoading() {
        val state = RadioUiState(radioSeedId = "seed", radioPending = true).songStation("seed")

        assertTrue(state.loading)
    }

    @Test
    fun aStampedSeedWithoutAStationSettlesOnMissing() {
        // The request was cancelled after it stamped its seed: nothing will ever write an error,
        // so waiting on one more frame would never end.
        val state = RadioUiState(radioSeedId = "seed").songStation("seed")

        assertNull(state.station)
        assertFalse(state.loading)
        assertNull(state.error)
    }

    @Test
    fun aFailureIsReportedInsteadOfTheLoad() {
        val state = RadioUiState(radioSeedId = "seed", radioError = "No similar tracks found")
            .songStation("seed")

        assertEquals("No similar tracks found", state.error)
        assertFalse(state.loading)
    }

    @Test
    fun aStationIsThisScreensWhenItBelongsToItsSeed() {
        val state = RadioUiState(radio = station, radioSeedId = "seed").songStation("seed")

        assertEquals(station, state.station)
        assertFalse(state.loading)
        assertNull(state.error)
    }

    @Test
    fun anotherArtistsStationAndErrorAreNotOurs() {
        val state = RadioUiState(
            artistRadio = station,
            artistRadioArtistId = "other",
            artistRadioError = "Couldn't reach the catalog"
        ).artistStation("artist")

        assertNull(state.station)
        // The screen is about to ask for its own artist; showing the previous artist's failure
        // would blame the wrong request.
        assertNull(state.error)
        assertTrue(state.loading)
    }

    @Test
    fun aSettledArtistRequestStopsSpinning() {
        val state = RadioUiState(artistRadioArtistId = "artist").artistStation("artist")

        assertNull(state.station)
        assertFalse(state.loading)
        assertNull(state.error)
    }
}
