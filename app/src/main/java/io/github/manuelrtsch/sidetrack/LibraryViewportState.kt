package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import io.github.manuelrtsch.sidetrack.ui.RowGutterState

// The library's reading-position rules: where each category was left, which row gets focus when
// it comes back, and how a category switch is recorded. Kept out of LibraryScreen's body so the
// composition reads as layout rather than focus choreography. The choreography itself is shared
// with Search in CategoryViewports; what is local here is the policy — filters, branches, and
// positions that survive process death.

/**
 * What [rememberLibraryViewport] hands back to the screen: the current body branch, the focus
 * pieces the list needs while composing rows, and the two input modifiers the filter row and the
 * first row attach. Everything else stays private to the controller.
 */
internal class LibraryViewportState(
    val restoreFocusIndex: Int?,
    val restoreFocus: FocusRequester,
    val switchFilter: (LibraryFilter) -> Unit,
    val holdSwitch: Modifier,
    val firstRowModifier: Modifier
)

/**
 * Remembers one viewport per category across a switch and across process death.
 *
 * The focused row is keyed by the row, not its old index: saving or removing a row above the
 * cursor must not move the cursor to a different item when the category comes back. A switch
 * from the list records the exact position; a switch from the category row keeps the last
 * settled viewport instead of the temporary top the user climbed through.
 */
@Composable
internal fun rememberLibraryViewport(
    library: LibraryUiState,
    listState: LazyListState,
    rows: TrackRows,
    libraryGutters: RowGutterState,
    filterFocus: Map<LibraryFilter, FocusRequester>,
    moreFocus: LoadMoreFocusState,
    supportedFilters: Set<LibraryFilter>,
    gate: CategoryHoldGate,
    onFilter: (LibraryFilter) -> Unit
): LibraryViewportState {
    val filter = library.filter
    val items = library.libraryItems
    val loading = items.isEmpty() && library.libraryLoading
    val viewports = rememberSaveable(saver = LibraryViewportsSaver) {
        mutableStateMapOf<LibraryFilter, LibraryViewport>()
    }
    val lastFocusedRowKey = rememberLastFocusedRowKey(rows, libraryGutters)
    var pendingViewport by remember { mutableStateOf<LibraryViewport?>(null) }
    // A switch from the category row is browsing, not entering a list: the cursor stays on the
    // row so Left/Right can keep stepping. A switch from a row body (held arrow) came from the
    // list, so that one may put the cursor back into the category's settled row.
    var restoreListFocus by remember { mutableStateOf(true) }
    var restoreFocusIndex by remember { mutableStateOf<Int?>(null) }
    var focusFilterRow by remember { mutableStateOf(false) }
    val restoreFocus = remember { FocusRequester() }
    // Switching category swaps this screen between the status body and the list, which
    // re-creates the filter row and drops focus. Re-attach focus to the selected category
    // whenever the category or the body branch changes, so keyboard navigation across the row
    // never falls through to the action bar.
    val branch = when {
        loading -> "loading"
        items.isEmpty() && library.libraryError != null -> "error"
        items.isEmpty() -> "empty"
        else -> "list"
    }
    SettledViewportEffect(
        settledKey = (rows.focus.openKey ?: libraryGutters.openKey)?.toString(),
        category = filter,
        branch = branch,
        items = items,
        listState = listState,
        record = { viewports[filter] = it }
    )
    val switchFilter: (LibraryFilter, Boolean) -> Unit = { next, fromRow ->
        if (next != filter) {
            // The live focus decides whether this was a switch from the list; the remembered row
            // is only a fallback for the position that gets recorded.
            val liveKey = (rows.focus.focusedKey ?: libraryGutters.focusedKey)?.toString()
            pendingViewport = recordCategorySwitch(
                viewports = viewports,
                from = filter,
                to = next,
                listState = listState,
                focusedKey = liveKey ?: lastFocusedRowKey.value,
                focusedIndex = items.indexOfRowKey(liveKey),
                lastFocusedRowKey = lastFocusedRowKey.value
            )
            restoreListFocus = !fromRow
            restoreFocusIndex = null
            lastFocusedRowKey.value = null
            onFilter(next)
        }
    }
    val holdSwitch = Modifier.categoryHoldSwitch(
        current = filter,
        entries = LibraryFilter.entries,
        supported = supportedFilters,
        enabled = rows.bar.selection == null,
        gate = gate,
        onSwitch = { switchFilter(it, false) }
    )
    LaunchedEffect(filter, branch) {
        if (branch != "list") {
            moreFocus.reset()
            runCatching { filterFocus.getValue(filter).requestFocus() }
            return@LaunchedEffect
        }
        val viewport = pendingViewport
        pendingViewport = null
        if (viewport == null || !restoreListFocus) {
            // The category row is the first list item, so it can only keep the cursor while the
            // list sits at the top. Browsing the row must not scroll it away and drop the cursor
            // into the restored content; a held arrow from a row body is the only switch allowed
            // to restore that category's row.
            listState.scrollToItem(0)
            runCatching { filterFocus.getValue(filter).requestFocus() }
            return@LaunchedEffect
        }
        restoreFocusIndex = listState.restoreViewport(viewport, items)
    }
    RestoreRowFocusEffect(restoreFocusIndex, restoreFocus) { restoreFocusIndex = null }
    LaunchedEffect(filter) {
        moreFocus.reset()
    }
    // Up from the first row returns to the keyboard row rather than letting focus search pick
    // the geometrically nearest button.
    CategoryRowFocusEffect(
        pending = focusFilterRow,
        listState = listState,
        requester = { filterFocus.getValue(filter) },
        onSettled = { focusFilterRow = false }
    )
    val firstRowModifier = Modifier.returnToCategoryRow { focusFilterRow = true }
    return LibraryViewportState(
        restoreFocusIndex = restoreFocusIndex,
        restoreFocus = restoreFocus,
        switchFilter = { switchFilter(it, true) },
        holdSwitch = holdSwitch,
        firstRowModifier = firstRowModifier
    )
}
