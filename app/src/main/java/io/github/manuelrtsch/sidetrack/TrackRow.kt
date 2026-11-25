package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import io.github.manuelrtsch.sidetrack.playback.formatListDuration
import io.github.manuelrtsch.sidetrack.ui.GutterRow
import io.github.manuelrtsch.sidetrack.ui.MeatballIcon
import io.github.manuelrtsch.sidetrack.ui.RowActionSquare
import io.github.manuelrtsch.sidetrack.ui.RowGutterState
import io.github.manuelrtsch.sidetrack.ui.Settle
import io.github.manuelrtsch.sidetrack.ui.gutterSquareFocus

/** The row whose gutter is showing in a track list. */
@Stable
class TrackRowFocusState : RowGutterState()

/**
 * A track list's shared gutter state, contextual action bar and row actions.
 *
 * A row has one gutter trigger. Pressing it publishes the row's actions to the screen's
 * contextual bar, so the row itself never grows a second square.
 */
class TrackRows(
    val focus: TrackRowFocusState,
    val bar: RowActionBarState,
    val savedIds: Set<String>,
    val onSave: (Track) -> Unit,
    val onEnsureSaved: (Track) -> Unit,
    val onAddToQueue: (Track) -> Unit,
    /**
     * Offered on pages whose row tap plays in collection context (album, playlist, radio): plays
     * this track now and hands the tail back to what was playing. Null hides the row action.
     */
    val onPlayFirst: ((Track) -> Unit)? = null,
    /** Offered where the account may append to some playlist; null hides the row action. */
    val onAddToPlaylist: ((Track) -> Unit)? = null,
    /** Offered on a playlist page; the index is the entry the user acted on. */
    val onRemoveFromPlaylist: ((Track, Int) -> Unit)? = null,
    /**
     * The row that takes a removed entry's place, by entry index. The bar needs it to put the
     * cursor somewhere sensible when a removal is confirmed and the row itself is gone.
     */
    val neighbourKey: ((Int) -> Any?)? = null
) {
    fun saved(track: Track): Boolean = track.key.encode() in savedIds
}

/** Remembers the list's gutter state and settles it once focus stops moving. */
@Composable
fun rememberTrackRows(
    saved: SavedUiState,
    onSave: (Track) -> Unit,
    onEnsureSaved: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onPlayFirst: ((Track) -> Unit)? = null,
    onAddToPlaylist: ((Track) -> Unit)? = null,
    onRemoveFromPlaylist: ((Track, Int) -> Unit)? = null,
    neighbourKey: ((Int) -> Any?)? = null
): TrackRows {
    val focus = remember { TrackRowFocusState() }
    val bar = rememberRowActionBarState()
    focus.Settle()
    return TrackRows(
        focus,
        bar,
        saved.savedTrackIds,
        onSave,
        onEnsureSaved,
        onAddToQueue,
        onPlayFirst,
        onAddToPlaylist,
        onRemoveFromPlaylist,
        neighbourKey
    )
}

/**
 * A track row with one gutter trigger. The trigger opens the contextual bar with Add to queue.
 *
 * Save/Unsave is deliberately page-only: it is a membership toggle with a destructive direction,
 * and Now Playing already owns it. Artist and album pages are destinations for the same reason.
 */
