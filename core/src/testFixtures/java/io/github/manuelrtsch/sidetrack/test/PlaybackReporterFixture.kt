package io.github.manuelrtsch.sidetrack.test

import io.github.manuelrtsch.sidetrack.ServiceId
import io.github.manuelrtsch.sidetrack.Track
import io.github.manuelrtsch.sidetrack.playback.PlaybackEngineState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The state a [io.github.manuelrtsch.sidetrack.data.PlaybackReporter] reads: one 200 s track, the
 * player state it is reported from, and a position and clock the test moves by hand.
 *
 * The reporter has one implementation in `:core` and a per-provider subclass that only sends, so
 * the tests of both drive the same state. Only the reporting destination differs, which is the one
 * thing a test overrides.
 */
open class PlaybackReporterFixture {
    protected val state = MutableStateFlow(PlaybackEngineState())
    protected val ended = MutableSharedFlow<String?>(extraBufferCapacity = 1)
    protected var position = 0L
    protected var now = 1_000L

    protected val item = Track(
        id = "t1",
        title = "Night Signals",
        artist = "Aurora Vale",
        artistId = "ar1",
        albumId = "a1",
        artRes = 0,
        durationMs = 200_000L,
        service = ServiceId.Jellyfin
    )
}
