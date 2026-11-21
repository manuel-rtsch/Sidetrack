package io.github.manuelrtsch.sidetrack.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Two services signed in at once must never share a queue, history or resume file, so the names
 * carry the service key. These pin the shape the shipped builds already wrote to disk.
 */
class StoreNamesTest {
    @Test fun `the derived names carry the service key`() {
        val jellyfin = StoreNames.forService("jellyfin")

        assertEquals("sidetracks_settings_jellyfin", jellyfin.settings)
        assertEquals("sidetracks-jellyfin-queue.bin", jellyfin.queue)
        assertEquals("sidetracks-jellyfin-history.bin", jellyfin.history)
        assertEquals("sidetracks-jellyfin-resume.bin", jellyfin.resume)
    }

    @Test fun `two services never share a file`() {
        val jellyfin = StoreNames.forService("jellyfin")
        val plex = StoreNames.forService("plex")

        assertNotEquals(jellyfin, plex)
        assertNotEquals(jellyfin.queue, plex.queue)
        assertNotEquals(jellyfin.resume, plex.resume)
    }
}
