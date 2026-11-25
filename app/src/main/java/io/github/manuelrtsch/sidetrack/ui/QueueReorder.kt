package io.github.manuelrtsch.sidetrack.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import io.github.manuelrtsch.sidetrack.rememberMediaRowPitch
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Pick-up-and-drop reordering of the queue's upcoming tail, touch only; the keypad works the same
 * state, [step]ping the data so the row scrolls into view in the same measure pass ([origin] for
 * Back). Rows span one media-row pitch, so the drop slot is rows travelled, not layout hit-testing.
 */
@Stable
class QueueReorderState internal constructor(
    internal val listState: LazyListState,
    private val pitchPx: Float
) {
    /** Upcoming index currently picked up, or null when nothing is being dragged. */
    var from by mutableStateOf<Int?>(null)
        private set

    /** Upcoming index the picked-up row would drop into; -1 while idle. */
    var to by mutableIntStateOf(-1)
        private set

    /** Vertical translation of the picked-up row, in pixels, including auto-scroll compensation. */
    var translation by mutableFloatStateOf(0f)
        private set

    /** True while the lift came from the keypad button rather than a touch long press. */
    var keypad by mutableStateOf(false)
        private set

    /** Upcoming index the keypad lift started from; -1 while idle. Back restores it. */
    var origin by mutableIntStateOf(-1)
        private set

    /** Whether this lift actually changed the order. A no-op drop must not alter shuffle. */
    var hasMoved by mutableStateOf(false)
        private set

    private var pointerDelta = 0f
    private var scrolled = 0f
    private var startOffset = 0f

    /** Where inside the row the finger landed, so the edge test can use the finger itself. */
    private var touchOffset = 0f
    private var count = 0

    /** The list key of the row being lifted, to read its real layout offset while it moves. */
    private var liftKey: Any? = null

    /** The gesture or keypad action that opened this lift; only it may end it. */
    private var owner: Any? = null

    val active: Boolean get() = from != null

    internal fun begin(
        index: Int,
        count: Int,
        listOffset: Float,
        keypad: Boolean = false,
        owner: Any? = null,
        key: Any? = null,
        touch: Float = 0f
    ) {
        from = index
        to = index
        origin = index
        this.count = count
        this.keypad = keypad
        this.owner = owner
        this.liftKey = key
        startOffset = listOffset
        touchOffset = touch
        pointerDelta = 0f
        scrolled = 0f
        translation = 0f
        hasMoved = false
    }

    /**
     * Moves the picked-up row with the finger, handing back the move the caller commits, or null.
     * The queue reorders as the finger crosses each row so its slot travels with it. Only slots
     * that are on screen are eligible: a slot outside the viewport is not composed, and losing the
     * row would end the drag. [translation] then puts the row back under the finger.
     */
    internal fun drag(dy: Float): Pair<Int, Int>? {
        val current = from ?: return null
        if (keypad) return null
        pointerDelta += dy
        // The row may only take a slot that is on screen: a slot outside the viewport is not
        // composed, so the list would dispose the row and end the drag on the spot.
        val info = listState.layoutInfo
        val firstOnScreen = origin + floor((info.viewportStartOffset + scrolled - startOffset) / pitchPx).toInt()
        val lastOnScreen = origin + floor((info.viewportEndOffset + scrolled - startOffset) / pitchPx).toInt()
        val low = firstOnScreen.coerceIn(0, (count - 1).coerceAtLeast(0))
        val high = lastOnScreen.coerceIn(low, (count - 1).coerceAtLeast(0))
        val desired = (origin + ((pointerDelta + scrolled) / pitchPx).roundToInt()).coerceIn(low, high)
        if (desired == current) {
            updateTranslation()
            return null
        }
        from = desired
        to = desired
        hasMoved = true
        updateTranslation()
        return current to desired
    }

    /**
     * Keypad move: steps the lifted row one slot and hands back the move the caller must commit to
     * the queue, or null at the ends. The data move is immediate so the list can scroll the row
     * into view in the same measure pass; [origin] remembers where it started.
     */
    internal fun step(delta: Int): Pair<Int, Int>? {
        if (!keypad) return null
        val current = from ?: return null
        val next = (current + delta).coerceIn(0, (count - 1).coerceAtLeast(0))
        if (next == current) return null
        from = next
        to = next
        hasMoved = true
        translation = 0f
        return current to next
    }

    /**
     * Whatever puts the row back under the finger: the finger's travel plus the auto-scroll, less
     * the distance its slot has already moved with the data. The result is clamped to the list
     * viewport: past the top row the list cannot scroll any further, and a row drawn above the
     * viewport is clipped away, which reads as if the drag had been cancelled.
     */
    private fun updateTranslation() {
        val current = from ?: return
        val info = listState.layoutInfo
        // The item's real offset already accounts for the moves and for the scrolling, including
        // any scroll the list keeps for its anchor as keyed items move across it. Reconstructing
        // that from the drag start drifts once the row passes the current-track item.
        val layout = info.visibleItemsInfo
        val itemTop = layout.firstOrNull { it.key == liftKey }?.offset
        if (itemTop == null) {
            // Nothing laid out yet (unit tests, the first frame): fall back to the travel math.
            // Otherwise the row is only momentarily absent; keep what it had rather than jump.
            if (layout.isEmpty()) {
                translation = pointerDelta + scrolled - (current - origin) * pitchPx
            }
            return
        }
        translation = clampedTop(info) - itemTop
    }

    /** Whether [key] is the lifted row, whichever slot the data has moved it to. */
    internal fun isLifted(key: Any?): Boolean = from != null && key != null && liftKey == key

    /**
     * The translation the row draws with, computed against the layout it is drawn into. Reading
     * the slot here instead of storing it keeps a fast move — which changes the data after the
     * translation was computed — from drawing the row a slot away for one frame, and the read of
     * [translation] makes the draw layer follow the finger between layout changes.
     */
    internal fun drawTranslation(key: Any?): Float {
        if (!isLifted(key)) return 0f
        // Snapshot read: every finger move stores [translation], which invalidates the layer.
        val fallback = translation
        val info = listState.layoutInfo
        val itemTop = info.visibleItemsInfo.firstOrNull { it.key == key }?.offset
        return if (itemTop != null) clampedTop(info) - itemTop else fallback
    }

    /** The finger's top, kept inside the viewport so the row is never drawn clipped away. */
    private fun clampedTop(info: androidx.compose.foundation.lazy.LazyListLayoutInfo): Float {
        val fingerTop = startOffset + pointerDelta
        // A list that has not been laid out yet (unit tests, the first frame) has an empty
        // viewport; clamping to it would park every row at zero.
        return if (info.viewportEndOffset > info.viewportStartOffset) {
            clampToViewport(
                fingerTop,
                info.viewportStartOffset.toFloat(),
                info.viewportEndOffset.toFloat(),
                pitchPx
            )
        } else {
            fingerTop
        }
    }

    /** Whether [token] is the gesture that opened the current lift. */
    internal fun owns(token: Any?): Boolean = token != null && owner === token

    /**
     * Ends the drag. Every move was committed as it happened, so there is nothing left to drop.
     * Passing a [token] makes the call a no-op unless that gesture owns the lift: a row the list
     * disposes cancels its own gesture even when it never picked anything up, and that must not
     * end the lift of the row under the finger.
     */
    internal fun end(token: Any? = null) {
        if (token != null && !owns(token)) return
        reset()
    }

    internal fun cancel(token: Any? = null) {
        if (token != null && !owns(token)) return
        reset()
    }

    private fun reset() {
        from = null
        to = -1
        origin = -1
        translation = 0f
        pointerDelta = 0f
        scrolled = 0f
        touchOffset = 0f
        keypad = false
        hasMoved = false
        liftKey = null
        owner = null
    }

    /**
     * Keeps a long drag alive past the viewport edges: while the picked-up row is near the top or
     * bottom, scroll a fraction of a pitch per frame. The scrolled pixels are added to the row's
     * translation so it stays under the finger, and the drop slot advances with them.
     */
    internal suspend fun autoScroll(onMove: (Int, Int) -> Unit = { _, _ -> }) {
        while (active && !keypad) {
            withFrameNanos { }
            val info = listState.layoutInfo
            val edge = pitchPx * 0.6f
            // The edge is the finger's, not the row's top: a row picked up near the top of the
            // screen would otherwise start scrolling before the finger moves at all.
            val finger = startOffset + touchOffset + pointerDelta
            val nearTop = info.viewportStartOffset + edge
            val nearBottom = info.viewportEndOffset - edge
            val delta = when {
                finger < nearTop -> (finger - nearTop).coerceAtLeast(-pitchPx)
                finger > nearBottom -> (finger - nearBottom).coerceAtMost(pitchPx)
                else -> 0f
            }
            if (delta == 0f) continue
            val current = from ?: continue
            // Never let the scroll carry the lifted row's slot out of composition: the list would
            // dispose the row, which Compose reports as onDragCancel and the drag would end early.
            // A negative delta scrolls the content down, toward the viewport's bottom edge.
            val itemTop = info.visibleItemsInfo.firstOrNull { it.key == liftKey }?.offset
            if (delta < 0 && itemTop != null && itemTop + pitchPx > info.viewportEndOffset) continue
            if (delta > 0 && itemTop != null && itemTop < info.viewportStartOffset) continue
            // Stop at the ends of the queue as well.
            if (delta < 0 && current <= 0) continue
            if (delta > 0 && current >= count - 1) continue
            val step = (delta * 0.35f).coerceIn(-MAX_AUTO_SCROLL_PX, MAX_AUTO_SCROLL_PX)
            val consumed = listState.scrollBy(step)
            if (consumed != 0f) {
                scrolled += consumed
                // The scroll moves the slot without a pointer event: commit the slot it landed in
                // every frame, or the data falls behind the pixels and the row jumps several slots
                // on the next move (and keeps scrolling as if nothing had moved).
                drag(0f)?.let { (from, to) -> onMove(from, to) }
                updateTranslation()
            }
        }
    }
}

