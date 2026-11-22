package io.github.manuelrtsch.sidetrack.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import hrln.interactive.sidekit.sdk.media.RemoteArtwork
import hrln.interactive.sidekit.sdk.media.Thumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bitmaps for whichever kind of artwork address a provider mints.
 *
 * Server providers use an `http(s)` URL, and Sidekit's [RemoteArtwork] fetches it. A local library
 * has no network artwork at all: it points at MediaProvider's album-art endpoint, a `content://`
 * URI that never leaves the device. Both paths answer the same call, so the caller only names the
 * address.
 */
object ArtworkImages {
    /** True for artwork the device holds, which must never be fetched over the network. */
    fun isContentUri(url: String): Boolean = url.startsWith("content://")

    /** Loads [url] cropped to [w] x [h] px, or null when it has no artwork or cannot be read. */
    suspend fun load(
        context: Context,
        url: String,
        w: Int,
        h: Int,
        headers: Map<String, String> = emptyMap()
    ): Bitmap? = if (isContentUri(url)) {
        loadContent(context, Uri.parse(url), w, h)
    } else {
        RemoteArtwork.load(context, url, w, h, headers)
    }

    /**
     * MediaProvider's thumbnail first — it is already decoded and cached — then the artwork URI
     * itself, because the album-art endpoint answers `openInputStream` but not `loadThumbnail` on
     * every OEM. Either way the bitmap is sampled down to roughly the drawn size.
     */
    private suspend fun loadContent(context: Context, uri: Uri, w: Int, h: Int): Bitmap? {
        runCatching { Thumbnails.load(context, uri, w, h) }.getOrNull()?.let { return it }
        return withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            }.getOrNull()
        }
    }
}
