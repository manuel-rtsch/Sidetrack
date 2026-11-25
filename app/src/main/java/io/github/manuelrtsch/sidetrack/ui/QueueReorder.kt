package io.github.manuelrtsch.sidetrack.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListLayoutInfo
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
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import io.github.manuelrtsch.sidetrack.rememberMediaRowPitch
import kotlin.math.roundToInt

/**
 * Pick-up-and-drop reordering of the queue's upcoming tail, touch only; the keypad works the same
 * state, [step]ping the data so the row scrolls into view in the same measure pass ([origin] for
 * Back).
 *
 * The touch drag is a single gesture on the list, not one per row. A row's own pointer input dies
 * the moment the list disposes that row, and a drag past the viewport edges does exactly that, so
 * the lift is owned by [queueReorderGestures], which the list container keeps for its whole life.
 * The finger's position is all the gesture records; the slot and the row's draw offset are read
 * from the layout every frame, so the row rides the finger no matter what the data or the scroll
 * does underneath it.
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

    /** Vertical translation of the picked-up row, in pixels, for the draw layer to follow. */
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

    /** Where the finger is along the list, in item-offset space — free to leave the viewport. */
    private var finger = 0f

    /** The lifted row's top less the finger at pick-up: the row rides at that fixed distance. */
    private var grab = 0f

    private var startFinger = 0f
    private var startOffset = 0f
    private var count = 0

    /** The list index of upcoming slot 0, so a measured row can name the slot it stands for. */
    private var firstItem = 0

    /** The list key of the row being lifted. */
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
        touch: Float = 0f,
        firstItem: Int = 0
    ) {
        from = index
        to = index
        origin = index
        this.count = count
        this.keypad = keypad
        this.owner = owner
        this.liftKey = key
        this.firstItem = firstItem
        startOffset = listOffset
        // The finger landed [touch] below the row's top; keeping that distance is what makes the
        // row feel picked up where it was grabbed instead of jumping under the fingertip.
        finger = listOffset + touch
        grab = -touch
        startFinger = finger
        translation = 0f
        hasMoved = false
    }

    /** Moves the finger by [dy] and hands back the move the caller commits, or null. */
    internal fun drag(dy: Float): Pair<Int, Int>? {
        if (from == null || keypad) return null
        finger += dy
        return follow()
    }

    /** Puts the finger at [listY] (item-offset space) and hands back the move to commit, or null. */
    internal fun dragTo(listY: Float): Pair<Int, Int>? {
        if (from == null || keypad) return null
        finger = listY
        return follow()
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

    /** Reads the slot the row now covers and commits it, keeping the row drawn under the finger. */
    private fun follow(): Pair<Int, Int>? {
        val current = from ?: return null
        val desired = slotUnderRow().coerceIn(0, (count - 1).coerceAtLeast(0))
        return commit(current, desired)
    }

    private fun commit(current: Int, desired: Int): Pair<Int, Int>? {
        if (desired == current) {
            updateTranslation()
            return null
        }
        from = desired
        to = desired
        hasMoved = true
        holdScroll()
        updateTranslation()
        return current to desired
    }

    /**
     * Keeps the viewport still while the data is reordered. LazyList remembers the first visible
     * row by key and, when a reorder moves that row, shifts the whole list a row to keep it in
     * place: near the head of the queue the row being dragged *is* the first visible one, so every
     * slot it crossed threw the list a row down and, because the slot is read from the layout, the
     * next frame read it back and threw it up again — a visible flicker for as long as the finger
     * held at the edge. Asking for the position the list already has drops that memory, so a
     * reorder moves rows and never the viewport.
     */
    private fun holdScroll() {
        val index = listState.firstVisibleItemIndex
        val offset = listState.firstVisibleItemScrollOffset
        listState.requestScrollToItem(index, offset)
    }

    /** Where the lifted row's top sits, in item-offset space: wherever the finger has carried it. */
    private fun desiredTop() = finger + grab

    /**
     * The slot the lifted row covers: the row whose span holds the lifted row's centre. Rows
     * reorder under the finger and the list scrolls beneath it, so this is read from the layout
     * every time rather than counted from rows travelled.
     */
    private fun slotUnderRow(): Int {
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isEmpty()) {
            // Nothing laid out yet (unit tests, the first frame): rows travelled is all there is.
            return origin + ((finger - startFinger) / pitchPx).roundToInt()
        }
        val centre = desiredTop() + pitchPx / 2f
        val hit = visible.lastOrNull { it.offset <= centre } ?: visible.first()
        return hit.index - firstItem
    }

    /**
     * The lifted row's top as it is drawn: carried by the finger, kept inside the visible content
     * area so the title bar and the action bar never hide it.
     */
    private fun drawnTop(info: LazyListLayoutInfo): Float {
        val start = visibleStart(info)
        val end = visibleEnd(info)
        if (end <= start) return desiredTop()
        val height = info.visibleItemsInfo.firstOrNull { it.key == liftKey }?.size?.toFloat()
            ?: pitchPx
        return clampToViewport(desiredTop(), start.toFloat(), end.toFloat(), height)
    }

    /** Whatever puts the row back under the finger: its carried top less its current slot's top. */
    private fun updateTranslation() {
        if (from == null) {
            translation = 0f
            return
        }
        val info = listState.layoutInfo
        if (info.visibleItemsInfo.isEmpty()) {
            // Nothing laid out yet: the travel math is the only thing there is.
            translation = desiredTop() - startOffset
            return
        }
        val itemTop = info.visibleItemsInfo.firstOrNull { it.key == liftKey }?.offset ?: return
        translation = drawnTop(info) - itemTop
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
        return if (itemTop != null) drawnTop(info) - itemTop else fallback
    }

    /** Whether [token] is the gesture that opened the current lift. */
    internal fun owns(token: Any?): Boolean = token != null && owner === token

    /**
     * Ends the drag. Every move was committed as it happened, so there is nothing left to drop.
     * Passing a [token] makes the call a no-op unless that gesture owns the lift.
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
        finger = 0f
        grab = 0f
        startFinger = 0f
        keypad = false
        hasMoved = false
        liftKey = null
        owner = null
    }

    /**
     * Keeps a long drag alive past the viewport edges: while the finger is near the top or the
     * bottom of the visible content, scroll a fraction of the way to the edge per frame. The
     * scroll moves the slots under the row, so the slot it covers is read again every frame and
     * the row keeps advancing through the queue while the finger is held still.
     */
    internal suspend fun autoScroll(onMove: (Int, Int) -> Unit = { _, _ -> }) {
        var lastFrame = 0L
        while (active && !keypad) {
            val frame = withFrameNanos { it }
            val elapsed = if (lastFrame == 0L) 0f else (frame - lastFrame) / NANOS_PER_MILLI
            lastFrame = frame
            val info = listState.layoutInfo
            if (info.visibleItemsInfo.isEmpty()) continue
            val step = autoScrollStep(info, elapsed)
            if (step != 0f) listState.scrollBy(step)
            follow()?.let { (from, to) -> onMove(from, to) }
        }
    }

    /**
     * How far to scroll this frame, in pixels: nothing in the middle of the list, rising to a
     * capped speed as the finger goes past an edge. A finger held beyond the edge keeps scrolling
     * at full speed, so the row can be carried the length of the queue without lifting.
     */
    private fun autoScrollStep(info: LazyListLayoutInfo, elapsedMillis: Float): Float {
        if (elapsedMillis <= 0f) return 0f
        val start = visibleStart(info).toFloat()
        val end = visibleEnd(info).toFloat()
        val span = end - start
        if (span < 1f) return 0f
        val edge = (pitchPx * AUTO_SCROLL_EDGE).coerceAtMost(span / 2f)
        if (edge < 1f) return 0f
        val depth = when {
            finger < start + edge -> ((finger - start - edge) / edge).coerceIn(-1f, 0f)
            finger > end - edge -> ((finger - end + edge) / edge).coerceIn(0f, 1f)
            else -> return 0f
        }
        val distance = depth * AUTO_SCROLL_SPEED * (elapsedMillis / MILLIS_PER_SECOND)
        return distance.coerceIn(-AUTO_SCROLL_MAX_PX, AUTO_SCROLL_MAX_PX)
    }
}

