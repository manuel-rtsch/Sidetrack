package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import io.github.manuelrtsch.sidetrack.ui.RowGutterState

// The search page's reading-position rules mirror the library's, and share its choreography in
// CategoryViewports. What is local here is the search policy: positions are dropped when the query
// changes, and the selection is corrected when a category has nothing to show.

/**
 * What [rememberSearchViewport] hands back to the screen: the category-row switch, the held-arrow
 * switch modifier, the restore pieces the list needs while composing rows, and the first-row
 * modifier that returns Up to the category row.
 */
internal class SearchViewportState(
    val restoreFocusIndex: Int?,
    val restoreFocus: FocusRequester,
    val switchScope: (SearchScope) -> Unit,
    val holdSwitch: Modifier,
    val firstRowModifier: Modifier
)

/**
 * Remembers one reading position per category, discarded whenever the query changes because a new
 * query is a fresh browse. The focused row is keyed by the row, not its index, so an item inserted
 * above it does not move the cursor to a stranger.
 *
 * A switch from the category row keeps the cursor on the row so Left/Right can keep stepping; a
 * held arrow from a row body restores that category's settled position.
 */
@Composable
internal fun rememberSearchViewport(
    query: String,
    scope: SearchScope,
    setScope: (SearchScope) -> Unit,
    supportedScopes: Set<SearchScope>,
    includedScopes: Set<SearchScope>,
    showFilters: Boolean,
    failed: Boolean,
    results: SearchResults,
    listState: LazyListState,
    rows: TrackRows,
    searchGutters: RowGutterState,
    moreFocus: LoadMoreFocusState,
    scopeFocus: Map<SearchScope, FocusRequester>,
    gate: CategoryHoldGate
): SearchViewportState {
    val viewports = remember(query) { mutableStateMapOf<SearchScope, LibraryViewport>() }
    val lastFocusedRowKey = rememberLastFocusedRowKey(rows, searchGutters)
    var pendingViewport by remember { mutableStateOf<LibraryViewport?>(null) }
    var restoreListFocus by remember { mutableStateOf(true) }
    var restoreFocusIndex by remember { mutableStateOf<Int?>(null) }
    var focusFilterRow by remember { mutableStateOf(false) }
    // The results page has no search field to hand the cursor down from. When the first page
    // arrives (or the page opens with results already cached), the category row takes it instead.
    var entryFocusPending by remember { mutableStateOf(true) }
    val restoreFocus = remember { FocusRequester() }

    val items = results.itemsFor(scope)
    val branch = when {
        !showFilters || failed -> "top"
        items.isEmpty() -> "empty"
        else -> "list"
    }
    SettledViewportEffect(
        settledKey = (rows.focus.openKey ?: searchGutters.openKey)?.toString(),
        category = scope,
        branch = branch,
        items = items,
        listState = listState,
        record = { viewports[scope] = it }
    )

    // A query may return nothing in the selected category, and toggling categories can remove the
    // selected one entirely. Move the selection rather than letting the row and the list disagree;
    // the scope-change effect below puts the cursor on the row it chose.
    LaunchedEffect(query, includedScopes, results) {
        if (scope !in includedScopes) {
            SearchScope.entries.firstOrNull { it in includedScopes }
                ?.let { if (it != scope) setScope(it) }
        } else if (results.sizeFor(scope) == 0) {
            SearchScope.entries.firstOrNull {
                it in supportedScopes && it in includedScopes && results.sizeFor(it) > 0
            }?.let { if (it != scope) setScope(it) }
        }
    }

    LaunchedEffect(showFilters, failed) {
        if (!showFilters || failed || !entryFocusPending) return@LaunchedEffect
        entryFocusPending = false
        focusFilterRow = true
    }

    val switchScope: (SearchScope, Boolean) -> Unit = { next, fromRow ->
        if (next != scope) {
            // The live focus decides whether this was a switch from the list; the remembered row
            // is only a fallback for the position that gets recorded.
            val liveKey = (rows.focus.focusedKey ?: searchGutters.focusedKey)?.toString()
            pendingViewport = recordCategorySwitch(
                viewports = viewports,
                from = scope,
                to = next,
                listState = listState,
                focusedKey = liveKey ?: lastFocusedRowKey.value,
                focusedIndex = items.indexOfRowKey(liveKey),
                lastFocusedRowKey = lastFocusedRowKey.value
            )
            restoreListFocus = !fromRow
            restoreFocusIndex = null
            lastFocusedRowKey.value = null
            setScope(next)
        }
    }

    val holdSwitch = Modifier.categoryHoldSwitch(
        current = scope,
        entries = SearchScope.entries,
        supported = includedScopes,
        enabled = rows.bar.selection == null,
        gate = gate,
        onSwitch = { switchScope(it, false) }
    )

    // Chosen a category (row click or Left/Right): the cursor stays on the category row so Left/
    // Right can keep stepping. A held arrow from a row body restores that category's position. The
    // typing, loading and failed states own their own focus, so only the browse states touch it
    // here; an empty category has no position to restore, so its cursor stays on the row too.
    LaunchedEffect(scope) {
        moreFocus.reset()
        if (!showFilters || failed) return@LaunchedEffect
        val viewport = pendingViewport
        pendingViewport = null
        if (viewport == null || !restoreListFocus || branch == "empty") {
            listState.scrollToItem(0)
            focusFilterRow = true
            return@LaunchedEffect
        }
        restoreFocusIndex = listState.restoreViewport(viewport, items)
    }

    RestoreRowFocusEffect(restoreFocusIndex, restoreFocus) { restoreFocusIndex = null }

    // Up from the first result returns to the category row rather than letting focus search pick
    // the geometrically nearest button.
    CategoryRowFocusEffect(
        pending = focusFilterRow,
        listState = listState,
        requester = { scopeFocus.getValue(scope) },
        onSettled = { focusFilterRow = false }
    )

    val firstRowModifier = Modifier.returnToCategoryRow { focusFilterRow = true }

    return SearchViewportState(
        restoreFocusIndex = restoreFocusIndex,
        restoreFocus = restoreFocus,
        switchScope = { switchScope(it, true) },
        holdSwitch = holdSwitch,
        firstRowModifier = firstRowModifier
    )
}
