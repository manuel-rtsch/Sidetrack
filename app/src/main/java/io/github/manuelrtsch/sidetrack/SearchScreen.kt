package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.action.ActionMotion
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitCompactItem
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitTextField
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.components.rememberT9Active
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.input.KeypadModule
import hrln.interactive.sidekit.design.input.LocalKeypad
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.sdk.ui.PauseUndoWhileHidden
import hrln.interactive.sidekit.sdk.ui.UndoWindowLifecycle
import hrln.interactive.sidekit.sdk.ui.rememberUndoWindow
import io.github.manuelrtsch.sidetrack.ui.SidekitSwipeRow
import io.github.manuelrtsch.sidetrack.ui.SwipeRevealGroup
import io.github.manuelrtsch.sidetrack.ui.rememberRowGutterState
import io.github.manuelrtsch.sidetrack.ui.rememberSwipeRevealGroup

/**
 * The search landing screen: type a term, choose where to look, or pick a recent search.
 *
 * Submitting hands the query to [SearchResultsScreen]; Back returns here with the term still in the
 * field. The history lives below "Search in" and scrolls with the page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    search: SearchUiState,
    chrome: ChromeUiState,
    supportedScopes: Set<SearchScope> = SearchScope.entries.toSet(),
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    onClear: () -> Unit,
    onRemoveHistory: (String) -> Unit,
    onScopes: () -> Unit,
    onNowPlaying: () -> Unit,
    onDismissNotice: () -> Unit,
    onDismissNowPlaying: () -> Unit,
    onDraftQuery: (String) -> Unit = {},
    onClearHistory: () -> Unit = {},
    onRestoreHistory: (List<String>) -> Unit = {},
    focusDismissed: Boolean = false,
    /**
     * Whether the on-screen keyboard is up. The screen yields everything but the field and the
     * category row to it. Tests pass it explicitly: WindowInsets cannot be faked.
     */
    keyboardVisible: Boolean = WindowInsets.isImeVisible,
    onDismissFocus: () -> Unit = {},
    onRestoreFocus: () -> Unit = {},
    onUndoQueueReplace: () -> Unit = {}
) {
    val field = rememberTextFieldState(search.query)
    val typed = field.text.toString()
    val keypad = LocalKeypad.current
    val term = typed.trim()
    val canSearch = term.length >= 2
    // One submit path for the Search action, the IME's Done and the letterless keypads.
    val focusManager = LocalFocusManager.current
    val submit = {
        if (canSearch) {
            field.setTextAndPlaceCursorAtEnd(term)
            onSearch(term)
        } else {
            // Done below two letters has nothing to search, so it drops the keyboard instead of
            // doing nothing: the screen keeps no actions while the keyboard is up.
            focusManager.clearFocus()
        }
    }
    // A too-short term is told to the field: the hint that would explain it is what the IME covers.
    val tooShort = searchFieldStateDescription(typed)
    // Spelled out only once the placeholder is gone: the empty field's placeholder already reads as
    // "Search", and labelling it too made TalkBack say the word twice.
    val fieldLabel = if (typed.isEmpty()) null else "Search"
    // Keep an abandoned draft, so returning to change categories shows the typed text, not the
    // last submitted term.
    LaunchedEffect(typed) { onDraftQuery(typed) }
    val searchFocus = remember { FocusRequester() }
    // Only a term to refine or a keypad that types takes the keyboard; a dismissal lasts the visit,
    // so clearing the history never reopens it over the Undo that was just offered.
    val keypadTypes = rememberT9Active()
    val autoFocus = searchFieldAutoFocus(search.query, keypadTypes, focusDismissed)
    // One press and one Undo, like the app's other list-wide deletions.
    var clearedHistory by remember { mutableStateOf<List<String>?>(null) }
    val undoWindow = rememberUndoWindow { clearedHistory = null }
    UndoWindowLifecycle(undoWindow) { clearedHistory = null }
    // The bar is not what is drawn while the IME owns the screen, so the window must pause rather
    // than run out unseen behind it.
    PauseUndoWhileHidden(undoWindow, shown = !keyboardVisible, pending = clearedHistory != null)
    // The summary names what the next search reads: all answerable categories in is "Everything",
    // and a single omission is named rather than listed, because the right side holds one line.
    val pickedScopes = SearchScope.entries.filter { it in supportedScopes && it in search.searchScopes }
    val leftOutScopes = SearchScope.entries.filter { it in supportedScopes && it !in search.searchScopes }
    val scopeSummary = when {
        supportedScopes.isEmpty() -> "Nothing"
        pickedScopes.size == supportedScopes.size -> "Everything"
        pickedScopes.isEmpty() -> "Nothing"
        pickedScopes.size == 1 -> pickedScopes.single().label
        leftOutScopes.size == 1 -> "All but " + leftOutScopes.single().label
        else -> pickedScopes.joinToString(", ") { it.label }
    }
    // Clear belongs to the screen's own bar, and steps aside with it when a notice owns the bar.
    val notice = chrome.notice != null || chrome.nowPlayingNotice != null || chrome.queueReplaced
    val clearHistory = if (typed.isEmpty() && !keyboardVisible && !notice && search.searchHistory.isNotEmpty()) {
        SideAction(
            "clearHistory",
            "Clear",
            SidekitIcon.DeleteForever,
            tone = ActionTone.Destructive,
            contentDescription = "Clear search history",
            onClick = {
                clearedHistory = search.searchHistory
                undoWindow.start()
                onClearHistory()
            }
        )
    } else {
        null
    }
    val undo = clearedHistory?.let { previous ->
        restoreAction("restoreSearchHistory", "Restore search history", "Restore your search history") {
            undoWindow.cancel()
            clearedHistory = null
            onRestoreHistory(previous)
        }
    }
    // Refusing the offer ends its window; the clear it offered to take back stays.
    val dismissUndo = {
        undoWindow.cancel()
        clearedHistory = null
    }
    PromptBack(live = clearedHistory != null, onDismiss = dismissUndo)
    val clear = if (typed.isNotEmpty() && !keyboardVisible && !notice) {
        SideAction.square(
            "clear",
            SidekitIcon.Close,
            "Clear search",
            tone = ActionTone.Muted,
            onClick = {
                field.setTextAndPlaceCursorAtEnd("")
                onClear()
            }
        )
    } else {
        null
    }
    val back = sidetrackBack("Back", onBack).first()
    val actions = when {
        keyboardVisible -> emptyList()

        undo != null -> restoreBar(undo, dismissUndo)

        else -> sidetrackActions(
            chrome,
            onDismissNotice,
            onNowPlaying,
            onDismissNowPlaying,
            buildList {
                add(back)
                when {
                    canSearch -> add(
                        SideAction("search", "Search", SidekitIcon.Search, motion = ActionMotion.Forward) {
                            submit()
                        }
                    )

                    clearHistory != null -> add(clearHistory)
                }
            },
            onUndoQueueReplace = onUndoQueueReplace
        )
    }
    // One row may be swiped open at a time, like the queue.
    val swipes = rememberSwipeRevealGroup()
    SidekitScaffold(
        title = null,
        actions = actions,
        trailingActions = listOfNotNull(clear),
        options = if (keyboardVisible) emptyList() else sidetrackOptions(onNowPlaying = onNowPlaying),
        header = {
            Box(
                Modifier.fillMaxWidth().height(SidekitTheme.spacing.headerHeight)
                    .padding(horizontal = SidekitTheme.spacing.itemPadding).padding(top = SidekitTheme.spacing.headerPaddingTop)
            ) {
                SidekitTextField(
                    field,
                    Modifier.fillMaxWidth().semantics {
                        if (fieldLabel != null) contentDescription = fieldLabel
                        if (tooShort != null) stateDescription = tooShort
                    }
                        // Coming back to the field asks for the keyboard; leaving it puts it away.
                        .onFocusChanged { focus ->
                            if (focus.hasFocus) {
                                onRestoreFocus()
                            } else if (typed.isNotEmpty()) {
                                onDismissFocus()
                            }
                        }
                        .onPreviewKeyEvent { event ->
                            // The typing keypads own Enter: their OK takes the suggestion and submits.
                            if (event.type != KeyEventType.KeyDown || event.nativeKeyEvent.repeatCount != 0) {
                                false
                            } else if (isSearchSubmitKey(event.key, keypad?.module)) {
                                submit()
                                true
                            } else {
                                false
                            }
                        },
                    textStyle = SidekitTheme.typography.title,
                    placeholder = "Search",
                    focusRequester = searchFocus,
                    // Only while focused: after Up / Down the keypad navigates the page, so typing
                    // must not take focus back.
                    focusOnKeypad = false,
                    singleLine = true,
                    // Decided above; see searchFieldAutoFocus for why history wins on an empty field.
                    autoFocus = autoFocus,
                    onDone = submit
                )
            }
        }
    ) { padding ->
        val listState = rememberLazyListState()
        val history = search.searchHistory
        SidekitList(padding, state = listState) {
            item(key = "scopes") {
                TerminalRow(
                    "Search in",
                    modifier = Modifier.listFade(listState, 0),
                    secondary = scopeSummary,
                    // The badge is a green success surface; the muted tone disappears on it.
                    secondaryColor = SidekitTheme.colors.light,
                    onClick = onScopes
                )
            }
            if (history.isNotEmpty()) {
                if (!keyboardVisible) {
                    item(key = "historyLabel") {
                        SectionLabel("Recent searches", Modifier.listFade(listState, 1))
                    }
                    itemsIndexed(history, key = { _, term -> "history:$term" }) { index, term ->
                        SearchHistoryRow(
                            term,
                            swipes,
                            Modifier.listFade(listState, index + 2),
                            onOpen = {
                                field.setTextAndPlaceCursorAtEnd(term)
                                // Picking a remembered term is not typing: the results are next.
                                onDismissFocus()
                                onSearch(term)
                            },
                            onRemove = {
                                swipes.closeAll()
                                onRemoveHistory(term)
                            }
                        )
                    }
                }
            } else {
                val hint = searchHint(typed, keyboardVisible)
                if (hint != null) {
                    item(key = "hint") { EmptyMessage(hint, Modifier.listFade(listState, 1)) }
                }
            }
        }
    }
}

