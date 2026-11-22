package io.github.manuelrtsch.sidetrack.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import io.github.manuelrtsch.sidetrack.rememberMediaRowMetrics
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** How far a left swipe must travel before it reveals the gutter. */
private val SWIPE_REVEAL_THRESHOLD = 36.dp

/**
 * Which row of a list is showing its gutter, and why. The state is per list, not per row, so one row
 * at a time is open and every gutter in the app opens the same way.
 *
 * Two reveals behave differently on purpose:
 * - Focus waits for the settle, so scrubbing Up/Down does not flash buttons on every row it passes,
 *   and the squares stay while the focus is on the row or inside its own gutter.
 * - A left swipe reveals immediately, since a touch screen has no focus border to wait for, and the
 *   squares stay until dismissed. The queue uses the same swipe, so no second gesture is needed.
 */
@Stable
open class RowGutterState {
    /** The row holding focus, or null. */
    var focusedKey: Any? by mutableStateOf(null)
        internal set

    /** The focused row whose gutter is open, once the focus has settled. */
    var openKey: Any? by mutableStateOf(null)
        internal set

    /** The row revealed by a left swipe, or null. */
    var touchedKey: Any? by mutableStateOf(null)
        internal set

    /**
     * True while [key]'s squares should be showing. A swipe reveal is exclusive: swiping one row
     * while the focus sits on another would otherwise open two gutters.
     */
    fun isOpen(key: Any): Boolean {
        val touched = touchedKey
        return if (touched != null) touched == key else openKey == key
    }

    fun rowFocused(key: Any) {
        // The keypad takes over: a swipe elsewhere yields as soon as the focus lands on another row.
        if (touchedKey != null && touchedKey != key) touchedKey = null
        focusedKey = key
    }

    fun rowUnfocused(key: Any) {
        if (focusedKey == key) focusedKey = null
    }

    /**
     * A left swipe on [key]. Deliberately not debounced: crossing the reveal threshold was a
     * deliberate act, and the squares are the only feedback it gets.
     */
    fun reveal(key: Any) {
        // Drop whatever the focus had open: a pending settle would otherwise open the focused row's
        // gutter a frame later, beside this one.
        if (openKey != key) openKey = null
        touchedKey = key
    }

    /** Dismisses the swipe reveal, either for one row or for whichever row has it. */
    fun touchDismissed(key: Any? = null) {
        if (key == null || touchedKey == key) touchedKey = null
    }
}

/** Remembers a list's gutter state and settles the focus into it. */
@Composable
fun rememberRowGutterState(): RowGutterState {
    val state = remember { RowGutterState() }
    state.Settle()
    return state
}

/**
 * The shared row shell for a focus/swipe gutter. It owns the slide animation, the swipe reveal and
 * the tap-to-dismiss overlay; callers provide the row content and the squares.
 */
@Composable
fun GutterRow(
    key: Any,
    state: RowGutterState,
    squares: Int,
    modifier: Modifier = Modifier,
    content: @Composable (gutterVisible: Boolean) -> Unit,
    actions: @Composable () -> Unit
) {
    val square = rememberMediaRowMetrics().height
    val open = state.isOpen(key)
    val targetGutter = if (open) square * squares + GutterGap * (squares - 1) else 0.dp
    val gutter by animateDpAsState(targetGutter, tween(GUTTER_ANIM_MS), label = "rowGutter")
    val gutterAlpha by animateFloatAsState(
        if (targetGutter > 0.dp) 1f else 0f,
        tween(GUTTER_ANIM_MS),
        label = "rowGutterAlpha"
    )
    val density = LocalDensity.current
    val gutterPx = with(density) { gutter.toPx() }
    val view = LocalView.current
    var rowHeightPx by remember { mutableStateOf(0) }
    val rowHeight = if (rowHeightPx > 0) with(density) { rowHeightPx.toDp() } else square
    Box(modifier) {
        Box(
            Modifier
                .graphicsLayer { translationX = -gutterPx }
                .onSizeChanged { rowHeightPx = it.height }
                .rowSwipeReveal {
                    if (state.touchedKey != key) {
                        view.armedHaptic()
                        state.reveal(key)
                    }
                }
        ) {
            content(gutter > 0.dp)
        }
        // While a swipe holds the gutter open, a tap on the row closes it rather than starting the
        // item, which is the same contract a swiped-open row had.
        if (state.touchedKey == key && gutter > 0.dp) {
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(key) {
                        detectTapGestures { state.touchDismissed(key) }
                    }
            )
        }
        // Composed only while the gutter has width, so a faded-out square can never be tapped.
        if (gutter > 0.dp) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .height(rowHeight)
                    .graphicsLayer {
                        alpha = gutterAlpha
                        translationX = (1f - gutterAlpha) * GUTTER_SLIDE.toPx()
                    }
            ) {
                Row(
                    Modifier.fillMaxHeight(),
                    horizontalArrangement = Arrangement.spacedBy(GutterGap)
                ) { actions() }
            }
        }
    }
}

