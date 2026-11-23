package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The compact-rows setting has to change the measured row, not just the artwork or the type: the
 * point is five rows where four fit. This reads the composed row's bounds so a Sidekit tile that
 * keeps its own fixed height cannot pass unnoticed.
 *
 * The row heights are solved for a fixed fraction of the *next* row showing: four and a quarter in
 * the default layout, five and a quarter compact. The fractions are asserted here rather than left
 * in the comments, so a chrome change (the header lost 10 dp when it was fitted to one title line)
 * cannot quietly turn the affordance into a fifth of a row or a whole one.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompactRowsTest {
    @get:Rule val compose = createComposeRule()

    private val track = MockSpotifyDataSource.albums.first().tracks.first()

    /** The predicate for `rememberMediaRowMetrics`, driven from the real row so it cannot drift. */
    private val rowGap = 3f

    private fun rowHeight(compact: Boolean): Float {
        compose.setContent {
            SidekitPreviewFrame {
                CompositionLocalProvider(LocalCompactRows provides compact) {
                    val rows = rememberTrackRows(
                        SavedUiState(),
                        onSave = {},
                        onEnsureSaved = {},
                        onAddToQueue = {}
                    )
                    LazyColumn {
                        item(key = track.id) {
                            TrackRow(
                                track.id,
                                track,
                                rows,
                                Modifier.testTag("row"),
                                onClick = {}
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag("row").getBoundsInRoot()
        return (bounds.bottom - bounds.top).value
    }

    @Test
    fun `the default row shows four and a quarter`() {
        // 435 / 103 pitch = 4 rows plus 23 dp of the fifth, so the list no longer ends at the fold.
        // No tolerance: 1 dp is 1 px on the canvas, so the height is a whole number by construction
        // and a fractional constant (99.5) would round here instead of being caught.
        assertEquals(100f, rowHeight(compact = false), 0.001f)
    }

    @Test
    fun `a compact row leaves room for five and a quarter`() {
        // 435 / 83 pitch = 5 rows plus 20 dp of the sixth.
        assertEquals(80f, rowHeight(compact = true), 0.001f)
    }

    @Test
    fun `four whole rows and a quarter fit the body`() {
        // 4.23 rather than 4.25: 435 - 12 does not divide by 4.25 into whole pixels.
        assertEquals(4.23f, visibleRows(body = 435f, height = rowHeight(compact = false), whole = 4), 0.01f)
    }

    @Test
    fun `five whole rows and a quarter fit the body`() {
        assertEquals(5.25f, visibleRows(body = 435f, height = rowHeight(compact = true), whole = 5), 0.01f)
    }

    /** Whole rows plus the fraction of the next one that the body still shows. */
    private fun visibleRows(body: Float, height: Float, whole: Int): Float {
        val peek = body - whole * (height + rowGap)
        return whole + peek / height
    }
}
