package io.github.manuelrtsch.sidetrack.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaFormatTest {
    @Test
    fun `list durations keep minutes and seconds through 98 minutes`() {
        assertEquals("0:00", formatListDuration(0))
        assertEquals("3:07", formatListDuration(187_000))
        assertEquals("59:59", formatListDuration(3_599_000))
        assertEquals("62:45", formatListDuration(3_765_000))
        assertEquals("98:59", formatListDuration(5_939_000))
    }

    @Test
    fun `list durations switch to hours and minutes at 99 minutes`() {
        assertEquals("1h 39m", formatListDuration(5_940_000))
        assertEquals("1h 40m", formatListDuration(6_000_000))
        assertEquals("10h 05m", formatListDuration(36_300_000))
    }

    @Test
    fun `playback durations keep seconds past an hour`() {
        assertEquals("1:02:45", formatDuration(3_765_000))
    }
}
