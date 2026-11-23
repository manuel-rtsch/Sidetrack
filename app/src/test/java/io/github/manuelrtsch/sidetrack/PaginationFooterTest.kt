package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The pagination footer reports its work with the record spinner, not with a word beside the label.
 * A footer label is a list's own title and can be long, so a trailing "Loading…" was the first thing
 * truncated at exactly the moment it had something to say; the spinner is a fixed square.
 *
 * A page that did not arrive keeps the row pressable and swaps the label for the retry, so the list
 * behind it stays reachable.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaginationFooterTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun loadingTurnsTheStatusTextIntoASpinner() {
        compose.setContent {
            SidekitPreviewFrame {
                LoadMoreRow("Load more tracks", loading = true, onLoadMore = {})
            }
        }

        // Read the unmerged tree: the tile merges its children, so only there does the spinner
        // have bounds of its own and a stray "Loading…" still show up as a text node.
        compose.onNodeWithText("Load more tracks", useUnmergedTree = true).assertExists()
        // The row still says what it is doing; a screen reader gets it from the spinner.
        compose.onNodeWithContentDescription("Loading…", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Loading…", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun theSpinnerKeepsItsWidthWhateverTheLabelIs() {
        compose.setContent {
            SidekitPreviewFrame {
                Column {
                    LoadMoreRow("More", loading = true, onLoadMore = {})
                    LoadMoreRow("Load more episodes from this show", loading = true, onLoadMore = {})
                }
            }
        }

        val spinners = compose.onAllNodesWithContentDescription("Loading…", useUnmergedTree = true)
        val short = spinners[0].getBoundsInRoot()
        val long = spinners[1].getBoundsInRoot()
        // The row arrow's 30 dp, on both rows: the label's length cannot shrink it the way it used
        // to shrink the word it replaced.
        assertEquals(30f, (short.right - short.left).value, 0.5f)
        assertEquals(30f, (short.bottom - short.top).value, 0.5f)
        assertEquals(30f, (long.right - long.left).value, 0.5f)
        assertEquals(30f, (long.bottom - long.top).value, 0.5f)
    }

    @Test
    fun aFailedPageOffersTheRetryWhereTheLabelWas() {
        compose.setContent {
            SidekitPreviewFrame {
                LoadMoreRow("Load more tracks", loading = false, onLoadMore = {}, failed = true)
            }
        }

        // The footer keeps the list's shape: one line, one press. The rows above it are still
        // there, so the failure is a retry rather than an ending.
        compose.onNodeWithText("Couldn't load more · Try again").assertExists().assertHasClickAction()
        compose.onNodeWithText("Load more tracks").assertDoesNotExist()
        compose.onNodeWithContentDescription("Loading…", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun aRetryingFooterShowsTheSpinnerAgain() {
        compose.setContent {
            SidekitPreviewFrame {
                LoadMoreRow("Load more tracks", loading = true, onLoadMore = {}, failed = true)
            }
        }

        // A press clears the failure before the request starts, so the two states never overlap;
        // if they did, the spinner is the one that wins.
        compose.onNodeWithContentDescription("Loading…", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Couldn't load more · Try again").assertDoesNotExist()
    }

    @Test
    fun anIdleFooterShowsNoSpinner() {
        compose.setContent {
            SidekitPreviewFrame {
                LoadMoreRow("Load more tracks", loading = false, onLoadMore = {})
            }
        }

        compose.onNodeWithContentDescription("Loading…", useUnmergedTree = true).assertDoesNotExist()
    }
}
