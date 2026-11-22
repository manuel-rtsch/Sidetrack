package io.github.manuelrtsch.sidetrack.data

import androidx.core.util.AtomicFile
import java.io.File
import java.io.IOException

/**
 * The device has no account, so "liked" is a list this app keeps for itself.
 *
 * The entries are MediaStore ids, not titles: a track that is re-tagged or moved keeps its row and
 * stays liked, and one that is deleted simply stops resolving. Insertion order is kept, so the
 * Liked section reads newest first in the order the tracks were saved.
 */
interface LocalFavorites {
    /** Liked track ids, most recently saved last. */
    fun ids(): List<String>
    fun contains(id: String): Boolean
    fun set(id: String, saved: Boolean)
    fun setAll(ids: Collection<String>, saved: Boolean)
}

/** The stored form: one id per line, replaced atomically, unreadable as "nothing liked". */
class FileLocalFavorites(private val file: File) : LocalFavorites {
    private val atomic = AtomicFile(file)

    @Volatile
    private var cached: LinkedHashSet<String>? = null

    override fun ids(): List<String> = read().toList()

    override fun contains(id: String): Boolean = read().contains(id)

    override fun set(id: String, saved: Boolean) {
        if (id.isEmpty()) return
        val current = read()
        if (saved == current.contains(id)) return
        write(if (saved) LinkedHashSet(current).apply { add(id) } else LinkedHashSet(current).apply { remove(id) })
    }

    override fun setAll(ids: Collection<String>, saved: Boolean) {
        val current = read()
        val next = if (saved) {
            current.apply { ids.filter { it.isNotEmpty() }.forEach { add(it) } }
        } else {
            current.apply { ids.forEach { remove(it) } }
        }
        write(next)
    }

    private fun read(): LinkedHashSet<String> = cached ?: runCatching {
        LinkedHashSet(atomic.openRead().bufferedReader().use { reader -> reader.readLines().filter { it.isNotBlank() } })
    }.onFailure {
        if (it !is IOException) throw it
    }.getOrDefault(LinkedHashSet()).also { cached = it }

    private fun write(next: LinkedHashSet<String>) {
        val out = atomic.startWrite()
        try {
            out.bufferedWriter().use { writer ->
                next.forEach { writer.append(it).append('\n') }
            }
            atomic.finishWrite(out)
            cached = LinkedHashSet(next)
        } catch (e: Exception) {
            atomic.failWrite(out)
            throw e
        }
    }
}
