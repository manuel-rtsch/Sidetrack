package io.github.manuelrtsch.sidetrack

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.manuelrtsch.sidetrack.data.EpisodeMeta
import io.github.manuelrtsch.sidetrack.data.FileEpisodeMetaStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The metadata store shares its record frame with [io.github.manuelrtsch.sidetrack.data.FileEpisodeProgressStore],
 * so these cover what that store's tests cannot: the nullable description, the `episodeNumber ?: 0`
 * encoding and the service that only version 2 files carry.
 */
@RunWith(RobolectricTestRunner::class)
class EpisodeMetaStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `episode metadata survives a round trip`() {
        val file = tempFile("episode-meta")
        val entries = mapOf(
            "lw5" to EpisodeMeta(
                episodeId = "lw5",
                description = "Notes with an ümlaut and a \"quote\"",
                episodeNumber = 412,
                explicit = true,
                updatedAtMs = 1_700_000_000_000L,
                service = ServiceId.Plex
            )
        )

        FileEpisodeMetaStore(file).write(entries)

        assertEquals(entries, FileEpisodeMetaStore(file).read())
    }

    @Test fun `a missing episode number round trips as null`() {
        val file = tempFile("episode-meta-no-number")
        FileEpisodeMetaStore(file).write(
            mapOf(
                "lw5" to EpisodeMeta("lw5", "Notes", episodeNumber = null, explicit = false, updatedAtMs = 1_000L)
            )
        )

        assertEquals(null, FileEpisodeMetaStore(file).read()["lw5"]?.episodeNumber)
    }

    @Test fun `an empty description round trips as empty`() {
        val file = tempFile("episode-meta-no-description")
        FileEpisodeMetaStore(file).write(
            mapOf(
                "lw5" to EpisodeMeta(
                    episodeId = "lw5",
                    description = "",
                    episodeNumber = 7,
                    explicit = false,
                    updatedAtMs = 1_000L
                )
            )
        )

        assertEquals("", FileEpisodeMetaStore(file).read()["lw5"]?.description)
    }

    @Test fun `a corrupt metadata file starts empty`() {
        val file = tempFile("episode-meta-corrupt").also { it.writeBytes(byteArrayOf(1, 2, 3, 4)) }

        assertEquals(emptyMap<String, EpisodeMeta>(), FileEpisodeMetaStore(file).read())
    }

    @Test fun `a file from another store is not read as metadata`() {
        val file = tempFile("episode-meta-wrong-magic")
        // "SKTP" is the progress store's magic: same frame, different file.
        file.writeBytes(byteArrayOf(0x53, 0x4B, 0x54, 0x50, 0, 0, 0, 2, 0, 0, 0, 0))

        assertEquals(emptyMap<String, EpisodeMeta>(), FileEpisodeMetaStore(file).read())
    }

    private fun tempFile(name: String): File = File(context.cacheDir, "sidetrack-$name-${System.nanoTime()}.bin").also { it.delete() }
}