private const val NANOS_PER_MILLI = 1_000_000f

private const val MILLIS_PER_SECOND = 1_000f

/** How much of a row at the edge is the auto-scroll zone. */
private const val AUTO_SCROLL_EDGE = 0.4f

/** Auto-scroll speed at the very edge, in pixels per second. */
private const val AUTO_SCROLL_SPEED = 900f

/** The most the auto-scroll may travel in one frame, so a long frame cannot fling the list. */
private const val AUTO_SCROLL_MAX_PX = 28f

/** The top of the visible content area, in item-offset space (below the title bar). */
private fun visibleStart(info: LazyListLayoutInfo) = info.viewportStartOffset + info.beforeContentPadding

/** The bottom of the visible content area, in item-offset space (above the action bar). */
private fun visibleEnd(info: LazyListLayoutInfo) = info.viewportEndOffset - info.afterContentPadding

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
    // The effect outlives the composition that started it; read the current callback.
    val latestOnMove by rememberUpdatedState(onMove)
    LaunchedEffect(state.active) {
        if (state.active) state.autoScroll { from, to -> latestOnMove(from, to) }
    }
}

/**
 * Long press anywhere in the list to pick up the upcoming row under the finger, then drag. The
 * gesture is declared once on the list container rather than on each row: a row leaves
 * composition as soon as the list scrolls it out, and a gesture that lives on the row dies with
 * it, which ended long drags the moment they reached an edge. Reading the finger in the
 * [PointerEventPass.Initial] pass lets the lift claim the gesture before the list scrolls or the
 * row's swipe reveals an action.
 *
 * @param firstItem the list index of upcoming slot 0.
 * @param count the number of upcoming slots that can be lifted.
 */