/** How far the auto-scroll may travel in one frame, so a held finger cannot fling the list. */
private const val MAX_AUTO_SCROLL_PX = 16f

/** The row's top, kept far enough inside the viewport that the whole row stays drawn. */
internal fun clampToViewport(
    visualTop: Float,
    viewportStart: Float,
    viewportEnd: Float,
    pitch: Float
): Float {
    val lowest = (viewportEnd - pitch).coerceAtLeast(viewportStart)
    return visualTop.coerceIn(viewportStart, lowest)
}

/** One long-press drag at a time, measured against the SideKit row pitch. */
@Composable
fun rememberQueueReorderState(listState: LazyListState): QueueReorderState {
    val pitch = with(LocalDensity.current) { rememberMediaRowPitch().toPx() }
    return remember(listState, pitch) { QueueReorderState(listState, pitch) }
}

/** Runs the auto-scroll loop for as long as a row is picked up. */
@Composable
fun QueueReorderAutoScroll(state: QueueReorderState, onMove: (Int, Int) -> Unit = { _, _ -> }) {
    LaunchedEffect(state.active) {
        if (state.active) state.autoScroll(onMove)
    }
}

/**
 * Long press to pick the row at [index] up, drag, release to drop. The detector is outside the
 * row's horizontal swipe: a horizontal move cancels the pending long press, a vertical one after
 * the press cancels the row's tap, so picking a track up never also opens it.
 */
