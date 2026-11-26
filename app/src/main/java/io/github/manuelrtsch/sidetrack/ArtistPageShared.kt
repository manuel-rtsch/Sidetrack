package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitItemRow
import hrln.interactive.sidekit.design.components.listFade

/**
 * The chrome shared by an artist page and its "All songs" page: the contextual row actions sit on
 * the [rememberTrackRows] bar, and the top bar is the standard sidetrack one with Back on the left.
 * Returns the row state the caller feeds to the list, and the actions for the scaffold.
 */
@Composable
internal fun artistPageChrome(
    saved: SavedUiState,
    onBack: () -> Unit,
    onSaveTrack: (Track) -> Unit,
    onEnsureSavedTrack: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onAddToPlaylist: ((Track) -> Unit)?,
    chrome: ChromeUiState,
    onDismissNotice: () -> Unit,
    onNowPlaying: () -> Unit,
    onDismissNowPlaying: () -> Unit,
    onUndoQueueReplace: () -> Unit
): Pair<TrackRows, List<SideAction>> {
    val rows = rememberTrackRows(
        saved,
        onSaveTrack,
        onEnsureSavedTrack,
        onAddToQueue,
        onAddToPlaylist = onAddToPlaylist
    )
    val back = remember { sidetrackBack("Back", onBack).first() }
    val actions = rowActionBarActions(
        rows.bar,
        sidetrackBar(
            chrome,
            onDismissNotice,
            onNowPlaying,
            onDismissNowPlaying,
            back,
            onUndoQueueReplace = onUndoQueueReplace
        )
    )
    return rows to actions
}

/** The two media tiles at the top of an artist's page: the play-context tile and the artwork. */
@Composable
internal fun ArtistTiles(
    artist: Artist,
    playback: PlaybackUiState,
    onPlay: () -> Unit,
    onPlayPause: () -> Unit,
    onArtwork: () -> Unit,
    listState: LazyListState,
    rowIndex: Int,
    /** Extra tiles after the two base ones; an artist page adds its Follow tile here. */
    follow: (@Composable RowScope.() -> Unit)? = null
) {
    SidekitItemRow(Modifier.listFade(listState, rowIndex)) {
        ContextPlayTile(
            artist.key,
            playback.origin,
            playback.transport,
            onPlay,
            onPlayPause,
            Modifier.weight(1f)
        )
        ArtworkTile(
            artist.artRes,
            "${artist.name} artwork",
            Modifier.weight(1f),
            artist.artUrl,
            onClick = onArtwork
        )
        if (follow != null) follow()
    }
}
