package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** A non-focusable label that makes the tail and shuffle state visible at a glance. */
@Composable
internal fun QueueSectionHeader(count: Int, shuffled: Boolean, modifier: Modifier = Modifier) {
    val label = buildString {
        append("Up next · ")
        append(count)
        append(if (count == 1) " track" else " tracks")
        if (shuffled && count > 1) append(" · Shuffled")
    }
    // Same heading as Home, Library, search and album/playlist sections: uppercase, small, muted.
    SectionLabel(label, modifier.fillMaxWidth())
}

/** The edit the bar is still offering to take back. */
internal sealed interface QueueEdit {
    class Cleared(val items: List<MediaItem>) : QueueEdit

    /**
     * [key] is the removed row's list key — the row comes back on restore — and [neighbourKey] is
     * the row that took its place, which is where the cursor goes once the offer is refused.
     */
    class Removed(
        val index: Int,
        val item: MediaItem,
        val key: String? = null,
        val neighbourKey: String? = null
    ) : QueueEdit
}