/**
 * The line under "Search in" while there is no history to show, or null when there is nothing to
 * say. With the on-screen keyboard up the hint would sit under the IME, cut in half: the body is
 * only as tall as the keyboard leaves it. A keypad never puts the keyboard over the page.
 */
internal fun searchHint(typed: String, keyboardVisible: Boolean): String? = when {
    keyboardVisible -> null
    typed.isBlank() -> "Type at least two letters"
    typed.trim().length < 2 -> "Keep typing"
    else -> "Press Done to search"
}

/**
 * What the field says about a term that is not searchable yet, or null when it is. The rule lives on
 * the field because the drawn hint is not read with the text, and is absent behind the keyboard.
 * Only while violated: an empty field keeps the placeholder's plain "Search".
 */
internal fun searchFieldStateDescription(typed: String): String? = if (typed.isNotBlank() && typed.trim().length < 2) "Keep typing, at least two letters" else null

/**
 * Whether the landing field takes the keyboard on entry: a term worth refining, or a keypad that
 * types. Everything else leaves the history readable, and a dismissal lasts the visit.
 */
internal fun searchFieldAutoFocus(query: String, keypadTypes: Boolean, dismissed: Boolean): Boolean = !dismissed && (query.isNotEmpty() || keypadTypes)

/**
 * Keys that submit the field. T9 and QWERTY own Enter while they type, so only the letterless
 * keypads are handled here.
 */
