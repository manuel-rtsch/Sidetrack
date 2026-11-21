package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import io.github.manuelrtsch.sidetrack.ui.RowGutterState

// The reading-position rules Library and Search share: which row the user had, how a switch
// records the category being left and seeds the next one, and the focus returns keyboard users
// rely on. What stays per screen is the branch naming, whether positions survive a switch, and
// the search-only scope correction; those are policy, the choreography below is not.

/**
 * The row the user had before climbing to the category row. A switch consumes it, so it is cleared
 * on every category change rather than being carried into the next one.
 */
@Composable
internal fun rememberLastFocusedRowKey(rows: TrackRows, gutters: RowGutterState): MutableState<String?> {
    val lastFocusedRowKey = remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        snapshotFlow { rows.focus.focusedKey ?: gutters.focusedKey }.collect { key ->
            if (key != null) lastFocusedRowKey.value = key.toString()
        }
    }
    return lastFocusedRowKey
}

/** Index of [key] among a category's rows, or -1 when the row is not in this category any more. */
internal fun List<Any>.indexOfRowKey(key: String?): Int = if (key == null) -1 else indexOfFirst { mediaRowKey(it) == key }

/**
 * Records the category being left and returns the position the next one resumes at.
 *
 * A quick switch from the list records the exact position. A switch from the category row keeps
 * the last settled viewport instead: the row can only own the cursor while the list sits at the
 * top, and that top is the temporary place the user climbed through, not a reading position.
 */
internal fun <T> recordCategorySwitch(
    viewports: MutableMap<T, LibraryViewport>,
    from: T,
    to: T,
    listState: LazyListState,
    focusedKey: String?,
    focusedIndex: Int,
    lastFocusedRowKey: String?
): LibraryViewport? {
    // [focusedIndex] is the live focus; [focusedKey] may be the remembered row instead, and the
    // two must not be conflated: only a row the user can see counts as "switched from the list".
    val focusedVisible = focusedIndex >= 0 &&
        listState.layoutInfo.visibleItemsInfo.any { it.index == focusedIndex + 1 }
    if (focusedVisible || viewports[from] == null) {
        viewports[from] = listState.captureViewport(focusedKey ?: lastFocusedRowKey)
    }
    return viewports[to]
}

/**
 * A row that has settled under the cursor is a reading position: the user stopped moving through
 * the list. Recording it here keeps the last real position even when the next thing they do is
 * climb all the way up to the category row.
 */
@Composable
internal fun SettledViewportEffect(
    settledKey: String?,
    category: Any?,
    branch: String,
    items: List<Any>,
    listState: LazyListState,
    record: (LibraryViewport) -> Unit
) {
    LaunchedEffect(settledKey, category, branch) {
        val settled = settledKey ?: return@LaunchedEffect
        if (branch != "list") return@LaunchedEffect
        if (items.indexOfRowKey(settled) < 0) return@LaunchedEffect
        record(listState.captureViewport(settled))
    }
}

/**
 * Scrolls to a recorded viewport and answers which body row should take the cursor: the row that
 * had it, or the first row of the saved page when that row is gone.
 */
internal suspend fun LazyListState.restoreViewport(viewport: LibraryViewport, items: List<Any>): Int {
    scrollToItem(
        viewport.index.coerceIn(0, items.size),
        viewport.offset.coerceAtLeast(0)
    )
    val focusedIndex = viewport.focusedRowKey?.let { key ->
        items.indexOfRowKey(key).takeIf { it >= 0 }
    }
    return focusedIndex ?: (viewport.index - 1).coerceIn(0, items.lastIndex)
}

/**
 * Asks the restored row for the cursor once the scroll above has composed it. The requester can
 * only attach on a frame that has rendered that row, so retry across a few of them instead of
 * guessing one.
 */
@Composable
internal fun RestoreRowFocusEffect(
    restoreFocusIndex: Int?,
    restoreFocus: FocusRequester,
    onSettled: () -> Unit
) {
    LaunchedEffect(restoreFocusIndex) {
        if (restoreFocusIndex == null) return@LaunchedEffect
        repeat(3) {
            withFrameNanos { }
            if (runCatching { restoreFocus.requestFocus() }.isSuccess) {
                onSettled()
                return@LaunchedEffect
            }
        }
        onSettled()
    }
}

/**
 * Puts the cursor back on the category row. The row can sit outside the composed window when the
 * first item is at the top, so scroll it back in and retry its requester across a few frames
 * rather than letting focus search pick the geometrically nearest button.
 */
@Composable
internal fun CategoryRowFocusEffect(
    pending: Boolean,
    listState: LazyListState,
    requester: () -> FocusRequester,
    onSettled: () -> Unit
) {
    LaunchedEffect(pending) {
        if (!pending) return@LaunchedEffect
        listState.scrollToItem(0)
        repeat(3) {
            withFrameNanos { }
            if (runCatching { requester().requestFocus() }.isSuccess) {
                onSettled()
                return@LaunchedEffect
            }
        }
        onSettled()
    }
}

/** Up from the first row asks for the category row instead of letting focus search decide. */
internal fun Modifier.returnToCategoryRow(onUp: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
        onUp()
        true
    } else {
        false
    }
}
