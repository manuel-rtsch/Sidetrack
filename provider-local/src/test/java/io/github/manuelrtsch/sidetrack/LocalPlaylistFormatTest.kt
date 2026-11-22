package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.LocalPlaylistFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `.m3u8` sidecar, which is the only place a device playlist survives outside MediaStore.
 * Other players read these files, so the format has to stay a plain m3u8 with one extra comment.
 */
class LocalPlaylistFormatTest {

    @Test fun `a written playlist reads back with the same token and entries`() {
        val text = LocalPlaylistFormat.encode("token-1", listOf("/music/a.mp3", "/music/b.mp3"))

        assertTrue(text.startsWith("#EXTM3U"))
        val (id, entries) = LocalPlaylistFormat.decode(text, fallbackId = "name:file.m3u8")
        assertEquals("token-1", id)
        assertEquals(listOf("/music/a.mp3", "/music/b.mp3"), entries)
    }

    @Test fun `a playlist another app wrote keeps its file name as identity`() {
        val text = "#EXTM3U\n#EXTINF:120,Some Artist - Some Title\n/storage/emulated/0/Music/some.mp3\n"

        val (id, entries) = LocalPlaylistFormat.decode(text, fallbackId = "name:Someone else's mix.m3u8")

        assertEquals("name:Someone else's mix.m3u8", id)
        assertEquals(listOf("/storage/emulated/0/Music/some.mp3"), entries)
    }

    @Test fun `a byte-order mark, CRLF and blank lines do not corrupt the entries`() {
        val text = "\uFEFF#EXTM3U\r\n\r\n# a comment\r\n/music/a.mp3\r\n/music/b b.mp3\r\n"

        val (_, entries) = LocalPlaylistFormat.decode(text, fallbackId = "name:x.m3u8")

        assertEquals(listOf("/music/a.mp3", "/music/b b.mp3"), entries)
    }

    @Test fun `an empty token falls back instead of producing an unusable identity`() {
        val (id, entries) = LocalPlaylistFormat.decode("#EXTM3U\n#SIDETRACK-ID:   \n/music/a.mp3\n", fallbackId = "name:x.m3u8")

        assertEquals("name:x.m3u8", id)
        assertEquals(listOf("/music/a.mp3"), entries)
    }

    @Test fun `an empty playlist round-trips as empty`() {
        val text = LocalPlaylistFormat.encode("empty", emptyList())

        val (id, entries) = LocalPlaylistFormat.decode(text, fallbackId = "name:x.m3u8")
        assertEquals("empty", id)
        assertTrue(entries.isEmpty())
    }
}
