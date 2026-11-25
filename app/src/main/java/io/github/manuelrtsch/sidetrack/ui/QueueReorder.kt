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
import io.github.manuelrtsch.sidetrack.SidetrackDebug
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
    private var count = 0

    val active: Boolean get() = from != null

    internal fun begin(index: Int, count: Int, listOffset: Float, keypad: Boolean = false) {
        from = index
        to = index
        origin = index
        this.count = count
        this.keypad = keypad
        startOffset = listOffset
        pointerDelta = 0f
        scrolled = 0f
        translation = 0f
        hasMoved = false
    }

    /**
     * Moves the picked-up row with the finger, handing back the move the caller commits, or null.
     * The queue reorders as the finger crosses each row so its slot travels with it — a left-behind
     * slot scrolls out and cancels the drag; translation then puts the row back under the finger.
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
        SidetrackDebug.log(
            "reorder touch: slot dy=$dy pointerDelta=$pointerDelta scrolled=$scrolled start=$startOffset " +
                "viewport=${info.viewportStartOffset}..${info.viewportEndOffset} low=$low high=$high " +
                "desired=$desired current=$current"
        )
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
     * the distance its slot has already moved with the data.
     */
    private fun updateTranslation() {
        val current = from ?: return
        translation = pointerDelta + scrolled - (current - origin) * pitchPx
    }

    /** Ends the drag. Every move was committed as it happened, so there is nothing left to drop. */
    internal fun end() = reset()

    internal fun cancel() = reset()

    private fun reset() {
        from = null
        to = -1
        origin = -1
        translation = 0f
        pointerDelta = 0f
        scrolled = 0f
        keypad = false
        hasMoved = false
    }

    /**
     * Keeps a long drag alive past the viewport edges: while the picked-up row is near the top or
     * bottom, scroll a fraction of a pitch per frame. The scrolled pixels are added to the row's
     * translation so it stays under the finger, and the drop slot advances with them.
     */
    internal suspend fun autoScroll() {
        while (active && !keypad) {
            withFrameNanos { }
            val info = listState.layoutInfo
            val edge = pitchPx * 0.6f
            val visualTop = startOffset + pointerDelta
            val visualBottom = visualTop + pitchPx
            val nearTop = info.viewportStartOffset + edge
            val nearBottom = info.viewportEndOffset - edge
            val delta = when {
                visualTop < nearTop -> (visualTop - nearTop).coerceAtLeast(-pitchPx)
                visualBottom > nearBottom -> (visualBottom - nearBottom).coerceAtMost(pitchPx)
                else -> 0f
            }
            if (delta == 0f) continue
            // Stop at the end the row has already reached. Scrolling on would carry its slot out
            // of the viewport, and the list would dispose the row mid-drag.
            val current = from ?: continue
            if (delta < 0 && current <= 0) continue
            if (delta > 0 && current >= count - 1) continue
            val step = (delta * 0.35f).coerceIn(-MAX_AUTO_SCROLL_PX, MAX_AUTO_SCROLL_PX)
            val consumed = listState.scrollBy(step)
            if (consumed != 0f) {
                scrolled += consumed
                updateTranslation()
                SidetrackDebug.log(
                    "reorder auto: visualTop=$visualTop edge=$nearTop..$nearBottom delta=$delta " +
                        "current=$current step=$step consumed=$consumed scrolled=$scrolled"
                )
            }
        }
    }
}

/** How far the auto-scroll may travel in one frame, so a held finger cannot fling the list. */
private const val MAX_AUTO_SCROLL_PX = 16f

/** One long-press drag at a time, measured against the SideKit row pitch. */
@Composable
fun rememberQueueReorderState(listState: LazyListState): QueueReorderState {
    val pitch = with(LocalDensity.current) { rememberMediaRowPitch().toPx() }
    return remember(listState, pitch) { QueueReorderState(listState, pitch) }
}

/** One id per pointer-input coroutine, so a restart mid-drag is visible in the log. */
private val gestureIds = java.util.concurrent.atomic.AtomicInteger()

/** Runs the auto-scroll loop for as long as a row is picked up. */
@Composable
fun QueueReorderAutoScroll(state: QueueReorderState) {
    LaunchedEffect(state.active) {
        if (state.active) state.autoScroll()
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
        val gid = gestureIds.incrementAndGet()
        SidetrackDebug.log("reorder touch: COROUTINE START gid=$gid key=$key")
        fun finishDrag() {
            val moved = state.hasMoved
            SidetrackDebug.log("reorder touch: finish gid=$gid key=$key moved=$moved from=${state.from} to=${state.to}")
            state.end()
            if (moved) latestDropEnd()
        }
        try {
            detectDragGesturesAfterLongPress(
                onDragStart = {
                    val info = state.listState.layoutInfo
                    val offset = info.visibleItemsInfo.firstOrNull { it.key == key }?.offset ?: 0
                    SidetrackDebug.log(
                        "reorder touch: START gid=$gid key=$key index=$latestIndex offset=$offset " +
                            "viewport=${info.viewportStartOffset}..${info.viewportEndOffset} visible=${info.visibleItemsInfo.size}"
                    )
                    state.begin(latestIndex, latestCount, offset.toFloat())
                    latestPickUp()
                },
                onDrag = { change, delta ->
                    change.consume()
                    val move = state.drag(delta.y)
                    SidetrackDebug.log(
                        "reorder touch: drag gid=$gid key=$key dy=${delta.y} from=${state.from} to=${state.to} " +
                            "translation=${state.translation} move=$move consumed=${change.isConsumed}"
                    )
                    move?.let { (from, to) -> latestDrop(from, to) }
                },
                onDragEnd = {
                    SidetrackDebug.log("reorder touch: END gid=$gid key=$key")
                    finishDrag()
                },
                onDragCancel = {
                    val info = state.listState.layoutInfo
                    SidetrackDebug.log(
                        "reorder touch: CANCEL gid=$gid key=$key visible=" +
                            info.visibleItemsInfo.joinToString { "${it.key}@${it.offset}" }
                    )
                    finishDrag()
                }
            )
        } finally {
            // A row that scrolls out of the list is disposed mid-drag, which cancels this coroutine
            // without a callback: never leave a lift behind. Moves already committed during the
            // drag still count, so commit the hand-picked order once.
            if (state.active) {
                SidetrackDebug.log("reorder touch: FINALLY active gid=$gid key=$key")
                finishDrag()
            } else {
                SidetrackDebug.log("reorder touch: FINALLY idle gid=$gid key=$key")
            }
        }
    }
}
