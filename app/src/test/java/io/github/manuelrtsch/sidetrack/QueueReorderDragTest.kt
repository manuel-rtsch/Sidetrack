package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.manuelrtsch.sidetrack.ui.QueueReorderAutoScroll
import io.github.manuelrtsch.sidetrack.ui.QueueReorderState
import io.github.manuelrtsch.sidetrack.ui.queueReorderGestures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The drag math needs a laid-out list: the slot under the row and the row's draw offset are read
 * from the layout, and the content padding of Sidekit's scaffold is what the visible edges are
 * measured against. These tests lay out a padded list and drive the state the gesture drives.
 */
@RunWith(AndroidJUnit4::class)
class QueueReorderDragTest {
    @get:Rule val compose = createComposeRule()

    private val topPadding = 120.dp
    private val bottomPadding = 80.dp
    private val rowHeight = 64.dp
    private val headerHeight = 24.dp
    private val gap = 8.dp

    /** The rows the list renders, in order: the drag commits a move here exactly as the screen does. */
    private val rows = mutableStateListOf<String>()
    private val listState = LazyListState()

    @Test
    fun `the lifted row keeps the screen position the finger gave it through a committed move`() {
        val state = layOut(count = 20)
        val info = listState.layoutInfo
        val pitch = pitch(info)
        val grab = pitch * 0.15f
        val start = info.visibleItemsInfo.first { it.key == "row3" }.offset.toFloat()

        state.begin(
            index = 3,
            count = rows.size,
            listOffset = start,
            key = "row3",
            touch = grab,
            firstItem = 0
        )
        val finger = start + grab + pitch * 0.6f
        val move = state.dragTo(finger)

        assertEquals("the row centre crossed into the next slot", 3 to 4, move)
        commit(move!!)
        assertEquals(4, state.from)

        val drawn = drawnTop(state, "row3")
        assertEquals(
            "the row rides the finger: its drawn top is the finger less where it was grabbed",
            finger - grab,
            drawn,
            0.5f
        )
        assertTrue(state.hasMoved)
    }

    @Test
    fun `a row dragged past the bottom edge is parked inside the visible content`() {
        val state = layOut(count = 20)
        val info = listState.layoutInfo
        val pitch = pitch(info)
        val start = info.visibleItemsInfo.first { it.key == "row1" }.offset.toFloat()
        state.begin(index = 1, count = rows.size, listOffset = start, key = "row1", touch = 10f, firstItem = 0)

        state.dragTo(info.viewportEndOffset + 400f)

        val visibleEnd = (info.viewportEndOffset - info.afterContentPadding).toFloat()
        val height = info.visibleItemsInfo.first { it.key == "row1" }.size.toFloat()
        val drawn = drawnTop(state, "row1")
        assertEquals(
            "the action bar's padding is not a place a row may hide",
            visibleEnd - height,
            drawn,
            0.5f
        )
        assertEquals(
            "the row keeps advancing to the last slot on screen",
            info.visibleItemsInfo.last().index,
            state.from
        )
        // The drawn row is a whole pitch below where it started, not a number of rows travelled.
        assertTrue(drawn > start)
        assertTrue(pitch > 0f)
    }

    @Test
    fun `a row dragged past the title bar is parked below it`() {
        val state = layOut(count = 20)
        val info = listState.layoutInfo
        val start = info.visibleItemsInfo.first { it.key == "row3" }.offset.toFloat()
        state.begin(index = 3, count = rows.size, listOffset = start, key = "row3", touch = 10f, firstItem = 0)

        state.dragTo(-500f)

        val visibleStart = (info.viewportStartOffset + info.beforeContentPadding).toFloat()
        assertEquals(
            "the title bar's padding is not a place a row may hide",
            visibleStart,
            drawnTop(state, "row3"),
            0.5f
        )
        assertEquals("the row cannot pass the head of the queue", 0, state.from)
    }

