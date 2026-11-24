package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.theme.SidekitTheme
import kotlinx.coroutines.launch

/**
 * Picks the playlist a track should be appended to.
 *
 * Only playlists that report [PlaylistActions.canAddItems] are listed. A target that the account
 * may not edit would otherwise be a button whose only outcome is a server refusal. The add is a
 * creation, so it is reported by the screen's notice ("Added track to X") and never offered as
 * Undo: Undo is reserved for destructive edits.
 */
@Composable
fun AddToPlaylistScreen(
    track: Track?,
    playlists: PlaylistUiState,
    chrome: ChromeUiState,
    /** The playlists an add is currently being written to; their rows say so. */
    adding: Set<String> = emptySet(),
    onBack: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onAdd: suspend (Playlist) -> Int?,
    onDismissNotice: () -> Unit,
    onDismissNowPlaying: () -> Unit,
    onUndoQueueReplace: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val choices = playlists.choices
    val loadFailed = playlists.error != null && choices.isEmpty()
    val baseActions = remember(loadFailed, onRetry) {
        sidetrackBack("Back", onBack) + if (loadFailed) listOf(retryAction(onRetry)) else emptyList()
    }
    val actions = sidetrackActions(
        chrome,
        onDismissNotice,
        onOpenNowPlaying = {},
        onDismissNowPlaying = onDismissNowPlaying,
        onUndoQueueReplace = onUndoQueueReplace,
        actions = baseActions
    )
    SidekitScaffold(title = "Add to playlist", actions = actions) { padding ->
        val listState = rememberLazyListState()
        val loading = playlists.loading && choices.isEmpty()
        val nothingToShow = choices.isEmpty() && playlists.loaded && !playlists.hasMore
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            if (track == null) {
                StatusScreen(
                    "This track is no longer available",
                    hint = "Go back and open it again"
                )
            } else {
                LoadSurface(
                    loading = loading,
                    loadingTitle = "Loading playlists…",
                    error = playlists.error.takeIf { loadFailed },
                    errorTitle = "Couldn't load playlists",
                    emptyTitle = "No playlists you can add to".takeIf { nothingToShow },
                    emptyHint = "Create one from Library → Playlists"
                )
            }
        }
        // A page can hold only read-only playlists; if the service says there is another page,
        // show the Load more row instead of declaring the account has no targets.
        val canShowList = track != null && !loading &&
            !loadFailed &&
            (choices.isNotEmpty() || playlists.hasMore)
        if (!canShowList) return@SidekitScaffold
        val moreFocus = rememberLoadMoreFocus()
        LoadMoreFocus(moreFocus, listState, choices.size, playlists.loading, firstItemListIndex = 1)
        SidekitList(padding, state = listState) {
            item(key = "track") {
                SidekitText(
                    track.title,
                    Modifier.fillMaxWidth()
                        .padding(horizontal = SidekitTheme.spacing.itemPadding)
                        .listFade(listState, 0),
                    style = SidekitTheme.typography.bodySmall,
                    color = SidekitTheme.colors.surfaceA6,
                    maxLines = 1
                )
            }
            itemsIndexed(
                choices,
                key = { _, playlist -> playlist.key.encode() },
                contentType = { _, _ -> "playlist" }
            ) { index, playlist ->
                val pending = playlist.key.encode() in adding
                LibraryRow(
                    playlist.artRes,
                    playlist.name,
                    if (pending) "Adding…" else playlistSubtitle(playlist),
                    Modifier.listFade(listState, index + 1).rowFocusTarget(moreFocus, index),
                    artUrl = playlist.artUrl
                ) {
                    // The registry refuses a second write for the same target; the row says
                    // "Adding…" meanwhile so the tap does not look lost.
                    if (pending) return@LibraryRow
                    scope.launch { onAdd(playlist) }
                }
            }
            if (playlists.hasMore) {
                val moreIndex = choices.size + 1
                item(key = "more") {
                    LoadMoreRow(
                        "Load more playlists",
                        playlists.loading,
                        onLoadMore = {
                            moreFocus.onLoadMore(choices.size)
                            onLoadMore()
                        },
                        Modifier.listFade(listState, moreIndex),
                        focusState = moreFocus
                    )
                }
            }
        }
    }
}