/**
 * Keeps the gutter open while the keypad is inside one of its squares. The first "not focused"
 * event a freshly attached square reports must not be mistaken for the row losing focus.
 */
@Composable
fun Modifier.gutterSquareFocus(key: Any, state: RowGutterState): Modifier {
    var squareHadFocus by remember(key) { mutableStateOf(false) }
    return onFocusChanged { focus ->
        when {
            focus.isFocused -> {
                squareHadFocus = true
                state.rowFocused(key)
            }

            squareHadFocus -> state.rowUnfocused(key)
        }
    }
}

/**
 * Debounces the focus into the gutter, so scrubbing Up/Down does not flash buttons on every row.
 *
 * Deliberately silent: the focus ring already says where the keypad is, and vibrating here would
 * buzz on every row a held D-pad passes. See [Haptics].
 */
@Composable
internal fun RowGutterState.Settle() {
    LaunchedEffect(focusedKey) {
        val target = focusedKey
        if (target == null) {
            // A move from a row into its own gutter arrives as two events: the row goes false, the
            // square goes true. Give the square one frame before treating the row as left for good.
            withFrameNanos { }
            if (focusedKey == null) openKey = null
            return@LaunchedEffect
        }
        // Close the previous row's gutter as soon as the focus border moves.
        if (openKey != target) openKey = null
        delay(GUTTER_DELAY_MS)
        openKey = target
    }
}

/**
 * Reports a left swipe on the row underneath. The gesture only starts after horizontal touch slop,
 * so a vertical list scroll still belongs to the list; once it is horizontal, the row's own click
 * is cancelled and the swipe is consumed.
 */
@Composable
fun Modifier.rowSwipeReveal(onReveal: () -> Unit): Modifier {
    val current by rememberUpdatedState(onReveal)
    val threshold = with(LocalDensity.current) { SWIPE_REVEAL_THRESHOLD.toPx() }
    return pointerInput(threshold) {
        var travelled = 0f
        var revealed = false
        detectHorizontalDragGestures(
            onDragStart = {
                travelled = 0f
                revealed = false
            },
            onHorizontalDrag = { change, dragAmount ->
                change.consume()
                travelled += dragAmount
                if (!revealed && travelled <= -threshold) {
                    revealed = true
                    current()
                }
            }
        )
    }
}

/**
 * Reports a long press on the row underneath and swallows the release, so holding a row does not
 * also count as a tap. Runs in the [PointerEventPass.Initial] pass, ahead of the row's own click
 * and the swipe: releasing early or dragging far enough for the swipe to take over cancels it.
 */
@Composable
fun Modifier.rowLongPress(onLongPress: () -> Unit): Modifier {
    // The callback is a new lambda on every composition, and keying the gesture on it would restart
    // the press detector on any recomposition, including the one a finger going down causes.
    val current by rememberUpdatedState(onLongPress)
    return pointerInput(Unit) {
        listen(current)
    }
}

private suspend fun PointerInputScope.listen(longPress: () -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val timeout = viewConfiguration.longPressTimeoutMillis
        val touchSlop = viewConfiguration.touchSlop
        var held = false
        // True once the press released or a drag took it over; false while it is still down.
        var released = false
        var settledInTime = false
        // Compose's pointer-event scope has its own withTimeoutOrNull, which reports the timeout as
        // null rather than throwing, hence the flag above deciding the outcome.
        withTimeoutOrNull(timeout) {
            var pressed = true
            while (pressed) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                when {
                    // The pointer is gone: the gesture ended under us.
                    change == null -> pressed = false

                    // A long press is a still hold: past touch slop this belongs to the list's scroll
                    // or swipe, not to the gutter.
                    (change.position - down.position).getDistance() > touchSlop -> {
                        pressed = false
                        released = true
                    }

                    // A drag (the swipe) or the row's own click took the gesture over.
                    change.isConsumed -> {
                        pressed = false
                        released = true
                    }

                    !change.pressed -> {
                        pressed = false
                        released = true
                        // A hold can also be long with the timeout never running: a busy looper, or
                        // input arriving as one batch. The timestamps still say how long the finger
                        // was down, and the release must be swallowed here so it is not also a tap.
                        if (change.uptimeMillis - down.uptimeMillis >= timeout) {
                            held = true
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
            settledInTime = true
        }
        if (!settledInTime) held = true
        if (!held) return@awaitEachGesture
        longPress()
        if (released) return@awaitEachGesture
        // Swallow the rest of the gesture: the release must not turn into a tap as well.
        var pressed = true
        while (pressed) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { it.consume() }
            pressed = event.changes.any { it.pressed }
        }
    }
}