    @Test
    fun `the visible content sits inside the scaffold padding`() {
        layOut(count = 20)
        val info = listState.layoutInfo

        assertTrue(
            "the list's viewport starts above the container because of the top padding",
            info.viewportStartOffset < 0
        )
        assertEquals(
            "the item offsets start where the title bar ends",
            -info.beforeContentPadding,
            info.viewportStartOffset
        )
        assertEquals(
            "the first row's offset and the visible start are the same place",
            0,
            info.viewportStartOffset + info.beforeContentPadding
        )
        val visible = info.viewportEndOffset - info.afterContentPadding -
            (info.viewportStartOffset + info.beforeContentPadding)
        assertEquals(
            "the visible content ends above the action bar",
            info.viewportSize.height - info.beforeContentPadding - info.afterContentPadding,
            visible
        )
    }

    @Test
    fun `a long press picks a row up and the drag commits the move`() {
        val rows = mutableStateListOf(*(0 until 20).map { "row$it" }.toTypedArray())
        val listState = LazyListState()
        val moves = mutableListOf<Pair<Int, Int>>()
        compose.setContent {
            val density = LocalDensity.current
            val reorder = remember {
                QueueReorderState(listState, with(density) { (rowHeight + gap).toPx() })
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("list")
                    .queueReorderGestures(
                        state = reorder,
                        firstItem = 0,
                        count = rows.size,
                        onDrop = { from, to ->
                            moves += from to to
                            rows.add(to, rows.removeAt(from))
                        }
                    ),
                contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding),
                verticalArrangement = Arrangement.spacedBy(gap)
            ) {
                items(rows, key = { it }) {
                    Box(Modifier.fillMaxWidth().height(rowHeight).background(Color.LightGray))
                }
            }
        }
        compose.waitForIdle()
        val info = listState.layoutInfo
        val pitch = pitch(info)
        val rowY = info.beforeContentPadding + pitch * 2 + info.visibleItemsInfo[2].size / 2f

