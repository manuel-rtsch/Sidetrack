package io.github.manuelrtsch.sidetrack

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
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
 * - **Restore** — the edit is applied and can be taken back. The theme accent, the Undo glyph,
 *   rising in place, and the label is the inverse: "Restore queue", "Restore X".
 * - **Confirm** — the edit can never be taken back, so it is asked first. Red, the action keeps its
 *   own glyph, and the label is a question: "Clear cache?", "Sign out?".
 * - **Notice** — nothing to decide, only to read. Grey, and the message is the slot.
 *
 * Tone follows what the press does, not how loud the bar is: accent is the one action on offer, red
 * is the action that cannot be taken back, and grey is left to the close square and to notices —
 * the two things that are not choices. The square must not share a tone with a restore, or the bar
 * shows two buttons of equal weight and only the label says which one changes the world.
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

/**
 * The key of the close square in every prompt bar. It is the same string everywhere on purpose:
 * one prompt owns the bar at a time, and the square means the same thing in all of them, so the
 * bar morphs it in place instead of swapping one square for another — and
 * [PromptBack] can name it without every call site passing its own key.
 */
const val PROMPT_CLOSE_KEY = "promptClose"

/** The leading slot of a restore: the bar's one action, with the glyph of the edit's way back. */
fun restoreAction(key: String, label: String, description: String, onClick: () -> Unit): SideAction = SideAction(
    key = key,
    label = label,
    icon = SidekitIcon.Undo,
    tone = ActionTone.Primary,
    motion = ActionMotion.Up,
    contentDescription = description,
    onClick = onClick
)

/** The offer bar: the named inverse takes the leading slot, a close square accepts the change. */
fun restoreBar(restore: SideAction, onDismiss: () -> Unit): List<SideAction> = listOf(restore, closeSquare("Dismiss", onDismiss))

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
fun confirmBar(confirm: SideAction, onCancel: () -> Unit): List<SideAction> = listOf(confirm, closeSquare("Cancel", onCancel))

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
        closeSquare("Dismiss the notice", onDismiss)
    )
}

private fun closeSquare(description: String, onClick: () -> Unit) = SideAction.square(
    key = PROMPT_CLOSE_KEY,
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
 *
 * The cursor also goes back where it came from once the prompt is gone, whichever way it ended:
 * [returnTo] names the row that took the place of a removed one, and when it is absent or cannot
 * take focus the page's own memory decides. A prompt must never leave the keypad on a bar that is
 * no longer showing.
 */
@Composable
fun PromptBack(live: Boolean, onDismiss: () -> Unit, returnTo: (() -> Boolean)? = null) {
    val chrome = LocalChrome.current
    val dismiss by rememberUpdatedState(onDismiss)
    val back by rememberUpdatedState(returnTo)
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
        // Remember the row the user was on, so closing the prompt can put them back on it.
        chrome?.saveContentFocus()
        // The new slots exist a frame after the state flips: the host refuses a key it has not
        // built yet, so retry a few frames instead of guessing when the bar is ready.
        repeat(3) {
            withFrameNanos { }
            if (chrome?.focusActionBar(PROMPT_CLOSE_KEY) == true) return@LaunchedEffect
        }
    }
    var wasLive by remember { mutableStateOf(false) }
    LaunchedEffect(live) {
        if (live) {
            wasLive = true
            return@LaunchedEffect
        }
        if (!wasLive) return@LaunchedEffect
        wasLive = false
        // The bar has to leave composition before the page can take the cursor back.
        withFrameNanos { }
        if (back?.invoke() != true) chrome?.restoreContentFocus()
    }
}