internal fun isSearchSubmitKey(key: Key, module: KeypadModule?): Boolean {
    if (module?.typesOnKeypad == true) return false
    return when (key) {
        Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> true
        Key.ButtonA -> module == KeypadModule.GamePad
        Key.MediaPlayPause -> module == KeypadModule.Sundial
        else -> false
    }
}

/**
 * One remembered term: tap to search it again, swipe (or focus with the keypad) to reveal Remove.
 * The SDK's compact item on the SDK's swipe row, so it behaves like every other removable row.
 */
@Composable
private fun SearchHistoryRow(
    term: String,
    group: SwipeRevealGroup,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onRemove: () -> Unit
) {
    SidekitSwipeRow(
        key = "history:$term",
        group = group,
        modifier = modifier,
        onDelete = onRemove,
        deleteLabel = "Remove",
        rowHeight = SidekitTheme.spacing.compactRowHeight
    ) {
        SidekitCompactItem(term, icon = SidekitIcon.Search, onClick = onOpen)
    }
}

/**
 * The results page for the submitted query. The term is the title and cannot be edited here: Back
 * returns to the field holding it. Options changes the categories, and toggling one searches again.
 */
@Composable
fun SearchResultsScreen(
    search: SearchUiState,
    saved: SavedUiState,
    chrome: ChromeUiState,
    supportedScopes: Set<SearchScope> = SearchScope.entries.toSet(),
    onBack: () -> Unit,
    onTrack: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onArtist: (Artist) -> Unit,
    onAlbum: (Album) -> Unit,
    onPlaylist: (Playlist) -> Unit,
    onShow: (Show) -> Unit,
    onSaveTrack: (Track) -> Unit,
    onEnsureSavedTrack: (Track) -> Unit,
    onLoadMore: (SearchScope) -> Unit,
    onRetry: () -> Unit,
    onScopes: () -> Unit,
    onNowPlaying: () -> Unit,
    onDismissNotice: () -> Unit,
    onDismissNowPlaying: () -> Unit,
    onAddToPlaylist: ((Track) -> Unit)? = null,
    /** Offered where the service can build a station from an artist; null hides the row gutter. */
    canRadio: Boolean = true,
    onArtistRadio: (Artist) -> Unit = {},
    onQueueAlbum: (Album) -> Unit = {},
    onQueuePlaylist: (Playlist) -> Unit = {},
    onAddLatestShowToQueue: (Show) -> Unit = {},
    onUndoQueueReplace: () -> Unit = {}
) {
    val results = search.searchResults
    val rows = rememberTrackRows(
        saved,
        onSaveTrack,
        onEnsureSavedTrack,
        onAddToQueue,
        onAddToPlaylist = onAddToPlaylist
    )
    var scope by rememberSaveable { mutableStateOf(SearchScope.Tracks) }
    val query = search.query.trim()
    val hasQuery = query.length >= 2
    val loading = hasQuery && search.searchPending && results.isEmpty
    val failed = hasQuery && !search.searchPending && search.searchError != null && results.isEmpty
    val baseActions = remember(failed, onRetry) {
        listOf(sidetrackBack("Back", onBack).first()) +
            if (failed) listOf(retryAction(onRetry)) else emptyList()
    }
    val actions = rowActionBarActions(
        rows.bar,
        sidetrackActions(chrome, onDismissNotice, onNowPlaying, onDismissNowPlaying, baseActions, onUndoQueueReplace)
    )
    val filterFocus = remember { SearchScope.entries.associateWith { FocusRequester() } }
    val moreFocus = rememberLoadMoreFocus()
    // One gutter state for every result row, so their action squares behave like the library's.
    val searchGutters = rememberRowGutterState()
    // The gate outlives any one row: a held arrow may rebuild the list before the key comes up.
    // The screen root uses it to swallow the repeats and the release.
    val holdGate = remember { CategoryHoldGate() }
    val showFilters = hasQuery && !loading && !failed
    // Moving the row and the selection only walks the categories that are both queried and answerable.
    val includedScopes = SearchScope.entries.filter { it in supportedScopes && it in search.searchScopes }.toSet()
    // A failure replaces the list, so drop an in-flight page: a retry must not read its result set
    // as a page that just arrived.
    LaunchedEffect(failed) { if (failed) moreFocus.reset() }
    SidekitScaffold(
        title = query.ifBlank { "Search" },
        modifier = Modifier
            .dismissRowActionsOnVerticalKey(rows.bar)
            .consumeCategoryHoldRepeats(holdGate),
        actions = actions,
        options = sidetrackOptions(searchInOption(onScopes), onNowPlaying = onNowPlaying)
    ) { padding ->
        val listState = rememberLazyListState()
        rows.bar.DismissOnScroll(listState)
        val viewport = rememberSearchViewport(
            query = search.query,
            scope = scope,
            setScope = { scope = it },
            supportedScopes = supportedScopes,
            includedScopes = includedScopes,
            showFilters = showFilters,
            failed = failed,
            results = results,
            listState = listState,
            rows = rows,
            searchGutters = searchGutters,
            moreFocus = moreFocus,
            scopeFocus = filterFocus,
            gate = holdGate
        )
        val holdSwitch = viewport.holdSwitch
        val restoreFocus = viewport.restoreFocus
        val restoreFocusIndex = viewport.restoreFocusIndex
        // A new query opens from the top; a scope change is handled by the viewport, which also
        // keeps the cursor on the category row.
        LaunchedEffect(search.query) {
            moreFocus.reset()
            listState.scrollToItem(0)
        }
        if (!hasQuery) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyMessage(
                    "Type at least two letters",
                    hint = "Go back to the search field"
                )
            }
            return@SidekitScaffold
        }
        if (failed || loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                LoadSurface(
                    loading = loading,
                    loadingTitle = "Searching for \"$query\"…",
                    error = if (failed) search.searchError else null,
                    errorTitle = "Couldn't search"
                )
            }
            return@SidekitScaffold
        }
        val items = results.itemsFor(scope)
        val hasMore = scope in search.searchHasMore
        val morePending = scope in search.searchMorePending
        LoadMoreFocus(moreFocus, listState, items.size, morePending, firstItemListIndex = 1)
        val rowKeys = remember(items) { mediaRowKeys(items) }
        SidekitList(padding, state = listState) {
            // Like the library, the category row is the first list item, so it scrolls away with
            // the content.
            item(key = "filters") {
                ContentFilterRow(
                    filters = SearchScope.entries,
                    selected = scope,
                    icon = { it.icon },
                    label = { it.label },
                    onSelect = viewport.switchScope,
                    focusRequesters = filterFocus,
                    // A row action bar owns the focus while it is open: leaving the category
                    // buttons focusable let touch-mode exit select one and dispose that very row.
                    acceptsFocus = rows.bar.selection == null,
                    // An unanswerable category stays for the row's shape but takes no click and no
                    // focus: it would only ever show an empty list.
                    enabled = { it in supportedScopes && it in search.searchScopes },
                    disabledLabel = { if (it in supportedScopes) "off" else "not available" },
                    modifier = Modifier.listFade(listState, 0)
                        .padding(bottom = SidekitTheme.spacing.pageGap)
                )
            }
            if (items.isEmpty()) {
                item(key = "empty") {
                    EmptyMessage(
                        "No ${scope.label.lowercase()} for \"$query\"",
                        Modifier.listFade(listState, 1),
                        hint = "Try another spelling or keyword"
                    )
                }
            } else {
                itemsIndexed(
                    items,
                    key = { index, _ -> rowKeys[index] },
                    contentType = { _, item -> mediaContentType(item) }
                ) { index, item ->
                    // Every result row carries its own focus target, so the load-more requester
                    // travels with a row instead of riding a wrapper that has no focusable. A
                    // restored row takes the shared restore requester.
                    val focusRequester = if (index == restoreFocusIndex) {
                        restoreFocus
                    } else {
                        moreFocus.requesterFor(index)
                    }
                    val modifier = Modifier.listFade(listState, index + 1)
                        .then(if (index == 0) viewport.firstRowModifier else Modifier)
                    when (item) {
                        is Track -> TrackRow(
                            rowKeys[index],
                            item,
                            rows,
                            modifier,
                            bodyModifier = holdSwitch,
                            // Search is about finding a result; the duration is not part of that
                            // decision and costs the title a large part of the row.
                            showDuration = false,
                            focusRequester = focusRequester
                        ) { onTrack(item) }

                        is Artist -> ArtistGutterRow(
                            rowKeys[index],
                            item,
                            searchGutters,
                            rows.bar,
                            modifier,
                            bodyModifier = holdSwitch,
                            focusRequester = focusRequester,
                            canRadio = canRadio,
                            onRadio = { onArtistRadio(item) },
                            onClick = { onArtist(item) }
                        )

                        is Album -> AlbumGutterRow(
                            rowKeys[index],
                            item,
                            searchGutters,
                            rows.bar,
                            modifier,
                            bodyModifier = holdSwitch,
                            focusRequester = focusRequester,
                            onAddToQueue = { onQueueAlbum(item) },
                            onClick = { onAlbum(item) }
                        )

                        is Playlist -> PlaylistGutterRow(
                            rowKeys[index],
                            item,
                            searchGutters,
                            rows.bar,
                            modifier,
                            bodyModifier = holdSwitch,
                            focusRequester = focusRequester,
                            onAddToQueue = { onQueuePlaylist(item) },
                            onClick = { onPlaylist(item) }
                        )

                        is Show -> ShowGutterRow(
                            rowKeys[index],
                            item,
                            searchGutters,
                            rows.bar,
                            modifier,
                            bodyModifier = holdSwitch,
                            focusRequester = focusRequester,
                            onAddLatestToQueue = { onAddLatestShowToQueue(item) },
                            onClick = { onShow(item) }
                        )
                    }
                }
                if (hasMore) {
                    item(key = "more") {
                        LoadMoreRow(
                            "Load more ${scope.label.lowercase()}",
                            morePending,
                            onLoadMore = {
                                moreFocus.onLoadMore(items.size)
                                onLoadMore(scope)
                            },
                            modifier = Modifier.listFade(listState, items.size + 1),
                            focusState = moreFocus
                        )
                    }
                }
            }
        }
    }
}

