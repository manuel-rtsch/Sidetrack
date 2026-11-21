package io.github.manuelrtsch.sidetrack.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Both the size read and the clear walk the tree, so these pin what counts and what is dropped. */
@RunWith(RobolectricTestRunner::class)
class CacheDirectoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `size counts file bytes only and clear drops the whole tree`() {
        val artwork = CacheDirectory.artwork(context).apply { mkdirs() }
        File(artwork, "cover").writeBytes(ByteArray(64))
        val nested = File(artwork, "nested").apply { mkdirs() }
        File(nested, "thumb").writeBytes(ByteArray(36))

        // Directories carry no bytes of their own, so only the two files count.
        assertEquals(100L, CacheDirectory.sizeBytes(artwork))

        CacheDirectory.clear(artwork)

        assertFalse(artwork.exists())
        assertEquals(0L, CacheDirectory.sizeBytes(artwork))
    }

    @Test fun `missing and absent directories count as nothing`() {
        val missing = File(context.cacheDir, "no-such-cache")
        val nothing: File? = null

        assertEquals(0L, CacheDirectory.sizeBytes(missing))
        assertEquals(0L, CacheDirectory.sizeBytes(nothing))
        assertEquals(
            64L,
            CacheDirectory.sizeBytes(
                missing,
                File(context.cacheDir, "artwork").apply {
                    mkdirs()
                    File(this, "cover").writeBytes(ByteArray(64))
                }
            )
        )

        // Clearing nothing is not an error; sign-out calls this whether a cache exists or not.
        CacheDirectory.clear(nothing)
        CacheDirectory.clear(missing)
    }
}
