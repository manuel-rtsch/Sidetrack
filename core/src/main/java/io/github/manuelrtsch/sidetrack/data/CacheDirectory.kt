package io.github.manuelrtsch.sidetrack.data

import android.content.Context
import java.io.File

/**
 * The on-disk directories behind [CacheRuntime].
 *
 * A populated artwork or audio cache holds thousands of files, so both the size read and the
 * clear walk the whole tree and turn into thousands of `stat` calls. Callers dispatch to
 * `Dispatchers.IO`; this object only owns which directories count and how their bytes are summed,
 * so every provider reports the same number for the same cache.
 */
object CacheDirectory {
    /** The cache Sidekit's `RemoteArtwork` fills with covers, shared by all services. */
    fun artwork(context: Context): File = File(context.cacheDir, "artwork")

    /** Bytes held by [dirs], counting files only. */
    fun sizeBytes(vararg dirs: File?): Long = dirs.sumOf { it?.sizeBytes() ?: 0L }

    /** Drops the whole tree; anything still needed is fetched again. */
    fun clear(dir: File?) {
        dir?.deleteRecursively()
    }

    private fun File.sizeBytes(): Long = walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