@Composable
fun Modifier.queueReorder(
    state: QueueReorderState,
    index: Int,
    count: Int,
    key: Any,
    onPickUp: () -> Unit = {},
    onDrop: (from: Int, to: Int) -> Unit,
    onDropEnd: () -> Unit = {}
): Modifier {
    // The row's index changes every time the drag reorders the queue, so the gesture must not be
    // keyed on it: a restarted pointer input would drop the row mid-drag. The latest values are
    // read through these instead.
    val latestIndex by rememberUpdatedState(index)
    val latestCount by rememberUpdatedState(count)
    val latestPickUp by rememberUpdatedState(onPickUp)
    val latestDrop by rememberUpdatedState(onDrop)
    val latestDropEnd by rememberUpdatedState(onDropEnd)
    return pointerInput(state, key) {
        // Every pointer-input coroutine gets its own token. The list disposes rows as it scrolls —
        // including, during a drag, rows that are only passing out of composition — and Compose
        // reports that cancellation to `detectDragGesturesAfterLongPress` as `onDragCancel` even
        // for a row that never picked anything up. Without a token such a cancel would end the
        // lift of the row actually under the finger.
        val gesture = Any()
        fun finishDrag() {
            if (!state.owns(gesture)) return
            val moved = state.hasMoved
            state.end(gesture)
            if (moved) latestDropEnd()
        }
        try {
            detectDragGesturesAfterLongPress(
                onDragStart = { touch ->
                    // A keypad lift or another row's drag already owns the state.
                    if (!state.active) {
                        val info = state.listState.layoutInfo
                        val offset = info.visibleItemsInfo.firstOrNull { it.key == key }?.offset ?: 0
                        state.begin(
                            latestIndex,
                            latestCount,
                            offset.toFloat(),
                            owner = gesture,
                            key = key,
                            touch = touch.y
                        )
                        latestPickUp()
                    }
                },
                onDrag = { change, delta ->
                    if (state.owns(gesture)) {
                        change.consume()
                        val move = state.drag(delta.y)
                        move?.let { (from, to) -> latestDrop(from, to) }
                    }
                },
                onDragEnd = { finishDrag() },
                onDragCancel = { finishDrag() }
            )
        } finally {
            // The list can dispose this row outright while it owns the lift: never leave one
            // behind. Moves already committed during the drag still count, so commit the
            // hand-picked order once.
            finishDrag()
        }
    }
}
