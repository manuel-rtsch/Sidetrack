package io.github.manuelrtsch.sidetrack

import android.os.Looper
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.manuelrtsch.sidetrack.ui.RowGutterState
import io.github.manuelrtsch.sidetrack.ui.rememberRowGutterState
import java.time.Duration
import org.junit.Rule
import org.robolectric.Shadows.shadowOf

/**
 * The harness every gutter row test drives: one row inside a preview frame, its gutter and action
 * bar states, and the two moves the tests make — let the row hold the focus until the gutter has
 * settled, then act on it.
 *
 * The row and what it should offer stay in the test; only the surrounding states, the frozen clock
 * and the settling are here. A test that needs a different row simply calls [showGutterRow] again
 * after changing [key], which is what the playlist and show cases do.
 */
abstract class GutterTestBase {
    @get:Rule val compose = createComposeRule()

    /** The row's key: what the gutter publishes and the action bar is addressed by. */
    protected abstract var key: String

    private var gutter: RowGutterState? = null
    private var bar: RowActionBarState? = null

    protected fun showGutterRow(row: @Composable (RowGutterState, RowActionBarState) -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            SidekitPreviewFrame {
                val gutterState = rememberRowGutterState()
                val barState = rememberRowActionBarState()
                gutter = gutterState
                bar = barState
                LazyColumn { item(key = key) { row(gutterState, barState) } }
            }
        }
        compose.mainClock.advanceTimeByFrame()
    }

    /** The gutter opens on a delay, so four frames plus an idle pass is what the row needs. */
    protected fun settle() {
        repeat(4) {
            compose.mainClock.advanceTimeBy(120)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(120))
            compose.waitForIdle()
        }
    }

    /** Focuses the row and waits for its gutter to open. */
    protected fun openGutter() {
        compose.runOnIdle { gutterState().rowFocused(key) }
        settle()
    }

    protected fun gutterState(): RowGutterState = requireNotNull(gutter)

    protected fun barState(): RowActionBarState = requireNotNull(bar)
}
