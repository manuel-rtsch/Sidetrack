package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.Saver

// The pure half of the library's reading position: which row each category was left at, saved as
// flat strings so a process restart keeps the cache. No composition state lives here beyond the
// restored map itself.

/**
 * Where a category was left: its first visible row and the row that owned the focus. The focused
 * row is stored by its row key rather than its index, so an item inserted above it does not move
 * the cursor to a stranger.
 */
internal data class LibraryViewport(
    val index: Int,
    val offset: Int,
    val focusedRowKey: String?
)

internal fun LazyListState.captureViewport(focusedRowKey: String?): LibraryViewport = LibraryViewport(
    index = firstVisibleItemIndex,
    offset = firstVisibleItemScrollOffset,
    focusedRowKey = focusedRowKey
)

/** Saves the five viewports as a flat list of strings, so a process restart keeps the cache. */
internal val LibraryViewportsSaver: Saver<MutableMap<LibraryFilter, LibraryViewport>, List<String>> = Saver(
    save = { viewports ->
        viewports.entries.flatMap { (filter, viewport) ->
            listOf(
                filter.name,
                viewport.index.toString(),
                viewport.offset.toString(),
                viewport.focusedRowKey.orEmpty()
            )
        }
    },
    restore = { saved ->
        mutableStateMapOf<LibraryFilter, LibraryViewport>().apply {
            saved.chunked(4).forEach { parts ->
                if (parts.size != 4) return@forEach
                runCatching {
                    put(
                        LibraryFilter.valueOf(parts[0]),
                        LibraryViewport(parts[1].toInt(), parts[2].toInt(), parts[3].ifEmpty { null })
                    )
                }
            }
        }
    }
)
