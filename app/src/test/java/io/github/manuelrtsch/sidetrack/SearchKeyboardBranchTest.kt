package io.github.manuelrtsch.sidetrack

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import hrln.interactive.sidekit.design.canvas.SidekitCanvas
import hrln.interactive.sidekit.design.theme.SidekitSpacing
import hrln.interactive.sidekit.design.theme.SidekitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * What the landing screen gives up to the on-screen keyboard, at the layout rather than in a rule.
 *
 * The keyboard state is a parameter because `WindowInsets` cannot be faked in Robolectric and
 * Sidekit's own seam is internal, so this branch used to be reachable only by hand.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchKeyboardBranchTest {
    @get:Rule val compose = createComposeRule()

    private fun show(
        query: String = "",
        history: List<String> = emptyList(),
        keyboardVisible: Boolean
    ) {
        compose.setContent {
            SidekitTheme(spacing = SidekitSpacing.Compact) {
                SidekitCanvas {
                    SearchScreen(
                        SearchUiState(query = query, searchHistory = history),
                        ChromeUiState(),
                        onBack = {},
                        onSearch = {},
                        onClear = {},
                        onRemoveHistory = {},
                        onScopes = {},
                        onNowPlaying = {},
                        onDismissNotice = {},
                        onDismissNowPlaying = {},
                        keyboardVisible = keyboardVisible
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun theEmptyStateHintIsNotDrawnUnderTheKeyboard() {
        show(keyboardVisible = true)

        compose.onNodeWithText("Type at least two letters").assertDoesNotExist()
        compose.onNodeWithText("Keep typing").assertDoesNotExist()
    }

    @Test
    fun theHintIsDrawnWhenTheKeyboardIsDown() {
        show(keyboardVisible = false)

        compose.onNodeWithText("Type at least two letters").assertExists()
    }

    @Test
    fun theBarIsEmptyUnderTheKeyboard() {
        show(query = "night", history = listOf("night"), keyboardVisible = true)

        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
        compose.onNodeWithContentDescription("Clear search").assertDoesNotExist()
    }

    @Test
    fun theBarAndClearReturnWhenTheKeyboardIsDown() {
        show(query = "night", history = listOf("night"), keyboardVisible = false)

        compose.onNodeWithContentDescription("Back").assertExists()
        compose.onNodeWithContentDescription("Clear search").assertExists()
    }
}
