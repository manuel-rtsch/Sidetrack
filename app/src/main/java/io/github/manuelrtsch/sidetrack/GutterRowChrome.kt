package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.theme.SidekitTheme
import io.github.manuelrtsch.sidetrack.ui.MeatballIcon
import io.github.manuelrtsch.sidetrack.ui.RowActionSquare
import io.github.manuelrtsch.sidetrack.ui.RowGutterState
import io.github.manuelrtsch.sidetrack.ui.rememberGutterSquareFocus

// The two pieces every gutter row repeats: the body modifier that publishes the row's focus and
// draws the selection border, and the meatball square that opens the contextual action bar. The
// artist, collection, history and track rows and the podcast episode row build the same two; what
// differs is their labels and their actions, which stay with the row.

/**
 * The body of a row inside a gutter.
 *
 * Modifier order matters: the row's own requester first, then the caller's (a load-more or a
 * restore target), then whatever the body adds, then the focus bookkeeping the gutter reads, and
 * last the border of the selected row.
 */
@Composable
internal fun gutterRowModifier(
    key: Any,
    selected: Boolean,
    focus: RowGutterState,
    rowFocus: FocusRequester,
    focusRequester: FocusRequester? = null,
    bodyModifier: Modifier = Modifier
): Modifier = Modifier
    .focusRequester(rowFocus)
    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
    .then(bodyModifier)
    .onFocusChanged { state ->
        if (state.isFocused) focus.rowFocused(key) else focus.rowUnfocused(key)
    }
    .then(
        if (selected) {
            Modifier.border(2.dp, SidekitTheme.colors.light, SidekitTheme.shapes.item)
        } else {
            Modifier
        }
    )

/** The single square that opens a row's contextual actions in the screen's bar. */
@Composable
internal fun GutterMeatball(
    key: Any,
    title: String,
    focus: RowGutterState,
    bar: RowActionBarState,
    rowFocus: FocusRequester,
    actions: List<RowAction>,
    label: String = "Actions for $title"
) {
    val squareFocus = rememberGutterSquareFocus(key, focus)
    RowActionSquare(
        color = SidekitTheme.colors.surfaceA4,
        label = label,
        content = { MeatballIcon() },
        modifier = squareFocus,
        onClick = {
            focus.touchDismissed(key)
            bar.show(RowActionSelection(key, title, actions, rowFocus))
        }
    )
}