        // The finger rests on row 2 until the press completes, then travels one row down.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("list").performTouchInput { down(Offset(48f, rowY)) }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("list").performTouchInput { moveBy(Offset(0f, pitch)) }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithTag("list").performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        assertEquals("the drag commits the slot it crossed", listOf(2 to 3), moves)
        assertEquals("row2", rows[3])
    }

    @Test
    fun `holding the finger past the bottom edge scrolls the list and carries the row with it`() {
        val rows = mutableStateListOf(*(0 until 20).map { "row$it" }.toTypedArray())
        val listState = LazyListState()
        val moves = mutableListOf<Pair<Int, Int>>()
        lateinit var state: QueueReorderState
        compose.setContent {
            val density = LocalDensity.current
            val reorder = remember {
                QueueReorderState(listState, with(density) { (rowHeight + gap).toPx() })
            }
            state = reorder
            QueueReorderAutoScroll(reorder) { from, to ->
                moves += from to to
                rows.add(to, rows.removeAt(from))
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag("list").queueReorderGestures(
                    state = reorder,
                    firstItem = 0,
                    count = rows.size,
                    onDrop = { from, to ->
                        moves += from to to
                        rows.add(to, rows.removeAt(from))
                    }
                ),
                contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding),
                verticalArrangement = Arrangement.spacedBy(gap)
            ) {
                items(rows, key = { it }) {
                    Box(Modifier.fillMaxWidth().height(rowHeight).background(Color.LightGray))
                }
            }
        }
        compose.waitForIdle()
        val info = listState.layoutInfo
        val pitch = pitch(info)
        val rowY = info.beforeContentPadding + pitch + info.visibleItemsInfo[1].size / 2f

        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("list").performTouchInput { down(Offset(48f, rowY)) }
        compose.mainClock.advanceTimeBy(600)
        // The finger goes far past the action bar and stays there: the row is parked above it and
        // the list keeps scrolling, which is the only way to walk a row down a long queue.
        compose.onNodeWithTag("list").performTouchInput { moveBy(Offset(0f, 1_000f)) }
        repeat(30) { compose.mainClock.advanceTimeBy(16) }
        compose.onNodeWithTag("list").performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        assertTrue("the held finger scrolled the list", listState.firstVisibleItemIndex > 0)
        assertTrue("the row advanced slot by slot", moves.size > 1)
        assertTrue("the row kept moving while the finger was still", moves.last().second > moves.first().second)
        assertEquals("row1", rows[moves.last().second])
        assertEquals(-1, state.to)
        assertEquals(null, state.from)
    }

    @Test
    fun `a row held at the head of the queue climbs it without throwing the list a row`() {
        // The queue screen's geometry, which is where the flicker appeared: SidekitScaffold pads the
        // list at the bottom only, so the list is composed from the first visible index and the title
        // sits outside it. Index 0 is the queue header, so upcoming slot 0 is list index 1.
        val rows = mutableStateListOf(*(0 until 20).map { "row$it" }.toTypedArray())
        val listState = LazyListState()
        val moves = mutableListOf<Pair<Int, Int>>()
        val tops = mutableListOf<Int>()
        lateinit var state: QueueReorderState
        compose.setContent {
            val density = LocalDensity.current
            val reorder = remember {
                QueueReorderState(listState, with(density) { (rowHeight + gap).toPx() })
            }
            state = reorder
            QueueReorderAutoScroll(reorder) { from, to ->
                moves += from to to
                rows.add(to, rows.removeAt(from))
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag("list").queueReorderGestures(
                    state = reorder,
                    firstItem = 1,
                    count = rows.size,
                    onDrop = { from, to ->
                        moves += from to to
                        rows.add(to, rows.removeAt(from))
                    }
                ),
                contentPadding = PaddingValues(bottom = bottomPadding),
                verticalArrangement = Arrangement.spacedBy(gap)
            ) {
                item(key = "header") { Box(Modifier.fillMaxWidth().height(headerHeight)) }
                items(rows, key = { it }) {
                    Box(Modifier.fillMaxWidth().height(rowHeight).background(Color.LightGray))
                }
            }
        }
        compose.runOnIdle { listState.requestScrollToItem(5) }
        compose.waitForIdle()
        val info = listState.layoutInfo
        val second = info.visibleItemsInfo[1]
        val rowY = (second.offset + second.size / 2f).toFloat()

        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("list").performTouchInput { down(Offset(48f, rowY)) }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("list").performTouchInput { moveBy(Offset(0f, -1_000f)) }
        val held = listState.firstVisibleItemIndex
        repeat(40) {
            compose.mainClock.advanceTimeBy(16)
            tops += listState.firstVisibleItemIndex
        }
        compose.onNodeWithTag("list").performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        assertTrue("the held finger walked the row up the queue", moves.size > 2)
        moves.zipWithNext().forEach { (before, after) ->
            assertEquals("every move picks up where the last one dropped", before.second, after.first)
            assertTrue("the row only ever climbs while the finger is held: $moves", after.second < after.first)
        }
        // The flicker was the viewport being shoved one row down and back by LazyList's scroll
        // anchoring, which follows the first visible row when a reorder moves it. A held finger
        // scrolls the list backwards, never forwards.
        tops.forEach { assertTrue("the viewport only moves backwards: $tops", it <= held) }
        assertTrue("the list scrolled to the head", tops.last() < held)
    }

    /** Lays the padded list out and returns a state measured against the rows it really drew. */
    private fun layOut(count: Int): QueueReorderState {
        rows.clear()
        rows.addAll((0 until count).map { "row$it" })
        compose.setContent {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding),
                verticalArrangement = Arrangement.spacedBy(gap)
            ) {
                items(rows, key = { it }) {
                    Box(Modifier.fillMaxWidth().height(rowHeight).background(Color.LightGray))
                }
            }
        }
        compose.waitForIdle()
        return QueueReorderState(listState, pitchPx = pitch(listState.layoutInfo))
    }

    /** The row height plus the gap, read from the layout instead of assuming a density. */
    private fun pitch(info: androidx.compose.foundation.lazy.LazyListLayoutInfo): Float = (info.visibleItemsInfo[1].offset - info.visibleItemsInfo[0].offset).toFloat()

    /** Where the row is actually drawn: its slot's top plus the translation the layer applies. */
    private fun drawnTop(state: QueueReorderState, key: String): Float {
        val top = listState.layoutInfo.visibleItemsInfo.first { it.key == key }.offset.toFloat()
        return top + state.drawTranslation(key)
    }

    private fun commit(move: Pair<Int, Int>) {
        val (from, to) = move
        compose.runOnIdle { rows.add(to, rows.removeAt(from)) }
        compose.waitForIdle()
    }
}