internal fun SearchResults.itemsFor(scope: SearchScope): List<Any> = when (scope) {
    SearchScope.Tracks -> tracks
    SearchScope.Artists -> artists
    SearchScope.Albums -> albums
    SearchScope.Playlists -> playlists
    SearchScope.Shows -> shows
}

internal fun SearchResults.sizeFor(scope: SearchScope): Int = itemsFor(scope).size

private val SearchScope.icon: SidekitIcon
    get() = when (this) {
        SearchScope.Tracks -> SidekitIcon.MusicNote2
        SearchScope.Artists -> SidekitIcon.Artist
        SearchScope.Albums -> SidekitIcon.Album
        SearchScope.Playlists -> SidekitIcon.QueueMusic
        SearchScope.Shows -> SidekitIcon.SmartDisplay
    }

/** Opens the category chooser from the results page without giving up the term being read. */
private fun searchInOption(onClick: () -> Unit) = SideAction(
    key = "searchIn",
    label = "Search in",
    icon = SidekitIcon.Search,
    onClick = onClick
)

/** Lets the user choose which search categories are queried without hiding them in Options. */
@Composable
fun SearchScopesScreen(
    search: SearchUiState,
    chrome: ChromeUiState,
    supportedScopes: Set<SearchScope> = SearchScope.entries.toSet(),
    onBack: () -> Unit,
    onToggleScope: (SearchScope) -> Unit,
    onNowPlaying: () -> Unit,
    onDismissNotice: () -> Unit,
    onDismissNowPlaying: () -> Unit,
    onUndoQueueReplace: () -> Unit = {}
) {
    val baseActions = remember { listOf(sidetrackBack("Search", onBack).first()) }
    val actions = sidetrackActions(chrome, onDismissNotice, onNowPlaying, onDismissNowPlaying, baseActions, onUndoQueueReplace)
    SidekitScaffold(
        title = "Search in",
        actions = actions,
        options = sidetrackOptions(onNowPlaying = onNowPlaying)
    ) { padding ->
        val listState = rememberLazyListState()
        SidekitList(padding, state = listState) {
            item(key = "label") {
                SectionLabel("Categories")
            }
            SearchScope.entries.forEachIndexed { index, scope ->
                item(key = scope.name) {
                    // An unanswerable category is shown, not hidden: the list keeps its shape on
                    // every service, and "Not available" explains it instead of a gap.
                    val available = scope in supportedScopes
                    val included = available && scope in search.searchScopes
                    // Never turn the last category off: the page has no list without one. The row
                    // stays focusable so its checked, disabled state is still announced.
                    val locked = included &&
                        search.searchScopes.count { it in supportedScopes } == 1
                    SidetrackItem(
                        scope.label,
                        modifier = Modifier.listFade(listState, index + 1)
                            .semantics { if (locked || !available) disabled() },
                        secondary = when {
                            !available -> "Not available"
                            locked -> "On, required"
                            included -> "On"
                            else -> "Off"
                        },
                        secondaryToEnd = true,
                        icon = if (included) SidekitIcon.Check else null,
                        toggleable = included,
                        toggleRole = Role.Checkbox,
                        onClick = { if (available && !locked) onToggleScope(scope) }
                    )
                }
            }
        }
    }
}
