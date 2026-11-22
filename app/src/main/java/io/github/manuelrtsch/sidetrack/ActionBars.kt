package io.github.manuelrtsch.sidetrack

import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.action.withNotice
import hrln.interactive.sidekit.design.icons.SidekitIcon

/**
 * The bar while a replaced queue can still be taken back: what happened, then Undo. The label is
 * grey and dismisses the offer, so the only live entry is the Undo square.
 */
fun queueReplacedActions(onDismiss: () -> Unit, onUndo: () -> Unit): List<SideAction> = undoBar(
    continueWith = SideAction(
        key = "queueReplaced",
        label = "Queue replaced",
        icon = SidekitIcon.QueueMusic,
        tone = ActionTone.Muted,
        contentDescription = "Queue replaced. Dismiss",
        onClick = onDismiss
    ),
    undo = SideAction.undo(
        key = "undoQueueReplace",
        label = "Undo",
        contentDescription = "Put the replaced queue back",
        onClick = onUndo
    )
)

/**
 * The screen's action bar, with transient confirmations taking precedence over the normal actions: a
 * real notice wins over the queue-replaced offer, which wins over the now-playing confirmation, since
 * the offer has a deadline and the confirmation is decoration.
 */
fun sidetrackActions(
    chrome: ChromeUiState,
    onDismissNotice: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    onDismissNowPlaying: () -> Unit,
    actions: List<SideAction>,
    onUndoQueueReplace: () -> Unit,
    showNowPlayingNotice: Boolean = true
): List<SideAction> = when {
    chrome.notice != null ->
        withNotice(chrome.notice, onDismissNotice, actions, chrome.noticeKind)

    chrome.queueReplaced -> queueReplacedActions(onDismissNotice, onUndoQueueReplace)

    showNowPlayingNotice && chrome.nowPlayingNotice != null ->
        nowPlayingNoticeActions(chrome.nowPlayingNotice, onOpenNowPlaying, onDismissNowPlaying)

    else -> actions
}

fun sidetrackBack(label: String, onBack: () -> Unit) = listOf(SideAction.back("back", label, onBack))

/** The second action on a failed page: the screen keeps its Back, and the retry sits beside it. */
fun retryAction(onRetry: () -> Unit) = SideAction(
    key = "retry",
    label = "Try again",
    icon = SidekitIcon.Undo,
    tone = ActionTone.Primary,
    onClick = onRetry
)

/** The action bar for screens that can change saved state, plus any important notice. */
fun sidetrackBar(
    chrome: ChromeUiState,
    onDismissNotice: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    onDismissNowPlaying: () -> Unit,
    continueWith: SideAction,
    extra: List<SideAction> = emptyList(),
    onUndoQueueReplace: () -> Unit,
    showNowPlayingNotice: Boolean = true
): List<SideAction> = sidetrackActions(
    chrome,
    onDismissNotice,
    onOpenNowPlaying,
    onDismissNowPlaying,
    listOf(continueWith) + extra,
    onUndoQueueReplace,
    showNowPlayingNotice
)

/**
 * The screen's Options menu, built in one place so a shared entry cannot drift in wording, glyph,
 * shape or colour from one screen to the next. The rules live in `docs/menus.md`.
 *
 * - Shape: a destination is a wide labelled button, so pressing Options never asks the user to read
 *   a row of icons. Only Now Playing's playback shortcuts are icon-only, where the bar already reads
 *   as transport. A stateful control is not an option: [toggleAction] puts it beside Back.
 * - Colour: [ActionTone.Primary] for every option, wherever it leads; [ActionTone.Secondary] marks a
 *   toggle that is on; [ActionTone.Destructive] destroys something. Grey is left to the close
 *   button, notices and undo, which are not options.
 *
 * Order is destinations, then list-wide controls, with Now playing last and a destructive entry
 * always last of all. Pass `null` for an entry the state or service cannot offer: it disappears.
 */
fun sidetrackOptions(
    vararg entries: SideAction?,
    onNowPlaying: (() -> Unit)? = null
): List<SideAction> = buildList {
    entries.forEach { entry -> if (entry != null) add(entry) }
    if (onNowPlaying != null) add(nowPlayingOption(onNowPlaying))
    // Destructive last whatever order the caller gave: the bar must never open on that button.
    sortBy { if (it.tone == ActionTone.Destructive) 1 else 0 }
}

/**
 * A destination on this screen: wide, labelled and in the theme accent. Tone marks what an option
 * does to the world (nothing here), never how far it travels.
 */
