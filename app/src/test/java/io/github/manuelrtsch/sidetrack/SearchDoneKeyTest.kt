package io.github.manuelrtsch.sidetrack

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performImeAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import hrln.interactive.sidekit.design.canvas.SidekitCanvas
import hrln.interactive.sidekit.design.theme.SidekitSpacing
import hrln.interactive.sidekit.design.theme.SidekitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The screen keeps no action bar while the on-screen keyboard is up, so the IME's Done is the only
 * key left. With a searchable term it searches; below two letters there is nothing to search, and
 * a Done that did nothing at all left the keyboard, and with it the empty bar, in the way.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchDoneKeyTest {
    @get:Rule val compose = createComposeRule()

    private fun show(query: String, onSearch: (String) -> Unit = {}) {
        compose.setContent {
            SidekitTheme(spacing = SidekitSpacing.Compact) {
                SidekitCanvas {
                    SearchScreen(
                        SearchUiState(query = query),
                        ChromeUiState(),
                        onBack = {},
                        onSearch = onSearch,
                        onClear = {},
                        onRemoveHistory = {},
                        onScopes = {},
                        onNowPlaying = {},
                        onDismissNotice = {},
                        onDismissNowPlaying = {}
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun aTermWorthSearchingIsSearchedByDone() {
        var submitted: String? = null
        show("night") { submitted = it }

        compose.onNode(hasSetTextAction()).performImeAction()

        assertEquals("night", submitted)
    }

    @Test
    fun doneBelowTwoLettersLeavesTheField() {
        show("b")

        // A term already in the field is worth refining, so the field opens focused.
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.onNode(hasSetTextAction()).performImeAction()

        compose.onNode(hasSetTextAction()).assertIsNotFocused()
    }
}
