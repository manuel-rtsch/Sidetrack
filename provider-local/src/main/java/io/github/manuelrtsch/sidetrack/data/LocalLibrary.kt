package io.github.manuelrtsch.sidetrack.data

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.provider.MediaStore
import io.github.manuelrtsch.sidetrack.SidetrackDebug

/** One audio file exactly as the device reports it. */
data class LocalTrack(
    /** MediaStore's `_ID`; the only identity that survives a rescan. */
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    /** MediaStore's album id; an album is its `ALBUM_ID`, so two "Greatest Hits" stay apart. */
    val albumId: String,
    val durationMs: Long,
    val trackNumber: Int,
    val year: Int,
    /** The file path, which is what an `.m3u8` stores. */
    val path: String
)

/** A playlist the device already indexed — written by another app, or by a sync client. */
data class LocalSystemPlaylist(
    val id: String,
    val name: String,
    /** The audio `_ID`s in playlist order. */
    val audioIds: List<String>
)

/**
 * Everything the catalog needs from the device, behind an interface.
 *
 * The catalog is the interesting part — grouping, paging, search, playlist writes — and it is
 * tested against a fixture of this, so the MediaStore calls (which no unit test can make) stay in
 * one small, replaceable class.
 */
interface LocalLibrarySource {
    /** Every playable audio file, in whatever order the device reports. */
    fun tracks(): List<LocalTrack>

    /** Playlists the system already knows about, sorted by name. */
    fun playlists(): List<LocalSystemPlaylist>
}

/**
 * The real source: Android's media database, not a directory walk.
 *
 * The music directory is an implementation detail of where the files live; MediaStore is the
 * index over it, it is what scoped storage allows an app to read, and it carries the metadata
 * (album, artist, track number, duration) that a filesystem listing would have to re-derive by
 * tagging every file. A track's `_ID` is also the identity the queue and the resume point can
 * persist: a rescan may move the file, but it keeps the row.
 */
class MediaStoreLibrarySource(private val context: Context) : LocalLibrarySource {

    override fun tracks(): List<LocalTrack> = query(
        uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        columns = TRACK_COLUMNS,
        selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.IS_PENDING} = 0",
        sortOrder = "${MediaStore.Audio.Media.ALBUM}, ${MediaStore.Audio.Media.TRACK}, ${MediaStore.Audio.Media.TITLE}"
    ) { cursor ->
        val id = cursor.string(MediaStore.Audio.Media._ID)
        val path = cursor.string(MediaStore.Audio.Media.DATA)
        LocalTrack(
            id = id,
            title = cursor.string(MediaStore.Audio.Media.TITLE).ifBlank { path.substringAfterLast('/') },
            artist = cursor.string(MediaStore.Audio.Media.ARTIST).unknownTo("Unknown artist"),
            album = cursor.string(MediaStore.Audio.Media.ALBUM).unknownTo("Unknown album"),
            albumId = cursor.string(MediaStore.Audio.Media.ALBUM_ID),
            durationMs = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)),
            trackNumber = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)),
            year = cursor.optInt(MediaStore.Audio.Media.YEAR),
            path = path
        )
    }.filter { it.id.isNotEmpty() && it.durationMs > 0L }

    override fun playlists(): List<LocalSystemPlaylist> = query(
        uri = MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI,
        columns = arrayOf(MediaStore.Audio.Playlists._ID, MediaStore.Audio.Playlists.NAME),
        selection = null,
        sortOrder = "${MediaStore.Audio.Playlists.NAME} COLLATE NOCASE"
    ) { cursor ->
        LocalSystemPlaylist(
            id = cursor.string(MediaStore.Audio.Playlists._ID),
            name = cursor.string(MediaStore.Audio.Playlists.NAME),
            audioIds = members(cursor.string(MediaStore.Audio.Playlists._ID))
        )
    }.filter { it.id.isNotEmpty() && it.name.isNotEmpty() }

    /** The `_ID`s of a system playlist, in the order it plays them. */
    private fun members(playlistId: String): List<String> {
        val id = playlistId.toLongOrNull() ?: return emptyList()
        return query(
            uri = MediaStore.Audio.Playlists.Members.getContentUri("external", id),
            columns = arrayOf(MediaStore.Audio.Playlists.Members.AUDIO_ID),
            selection = null,
            sortOrder = MediaStore.Audio.Playlists.Members.PLAY_ORDER
        ) { it.string(MediaStore.Audio.Playlists.Members.AUDIO_ID) }.filter { it.isNotEmpty() }
    }

    /**
     * Runs one query and maps every row. A provider that is not mounted, a revoked permission or a
     * database the platform refuses reads as "no music" instead of taking a screen down.
     */
    private fun <T> query(
        uri: android.net.Uri,
        columns: Array<String>,
        selection: String?,
        sortOrder: String?,
        map: (Cursor) -> T
    ): List<T> = runCatching {
        context.contentResolver.query(uri, columns, selection, null, sortOrder)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(map(cursor))
            }
        }.orEmpty()
    }.onFailure {
        SidetrackDebug.log("local-library query failed for $uri: ${it.message}")
    }.getOrDefault(emptyList())

    private fun Cursor.string(column: String): String = getString(getColumnIndexOrThrow(column)).orEmpty()

    private fun Cursor.optInt(column: String): Int = getColumnIndex(column).takeIf { it >= 0 }?.let { getInt(it) } ?: 0

    private companion object {
        val TRACK_COLUMNS = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DATA
        )
    }
}

/** MediaStore writes `<unknown>` literally when a tag is missing; the UI should not say that. */
internal fun String.unknownTo(fallback: String): String = if (isBlank() || this == MediaStore.UNKNOWN_STRING) fallback else this