@Composable
fun Modifier.queueReorderGestures(
    state: QueueReorderState,
    firstItem: Int,
    count: Int,
    onPickUp: () -> Unit = {},
    onDrop: (Int, Int) -> Unit,
    onDropEnd: () -> Unit = {}
): Modifier {
    val latestFirst by rememberUpdatedState(firstItem)
    val latestCount by rememberUpdatedState(count)
    val latestPickUp by rememberUpdatedState(onPickUp)
    val latestDrop by rememberUpdatedState(onDrop)
    val latestDropEnd by rememberUpdatedState(onDropEnd)
    return pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // Another lift (a keypad reorder) or a queue too short to reorder owns the screen.
            if (state.active || latestCount <= 1) return@awaitEachGesture
            // A finger that travels before the press is complete is scrolling, not picking a row
            // up: leave the gesture to the list.
            if (!awaitLongPress(down)) return@awaitEachGesture
            val info = state.listState.layoutInfo
            val pointer = info.toItemOffset(down.position.y)
            val hit = info.visibleItemsInfo.lastOrNull { it.offset <= pointer }
                ?: info.visibleItemsInfo.firstOrNull()
                ?: return@awaitEachGesture
            val upcoming = hit.index - latestFirst
            if (upcoming !in 0 until latestCount) return@awaitEachGesture
            val gesture = Any()
            state.begin(
                index = upcoming,
                count = latestCount,
                listOffset = hit.offset.toFloat(),
                owner = gesture,
                key = hit.key,
                touch = pointer - hit.offset,
                firstItem = latestFirst
            )
            latestPickUp()
            try {
                state.dragTo(pointer)?.let { (from, to) -> latestDrop(from, to) }
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    // Consumed on the Initial pass, so neither the list's scroll nor the row's
                    // swipe can act on a gesture the lift owns.
                    change.consume()
                    if (!change.pressed) break
                    val y = state.listState.layoutInfo.toItemOffset(change.position.y)
                    state.dragTo(y)?.let { (from, to) -> latestDrop(from, to) }
                }
            } finally {
                val moved = state.hasMoved
                state.end(gesture)
                if (moved) latestDropEnd()
            }
        }
    }
}

/**
 * Waits out the long-press timeout while the finger stays put. Returns false when the finger is
 * lifted or wanders past the touch slop first, leaving the gesture to whatever it was doing.
 */
private suspend fun AwaitPointerEventScope.awaitLongPress(down: PointerInputChange): Boolean {
    var held = false
    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        held = true
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id }
            val moved = change != null && change.pressed &&
                (change.position - down.position).getDistance() > viewConfiguration.touchSlop
            if (moved || change == null || !change.pressed) {
                held = false
                break
            }
        }
    }
    return held
}

/**
 * A pointer's y in item-offset space: the list's item offsets start below the content padding,
 * while pointer positions start at the container's top edge.
 */
private fun LazyListLayoutInfo.toItemOffset(pointerY: Float): Float = pointerY - beforeContentPadding
