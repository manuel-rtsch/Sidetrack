package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import hrln.interactive.sidekit.design.components.SidekitItemRow
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.listFade

/**
 * The artist's whole catalogue, in the same 20-row batches every other list uses.
 *
 * A page rather than a third section on [ArtistScreen]: that screen is a summary (tiles, top
 * tracks, albums), and a catalogue as long as the discography belongs behind its own Back action,
 * like History and Artist radio. [ArtistScreen] links here under Top tracks, and only when the
 * service has a catalogue to show — see [ServiceCapabilities.supportsArtistSongs].
 *
 * The play tile queues the whole catalogue, not just the rows that have arrived: the pages behind
 * Load more still grow the tail, so the queue matches the list once they do.
 */
@Composable
fun ArtistSongsScreen(
    artist: Artist,
    songs: List<Track>,
    saved: SavedUiState,
    playback: PlaybackUiState,
    chrome: ChromeUiState,
    hasMore: Boolean,
    loadingMore: Boolean,
    /** The last page failed; the rows above stay and the footer offers it again. */
    moreFailed: Boolean,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onPlayPause: () -> Unit,
    onTrack: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onSaveTrack: (Track) -> Unit,
    onEnsureSavedTrack: (Track) -> Unit,
    onLoadMore: () -> Unit,
    onNowPlaying: () -> Unit,
    onArtwork: () -> Unit,
    onDismissNotice: () -> Unit,
    onDismissNowPlaying: () -> Unit,
    onAddToPlaylist: ((Track) -> Unit)? = null,
    onUndoQueueReplace: () -> Unit = {}
) {
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
    SidekitScaffold(
        title = artist.name,
        modifier = Modifier.dismissRowActionsOnVerticalKey(rows.bar),
        actions = actions,
        options = sidetrackOptions(onNowPlaying = onNowPlaying)
    ) { padding ->
        val listState = rememberLazyListState()
        rows.bar.DismissOnScroll(listState)
        val songsFocus = rememberLoadMoreFocus()
        // Tiles, then the "All songs" label, then the rows.
        val songsBase = 2
        LaunchedEffect(artist.id) { songsFocus.reset() }
        LoadMoreFocus(songsFocus, listState, songs.size, loadingMore, firstItemListIndex = songsBase)
        val songKeys = remember(songs) { mediaRowKeys(songs) }
        SidekitList(padding, state = listState) {
            var rowIndex = 0
            item(key = "tiles") {
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
                }
            }
            rowIndex += 1
            item(key = "songsLabel") {
                SectionLabel("All songs")
            }
            rowIndex += 1
            itemsIndexed(
                songs,
                key = { index, _ -> songKeys[index] },
                contentType = { _, _ -> "track" }
            ) { index, track ->
                TrackRow(
                    songKeys[index],
                    track,
                    rows,
                    Modifier.listFade(listState, songsBase + index),
                    audio = playback.audioFor(track),
                    focusRequester = songsFocus.requesterFor(index)
                ) { onTrack(track) }
            }
            rowIndex += songs.size
            if (hasMore) {
                val moreIndex = rowIndex
                item(key = "songsMore") {
                    LoadMoreRow(
                        "Load more songs",
                        loadingMore,
                        onLoadMore = {
                            songsFocus.onLoadMore(songs.size)
                            onLoadMore()
                        },
                        Modifier.listFade(listState, moreIndex),
                        focusState = songsFocus,
                        failed = moreFailed
                    )
                }
            }
        }
    }
}
