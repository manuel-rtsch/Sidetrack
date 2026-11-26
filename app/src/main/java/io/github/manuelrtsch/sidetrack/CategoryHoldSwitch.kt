package io.github.manuelrtsch.sidetrack

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import hrln.interactive.sidekit.design.action.ChromeState
import hrln.interactive.sidekit.design.action.LocalChrome
import hrln.interactive.sidekit.design.input.HoldToAct
import hrln.interactive.sidekit.design.input.KeypadTiming

// The held-arrow category switch: a tap keeps its old gutter meaning, a hold steps one category.
// Library and Search both grew this machine; it lives here once so the two screens swallow the
// repeats the same way. The category type is the caller's enum, the walk order its ordinal order.

/** One category step per physical hold, even if the key repeats faster than the composable. */
internal class CategoryHoldGate {
    var keyCode: Int? = null
    var switched: Boolean = false

    /**
     * Fires when [reset] releases a held key, so the switch can stand the arrow reservation down
     * once the category hold lets the native corner holds take over on rows above the list.
     */
    var onReset: (() -> Unit)? = null

    fun reset() {
        keyCode = null
        switched = false
        onReset?.invoke()
    }
}

/**
 * Keeps Left/Right reserved only while a list-body row owns the cursor, or while a category switch
 * is still releasing its key. A shared key also has a module chrome hold on it — the Sundial's
 * corners for Search and Playing Now — so standing the reservation down on the category row lets
 * that native hold fire there, while the rows above it keep their own category gesture.
 */
private class ArrowReservation {
    var chrome: ChromeState? = null
    var gate: CategoryHoldGate? = null
    var enabled: Boolean = false
    private var focusedRows = 0
    private var releases: List<() -> Unit> = emptyList()

    fun rowFocusChanged(focused: Boolean) {
        focusedRows += if (focused) 1 else -1
        if (focusedRows < 0) focusedRows = 0
        refresh()
    }

    fun refresh() {
        releases.forEach { it() }
        releases = emptyList()
        val ch = chrome
        val g = gate
        if (enabled && ch != null && (focusedRows > 0 || (g?.switched == true))) {
            releases = listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
                .map { ch.reserveHold(it) }
        }
    }

    fun release() {
        releases.forEach { it() }
        releases = emptyList()
    }
}

/** Swallows the rest of a held arrow at the screen root, before focus can land on the category row. */
internal fun Modifier.consumeCategoryHoldRepeats(gate: CategoryHoldGate): Modifier = onPreviewKeyEvent { event ->
    val native = event.nativeKeyEvent
    if (gate.switched && native.keyCode == gate.keyCode &&
        (native.action == KeyEvent.ACTION_DOWN || native.action == KeyEvent.ACTION_UP)
    ) {
        if (native.action == KeyEvent.ACTION_UP) gate.reset()
        true
    } else {
        false
    }
}

/**
 * A held Left/Right on a row body steps to the adjacent category. A tap keeps its old meaning
 * (into the gutter on Right, back out on Left), so the press is held back until release or the
 * shared keypad long-press threshold fires.
 *
 * Steps skip categories that are not in [supported]: one the user switched off, or one the service
 * cannot answer, is never a destination. [entries] is the caller's category order — the library's
 * filters, the search scopes — and [current] must be one of them.
 *
 * While a body row owns the cursor the switch reserves Left/Right in [LocalChrome], so a module
 * chrome hold on the same key (the Sundial's corner holds for Search and Playing Now) stands down on
 * the rows and the category step is the only hold that fires there. On the category row above the
 * list the reservation is released, so the same native holds work on it.
 */
@Composable
internal fun <T> Modifier.categoryHoldSwitch(
    current: T,
    entries: List<T>,
    supported: Set<T>,
    enabled: Boolean,
    gate: CategoryHoldGate,
    onSwitch: (T) -> Unit
): Modifier {
    val currentCategory by rememberUpdatedState(current)
    val currentSupported by rememberUpdatedState(supported)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnSwitch by rememberUpdatedState(onSwitch)
    val focusManager = LocalFocusManager.current
    val hold = remember {
        HoldToAct(holdMs = KeypadTiming.LONG_PRESS_MS, onHold = { action, _ ->
            if (!currentEnabled || gate.switched) return@HoldToAct
            val step = when (action) {
                KeyEvent.KEYCODE_DPAD_LEFT -> -1
                KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                else -> return@HoldToAct
            }
            val next = adjacentEntry(entries, currentCategory, step, currentSupported) ?: return@HoldToAct
            gate.switched = true
            currentOnSwitch(next)
        })
    }
    LaunchedEffect(enabled) {
        if (!enabled) hold.reset()
    }
    val chrome = LocalChrome.current
    // Reservation is scoped to the rows, not the whole screen: a body row owns the cursor for the
    // category hold, so a module chrome hold on the same corners (the Sundial's Search and Playing
    // Now) must stand down on rows but keep working above them, on the category row. The reservation
    // survives a mid-hold category switch — the press leaves the rows before its release — because
    // the gate keeps [CategoryHoldGate.switched] true until the key comes up, which holds the
    // reservation through the rebuild and its release.
    val reservation = remember { ArrowReservation() }
    reservation.chrome = chrome
    reservation.gate = gate
    LaunchedEffect(enabled, chrome) {
        reservation.enabled = enabled && chrome != null
        reservation.refresh()
    }
    DisposableEffect(gate, reservation, chrome) {
        reservation.refresh()
        gate.onReset = { reservation.refresh() }
        onDispose {
            gate.onReset = null
            reservation.release()
        }
    }
    // The gate is shared by every row and by the screen root, which resets it when the physical
    // key comes up. A row must not reset it on dispose: a hold switches category by disposing
    // the very row that fired it, and clearing the gate mid-press let the rest of the hold
    // through as a second switch. Only this row's own pending press belongs to this composable.
    DisposableEffect(hold) {
        onDispose { hold.reset() }
    }
    return onPreviewKeyEvent { event ->
        if (!currentEnabled) return@onPreviewKeyEvent false
        val native = event.nativeKeyEvent
        when {
            // A hold already stepped. Swallow every repeat until the physical key comes up, even
            // if the input source reports each repeat as a fresh press with a zero repeat count.
            native.action == KeyEvent.ACTION_DOWN &&
                gate.switched &&
                native.keyCode == gate.keyCode -> return@onPreviewKeyEvent true

            native.action == KeyEvent.ACTION_UP && native.keyCode == gate.keyCode -> gate.reset()

            native.action == KeyEvent.ACTION_DOWN && native.repeatCount == 0 -> gate.keyCode = native.keyCode
        }
        when (
            val result = hold.handle(native) { keyCode ->
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> keyCode
                    else -> null
                }
            }
        ) {
            HoldToAct.Result.Pass -> false

            HoldToAct.Result.Consume -> true

            is HoldToAct.Result.Deliver -> {
                // A tap: replay the direction the swallowed press would have performed.
                when (native.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> focusManager.moveFocus(FocusDirection.Left)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> focusManager.moveFocus(FocusDirection.Right)
                }
                true
            }
        }
    }.onFocusChanged { reservation.rowFocusChanged(it.isFocused) }
}

/** The next entry in [step] order that is supported, skipping the slots that are not. */
internal fun <T> adjacentEntry(
    entries: List<T>,
    from: T,
    step: Int,
    supported: Set<T>
): T? {
    var index = entries.indexOf(from) + step
    while (index in entries.indices) {
        val candidate = entries[index]
        if (candidate in supported) return candidate
        index += step
    }
    return null
}
