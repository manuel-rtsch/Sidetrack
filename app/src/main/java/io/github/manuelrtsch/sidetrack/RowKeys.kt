package io.github.manuelrtsch.sidetrack

// One row identity for every list. The lazy-list key, the gutter/focus key a row publishes and the
// key the viewport state stores are the same string, so a row keeps its identity — and its cursor —
// across an insertion, a deletion or a page of history. The identity is the service-scoped
// MediaKey: an id alone is only unique within its type and its service.

/** The identity of a row; anything that is not a [MediaItem] falls back to its own string form. */
internal fun mediaRowKey(item: Any): String = when (item) {
    is MediaItem -> item.key.encode()
    else -> item.toString()
}

/**
 * Row identities in display order.
 *
 * A track can legitimately appear twice in history or in a playlist, so a repeated identity
 * carries its occurrence rank. The first occurrence keeps the plain key, which keeps a list with
 * no duplicates identical to [mediaRowKey] and leaves the category viewports' row lookup working
 * unchanged. Compute once per list with `remember(items)` rather than formatting a string per row
 * and again in the body.
 */
internal fun mediaRowKeys(items: List<Any>): List<String> {
    val seen = HashMap<String, Int>(items.size)
    return items.map { item ->
        val key = mediaRowKey(item)
        val rank = seen[key] ?: 0
        seen[key] = rank + 1
        if (rank == 0) key else "$key#$rank"
    }
}

/** What kind of row this is, for the lazy list's content-type reuse. */
internal fun mediaContentType(item: Any): String = when (item) {
    is Track -> "track"
    is Episode -> "episode"
    else -> "media"
}

/**
 * Row identities for a list the user can reorder.
 *
 * [mediaRowKeys] ranks duplicates by their position, so moving one of two identical tracks past
 * the other renames both rows: the moving row's lazy-list key changes, its node is replaced, and a
 * drag in flight loses its gesture (and its lift). These keys are handed out once per entry, in
 * appearance order, and moved with [move] when the data moves, so a reorder never renames a row.
 * The entry's [mediaRowKey] stays in the key to keep it recognizable.
 */
internal class StableRowKeys {
    private val ids = ArrayList<Long>()
    private var nextId = 0L

    /** Keys for [items], aligned by position; new entries get an id the first time they appear. */
    fun keys(items: List<Any>): List<String> {
        while (ids.size < items.size) ids.add(nextId++)
        while (ids.size > items.size) ids.removeAt(ids.lastIndex)
        return items.mapIndexed { index, item -> "${mediaRowKey(item)}#${ids[index]}" }
    }

    /** Mirrors the queue's move: remove at [from], insert at [to], clamped like the data. */
    fun move(from: Int, to: Int) {
        if (from !in ids.indices) return
        val target = to.coerceIn(0, ids.lastIndex)
        if (from == target) return
        ids.add(target, ids.removeAt(from))
    }
}