fun nowPlayingOption(onClick: () -> Unit) = SideAction("nowPlaying", "Now playing", SidekitIcon.MusicNote2, onClick = onClick)

fun settingsOption(onClick: () -> Unit) = SideAction("settings", "Settings", SidekitIcon.Settings, onClick = onClick)

fun aboutOption(onClick: () -> Unit) = SideAction("about", "About Sidetrack", SidekitIcon.BrandAwareness, onClick = onClick)

/** Creating a playlist writes something new, so it is stated (and coloured) like the other actions. */
fun newPlaylistOption(onClick: () -> Unit) = SideAction("createPlaylist", "New playlist", SidekitIcon.PlaylistAdd, tone = ActionTone.Primary, onClick = onClick)

/**
 * Queue what this page holds. [label] lets a show say "Add latest to queue" for the one episode it
 * holds, without inventing a second entry.
 */
fun addToQueueOption(label: String = "Add to queue", onClick: () -> Unit) = SideAction("addToQueue", label, SidekitIcon.PlaylistAdd, tone = ActionTone.Primary, onClick = onClick)

fun artistRadioOption(onClick: () -> Unit) = SideAction("artistRadio", "Artist radio", SidekitIcon.Explore, tone = ActionTone.Primary, onClick = onClick)

/**
 * A square on the playlist page, where Add to queue already owns the bar's one label: three labels
 * plus the close square do not fit. [title] moves into the content description a label would carry.
 */
fun renamePlaylistOption(title: String, onClick: () -> Unit) = SideAction.square(
    key = "renamePlaylist",
    icon = SidekitIcon.Edit,
    contentDescription = "Rename $title",
    onClick = onClick
)

/** Delete belongs last and in the destructive red, like every other delete in the app. */
fun deletePlaylistOption(title: String, onClick: () -> Unit) = SideAction.square(
    key = "deletePlaylist",
    icon = SidekitIcon.DeleteForever,
    contentDescription = "Delete $title",
    onClick = onClick,
    tone = ActionTone.Destructive
)

/**
 * Now Playing's options are the bar's one icon-only set: the screen has no room for labels, and the
 * bar reads as transport beside the tiles below it. Their content description carries the name.
 */
fun openQueueOption(onClick: () -> Unit) = SideAction.square("queue", SidekitIcon.QueueMusic, "Open queue", onClick)

fun songRadioOption(onClick: () -> Unit) = SideAction.square("songRadio", SidekitIcon.Explore, "Song radio", onClick)

fun episodeNotesOption(onClick: () -> Unit) = SideAction.square("notes", SidekitIcon.Docs, "Episode notes", onClick)

/**
 * [sleepTimerOption] as an icon-only square, like the shortcuts beside it: three playback squares and
 * Sidekit's close square already claim 430 of the bar's 460 dp, so a label would truncate to "Sle…".
 * The screen's own "Sleep timer · 23:00" row spells the state out.
 */
fun sleepTimerShortcutOption(active: Boolean, onClick: () -> Unit) = SideAction.square(
    key = "sleepTimer",
    icon = SidekitIcon.Bedtime,
    contentDescription = if (active) "Sleep timer on" else "Sleep timer",
    onClick = onClick,
    tone = if (active) ActionTone.Secondary else ActionTone.Primary
)

/**
 * The sleep timer is a destination with a state, so it keeps its label where there is room: the armed
 * state is written into the words as well as the colour.
 */
fun sleepTimerOption(active: Boolean, onClick: () -> Unit) = SideAction(
    key = "sleepTimer",
    label = if (active) "Sleep timer on" else "Sleep timer",
    icon = SidekitIcon.Bedtime,
    tone = if (active) ActionTone.Secondary else ActionTone.Primary,
    onClick = onClick
)

/**
 * A stateful control in the screen's own action bar, beside its Back: Now Playing's Save, the queue's
 * Shuffle. Green while on, accent while off, and the announcement names the state, so colour is never
 * the only signal.
 */
fun toggleAction(
    key: String,
    icon: SidekitIcon,
    offLabel: String,
    onLabel: String,
    on: Boolean,
    onClick: () -> Unit,
    onIcon: SidekitIcon = icon
) = SideAction.square(
    key = key,
    icon = if (on) onIcon else icon,
    contentDescription = if (on) onLabel else offLabel,
    onClick = onClick,
    tone = if (on) ActionTone.Secondary else ActionTone.Primary
)