@Composable
fun TrackRow(
    key: Any,
    track: Track,
    rows: TrackRows,
    modifier: Modifier = Modifier,
    bodyModifier: Modifier = Modifier,
    index: Int? = null,
    /** The row's entry in a collection, when that index is not the display number. */
    entryIndex: Int? = null,
    showArt: Boolean = true,
    /** False hides the duration on screens where it is not part of the decision, such as search. */
    showDuration: Boolean = true,
    secondary: String = track.artist,
    /** Non-null marks this as the track playing now; see [RowAudio]. */
    audio: RowAudio? = null,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    val selected = rows.bar.selection?.key == key
    val rowFocus = remember { FocusRequester() }
    RegisterRowFocus(rows.bar.rows, key, rowFocus)
    CloseRowActionsWhenDisposed(selected, rows.bar)
    GutterRow(key, rows.focus, squares = 1, modifier = modifier, content = { gutterVisible ->
        MediaRow(
            track,
            gutterRowModifier(key, selected, rows.focus, rowFocus, focusRequester, bodyModifier),
            index = index,
            showArt = showArt,
            secondary = secondary,
            audio = audio,
            // The square gets the right edge while it is visible.
            trailing = if (gutterVisible || !showDuration) null else formatListDuration(track.durationMs),
            onClick = {
                rows.focus.touchDismissed(key)
                onClick()
            }
        )
    }) {
        // The trigger and its focus state only exist while the gutter is open. A long list
        // otherwise pays for a focus modifier on every row it composes.
        val squareFocus = Modifier.gutterSquareFocus(key, rows.focus)
        RowActionSquare(
            color = SidekitTheme.colors.surfaceA4,
            label = "Actions for ${track.title}",
            content = { MeatballIcon() },
            modifier = squareFocus,
            onClick = {
                rows.focus.touchDismissed(key)
                val addToPlaylist = rows.onAddToPlaylist
                val removeFromPlaylist = rows.onRemoveFromPlaylist
                val actions = rowActions(
                    rows.onPlayFirst?.let { play -> playRowAction(track.title) { play(track) } },
                    addToQueueRowAction(track.title) { rows.onAddToQueue(track) },
                    addToPlaylist?.let { add -> addToPlaylistRowAction(track.title) { add(track) } },
                    if (removeFromPlaylist != null && entryIndex != null) {
                        removeFromPlaylistRowAction(track.title) {
                            // Removal is destructive and cannot be undone at its old position, so
                            // the bar asks once more before writing.
                            rows.bar.show(
                                RowActionSelection(
                                    key = key,
                                    title = "Remove ${track.title}?",
                                    focusRequester = rowFocus,
                                    confirm = true,
                                    neighbourKey = rows.neighbourKey?.invoke(entryIndex),
                                    actions = rowActions(
                                        confirmRemoveFromPlaylistRowAction(track.title) {
                                            removeFromPlaylist(track, entryIndex)
                                        }
                                    )
                                )
                            )
                        }
                    } else {
                        null
                    }
                )
                rows.bar.show(
                    RowActionSelection(
                        key = key,
                        title = track.title,
                        focusRequester = rowFocus,
                        actions = actions
                    )
                )
            }
        )
    }
}

/**
 * A non-track media row with one context action: Add to queue. Episodes have no saved-track
 * endpoint, so Save stays track-only; their show is reached from Now Playing.
 */
@Composable
fun GutterMediaRow(
    key: Any,
    item: MediaItem,
    focus: RowGutterState,
    modifier: Modifier = Modifier,
    index: Int? = null,
    showArt: Boolean = true,
    secondary: String = item.creator,
    /** Non-null marks this as the episode playing now; see [RowAudio]. */
    audio: RowAudio? = null,
    focusRequester: FocusRequester? = null,
    onAddToQueue: (MediaItem) -> Unit,
    onClick: () -> Unit
) {
    GutterRow(key, focus, squares = 1, modifier = modifier, content = { gutterVisible ->
        MediaRow(
            item,
            Modifier
                .onFocusChanged { state ->
                    if (state.isFocused) focus.rowFocused(key) else focus.rowUnfocused(key)
                }
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
            index = index,
            showArt = showArt,
            secondary = secondary,
            audio = audio,
            trailing = if (gutterVisible) null else formatListDuration(item.durationMs),
            onClick = {
                focus.touchDismissed(key)
                onClick()
            }
        )
    }) {
        val squareFocus = Modifier.gutterSquareFocus(key, focus)
        RowActionSquare(
            color = SidekitTheme.colors.successA1,
            icon = SidekitIcon.PlaylistAdd,
            label = "Add ${item.title} to queue",
            modifier = squareFocus,
            onClick = {
                focus.touchDismissed(key)
                onAddToQueue(item)
            }
        )
    }
}
