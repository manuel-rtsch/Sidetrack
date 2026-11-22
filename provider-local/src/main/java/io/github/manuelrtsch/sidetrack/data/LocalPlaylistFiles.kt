package io.github.manuelrtsch.sidetrack.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import io.github.manuelrtsch.sidetrack.SidetrackDebug
import java.util.UUID

/**
 * A playlist Sidetrack owns as an `.m3u8` file in the device's music directory.
 *
 * [id] is a token written into the file itself, so renaming the file keeps every open page, queue
 * and cached row pointing at the same playlist. A file that carries no token (one another app
 * wrote into the same folder) is identified by its file name instead.
 */
data class LocalPlaylistFile(
    val id: String,
    val name: String,
    /** Absolute file paths in playlist order — the one thing every m3u8 reader agrees on. */
    val entries: List<String>
)

/**
 * Playlists that live on the device as files.
 *
 * System playlists (the ones `MediaStore.Audio.Playlists` holds) belong to the media database and
 * are read-only here: since Android 11 a playlist row can only be changed by the app that created
 * it, and membership writes are unreliable across OEMs. A file Sidetrack created is ours to
 * rewrite, rename and delete, and every other player on the phone can read it too.
 */
interface LocalPlaylistFiles {
    fun list(): List<LocalPlaylistFile>
    fun create(name: String, entries: List<String>): LocalPlaylistFile
    fun save(id: String, entries: List<String>)
    fun rename(id: String, name: String)
    fun delete(id: String)
}

/**
 * The `.m3u8` sidecar format.
 *
 * Plain UTF-8 m3u8, the one Poweramp, VLC and every sync tool already read: `#EXTINF` for the
 * display line, one absolute path per entry. The only addition is a `#SIDETRACK-ID:` comment,
 * which readers that do not know it skip.
 */
internal object LocalPlaylistFormat {
    const val HEADER = "#EXTM3U"
    private const val ID_TAG = "#SIDETRACK-ID:"

    fun encode(id: String, entries: List<String>): String = buildString {
        appendLine(HEADER)
        appendLine("$ID_TAG$id")
        entries.forEach { path ->
            appendLine("#EXTINF:-1,${path.substringAfterLast('/')}")
            appendLine(path)
        }
    }

    /** Parses [text]; [fallbackId] identifies a file that carries no Sidetrack token. */
    fun decode(text: String, fallbackId: String): Pair<String, List<String>> {
        var id = fallbackId
        val entries = mutableListOf<String>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim().removePrefix("\uFEFF").removeSuffix("\r")
            when {
                line.isEmpty() -> Unit
                line.startsWith(ID_TAG, ignoreCase = true) -> id = line.substring(ID_TAG.length).trim().ifEmpty { fallbackId }
                line.startsWith("#") -> Unit
                else -> entries += line
            }
        }
        return id to entries
    }
}

/**
 * Writes those files through MediaStore, into `Music/Sidetrack`.
 *
 * Nothing here needs a storage write permission: an app may create files in a shared collection
 * without one and keeps full access to what it created. That is also why the folder is fixed —
 * asking the user for a folder through SAF would add a picker to a screen that has no business
 * having one.
 */
class MediaStorePlaylistFiles(private val context: Context) : LocalPlaylistFiles {

    override fun list(): List<LocalPlaylistFile> = rows().mapNotNull { row -> read(row) }

    override fun create(name: String, entries: List<String>): LocalPlaylistFile {
        val id = UUID.randomUUID().toString()
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileNameFor(name))
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(collection(), values)
            ?: throw ServiceException(ServiceFailure.Unavailable, "The device did not create the playlist file")
        write(uri, id, entries)
        val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        context.contentResolver.update(uri, published, null, null)
        return LocalPlaylistFile(id, name.trim(), entries)
    }

    override fun save(id: String, entries: List<String>) {
        val row = rows().firstOrNull { read(it)?.id == id }
            ?: throw ServiceException(ServiceFailure.TrackUnavailable, "This playlist file is gone")
        write(row.uri, id, entries)
    }

    override fun rename(id: String, name: String) {
        val row = rows().firstOrNull { read(it)?.id == id }
            ?: throw ServiceException(ServiceFailure.TrackUnavailable, "This playlist file is gone")
        val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, fileNameFor(name)) }
        context.contentResolver.update(row.uri, values, null, null)
    }

    override fun delete(id: String) {
        val row = rows().firstOrNull { read(it)?.id == id }
            ?: throw ServiceException(ServiceFailure.TrackUnavailable, "This playlist file is gone")
        context.contentResolver.delete(row.uri, null, null)
    }

    private data class Row(val uri: Uri, val displayName: String)

    private fun rows(): List<Row> = runCatching {
        context.contentResolver.query(
            collection(),
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.MIME_TYPE} = ?",
            arrayOf("$FOLDER%", MIME_TYPE),
            "${MediaStore.MediaColumns.DISPLAY_NAME} COLLATE NOCASE"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            buildList {
                while (cursor.moveToNext()) {
                    add(Row(Uri.withAppendedPath(collection(), cursor.getLong(idColumn).toString()), cursor.getString(nameColumn).orEmpty()))
                }
            }
        }.orEmpty()
    }.onFailure {
        SidetrackDebug.log("local-playlists query failed: ${it.message}")
    }.getOrDefault(emptyList())

    /** Reads one row, or null when the file vanished between the query and the read. */
    private fun read(row: Row): LocalPlaylistFile? = runCatching {
        val text = context.contentResolver.openInputStream(row.uri)?.use { it.readBytes().decodeToString() } ?: return null
        val (id, entries) = LocalPlaylistFormat.decode(text, fallbackId = "name:${row.displayName}")
        LocalPlaylistFile(id = id, name = nameFor(row.displayName), entries = entries)
    }.onFailure {
        SidetrackDebug.log("local-playlists read failed for ${row.displayName}: ${it.message}")
    }.getOrNull()

    private fun write(uri: Uri, id: String, entries: List<String>) {
        val stream = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw ServiceException(ServiceFailure.Unavailable, "The device did not open the playlist file")
        stream.use { it.write(LocalPlaylistFormat.encode(id, entries).toByteArray()) }
    }

    private companion object {
        const val MIME_TYPE = "audio/x-mpegurl"
        const val FOLDER = "Music/Sidetrack/"
        const val EXTENSION = ".m3u8"

        fun collection(): Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        /** A file name the storage layer accepts: no path separators, no reserved characters. */
        fun fileNameFor(name: String): String {
            val cleaned = name.trim()
                .replace(Regex("[/\\\\:*?\"<>|\\u0000-\\u001F]"), "-")
                .trim('.', ' ')
                .ifEmpty { "Playlist" }
            return if (cleaned.endsWith(EXTENSION, ignoreCase = true)) cleaned else "$cleaned$EXTENSION"
        }

        fun nameFor(displayName: String): String = displayName.removeSuffix(EXTENSION).ifEmpty { displayName }
    }
}
