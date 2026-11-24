package io.github.manuelrtsch.sidetrack

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import hrln.interactive.sidekit.design.action.ActionMotion
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.LocalChrome
import hrln.interactive.sidekit.design.action.NoticeKind
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.icons.SidekitIcon

/*
 * The three prompts an edit can raise, in one bar shape: what the user has to decide takes the
 * leading slot, a close square sits on the right.
 *
 * - **Restore** — the edit is applied and can be taken back. Grey, the Undo glyph, rising in place,
 *   and the label is the inverse: "Restore queue", "Restore X".
 * - **Confirm** — the edit can never be taken back, so it is asked first. Red, the action keeps its
 *   own glyph, and the label is a question: "Clear cache?", "Sign out?".
 * - **Notice** — nothing to decide, only to read. Grey, and the message is the slot.
 *
 * The close square **never changes the world**. In a restore the edit stays; in a confirm nothing
 * happens because nothing has happened yet; in a notice it only goes away. That is why one glyph can
 * accept in one bar and cancel in the next: it always means "take this bar away". The Back key does
 * the same thing and stays on the page. The cursor lands on the square in all three, so Enter is
 * always the press that changes nothing.
 *
 * Labels stay English on purpose: every string in this app is, and taking Sidekit's localised
 * default would make one button change language under a screen that is not translated.
 */

/** The leading slot of a restore: grey, same glyph as the edit's way back, rises in place. */
fun restoreAction(key: String, label: String, description: String, onClick: () -> Unit): SideAction = SideAction(
    key = key,
    label = label,
    icon = SidekitIcon.Undo,
    tone = ActionTone.Muted,
    motion = ActionMotion.Up,
    contentDescription = description,
    onClick = onClick
)

/** The offer bar: the named inverse takes the leading slot, a close square accepts the change. */
fun restoreBar(restore: SideAction, dismissKey: String, onDismiss: () -> Unit): List<SideAction> = listOf(restore, closeSquare(dismissKey, "Dismiss", onDismiss))

/** The leading slot of a confirmation: red, because the edit cannot be taken back afterwards. */
fun confirmAction(
    key: String,
    label: String,
    description: String,
    icon: SidekitIcon? = null,
    onClick: () -> Unit
): SideAction = SideAction(
    key = key,
    label = label,
    icon = icon,
    contentDescription = description,
    tone = ActionTone.Destructive,
    onClick = onClick
)

/** The confirmation bar: the destructive action takes the leading slot, the close square cancels. */
fun confirmBar(confirm: SideAction, cancelKey: String, onCancel: () -> Unit): List<SideAction> = listOf(confirm, closeSquare(cancelKey, "Cancel", onCancel))

/** The notice bar: the message is read and dismissed, and the close square only shortens the wait. */
fun noticeBar(
    notice: String?,
    onDismiss: () -> Unit,
    actions: List<SideAction>,
    kind: NoticeKind = NoticeKind.Other
): List<SideAction> = if (notice == null) {
    actions
} else {
    listOf(
        SideAction.notice(key = "notice", message = notice, kind = kind, onClick = onDismiss),
        closeSquare("dismissNotice", "Dismiss the notice", onDismiss)
    )
}

private fun closeSquare(key: String, description: String, onClick: () -> Unit) = SideAction.square(
    key = key,
    icon = SidekitIcon.Close,
    contentDescription = description,
    tone = ActionTone.Muted,
    onClick = onClick
)

/**
 * The Activity's slot for a prompt's Back key. A prompt can outlive the page that raised it, so the
 * handler is installed by whoever owns it and removed when it goes away; handlers are stacked, so a
 * screen clearing its own keypad override cannot take a prompt's handler with it.
 */
internal val LocalPromptBack = staticCompositionLocalOf<((() -> Boolean) -> () -> Unit)> { { _ -> {} } }

/**
 * Binds a prompt to the keys: Back closes it and stays on the page, and the cursor lands on the
 * close square so Enter is the press that changes nothing and Left is the only step before the
 * decision. A restore's decision is the edit it already made; a confirm's is the edit it has not.
 */
@Composable
fun PromptBack(live: Boolean, onDismiss: () -> Unit) {
    val chrome = LocalChrome.current
    val focusManager = LocalFocusManager.current
    val dismiss by rememberUpdatedState(onDismiss)
    BackHandler(enabled = live, onBack = onDismiss)
    val push = LocalPromptBack.current
    DisposableEffect(live, push) {
        if (!live) return@DisposableEffect onDispose {}
        val remove = push {
            dismiss()
            true
        }
        onDispose { remove() }
    }
    LaunchedEffect(live) {
        if (!live) return@LaunchedEffect
        // The new slots exist a frame after the state flips. The bar's own requester lands on the
        // leading button, so one step right reaches the close square.
        repeat(3) {
            withFrameNanos { }
            if (chrome?.focusActionBar() == true) {
                focusManager.moveFocus(FocusDirection.Right)
                return@LaunchedEffect
            }
        }
    }
}
