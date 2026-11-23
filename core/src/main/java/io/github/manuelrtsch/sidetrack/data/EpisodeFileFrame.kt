package io.github.manuelrtsch.sidetrack.data

import androidx.core.util.AtomicFile
import io.github.manuelrtsch.sidetrack.ServiceId
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * The record frame shared by [FileEpisodeMetaStore] and [FileEpisodeProgressStore].
 *
 * Both files are a magic, a version, a count, then that many records opening with a service and an
 * episode id; they differ only in the fields behind that prefix. Keeping one frame means the
 * atomic replace, the version gate and the "an unreadable file is an empty cache" rule cannot land
 * on one store and miss the other, which is the pair of behaviours most expensive to get wrong.
 */
internal object EpisodeFileFrame {
    private const val VERSION_1 = 1
    private const val VERSION_2 = 2

    /**
     * Reads the frame. [body] reads the fields behind the shared prefix and is told the id, so a
     * record is never built twice. A truncated or unreadable file reads as empty: a lost cache is
     * recoverable on the next fetch, a thrown read is not.
     */
    fun <T> read(
        file: AtomicFile,
        magic: Int,
        maxEntries: Int,
        label: String,
        body: (DataInputStream, ServiceId, String) -> T
    ): Map<String, T> {
        val stream = try {
            file.openRead()
        } catch (_: IOException) {
            return emptyMap()
        }
        return try {
            DataInputStream(BufferedInputStream(stream)).use { input ->
                if (input.readInt() != magic) return emptyMap()
                val version = input.readInt()
                if (version != VERSION_1 && version != VERSION_2) return emptyMap()
                val count = input.readInt()
                if (count !in 0..maxEntries) throw IOException("Invalid $label count $count")
                buildMap {
                    repeat(count) {
                        // Every write since version 2 names a service; version 1 files predate the
                        // multi-provider library and can only be Spotify.
                        val service = if (version >= VERSION_2) ServiceId(input.readUTF()) else ServiceId.Spotify
                        val id = input.readUTF()
                        val entry = body(input, service, id)
                        if (id.isNotEmpty()) put(id, entry)
                    }
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Writes the frame, newest first and capped at [maxEntries]. */
    fun <T> write(
        file: AtomicFile,
        magic: Int,
        maxEntries: Int,
        entries: Map<String, T>,
        id: (T) -> String,
        service: (T) -> ServiceId,
        updatedAt: (T) -> Long,
        body: (DataOutputStream, T) -> Unit
    ) {
        val out = file.startWrite()
        try {
            val data = DataOutputStream(BufferedOutputStream(out))
            data.writeInt(magic)
            data.writeInt(VERSION_2)
            val values = entries.values.sortedByDescending(updatedAt).take(maxEntries)
            data.writeInt(values.size)
            values.forEach { entry ->
                data.writeUTF(service(entry).value)
                data.writeUTF(id(entry))
                body(data, entry)
            }
            data.flush()
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }
}
