package io.github.manuelrtsch.sidetrack

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * Clearing the history is offered back for five seconds in the action bar, and the bar is not what
 * is drawn while the on-screen keyboard owns the screen. The clock therefore has to stop with the
 * bar: an offer the user cannot see must not expire behind it.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchUndoWindowTest {
    @get:Rule val compose = createComposeRule()

    private val history = listOf("night", "hello")
    private var restored: List<String>? = null
    private var keyboard by mutableStateOf(false)

    private fun show() {
        compose.setContent {
            SidekitTheme(spacing = SidekitSpacing.Compact) {
                SidekitCanvas {
                    SearchScreen(
                        SearchUiState(searchHistory = history),
                        ChromeUiState(),
                        onBack = {},
                        onSearch = {},
                        onClear = {},
                        onRemoveHistory = {},
                        onScopes = {},
                        onNowPlaying = {},
                        onDismissNotice = {},
                        onDismissNowPlaying = {},
                        onClearHistory = {},
                        onRestoreHistory = { restored = it },
                        keyboardVisible = keyboard
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun anUndoOfferSurvivesAKeyboardExcursion() {
        show()
        compose.onNodeWithContentDescription("Clear search history").performClick()
        compose.onNodeWithText("Undo").assertExists()

        // The keyboard takes the bar away and stays up well past the five seconds.
        keyboard = true
        compose.waitForIdle()
        compose.onNodeWithText("Undo").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(10_000)
        compose.waitForIdle()

        // Coming back finds the offer still pending, and it still restores what was cleared.
        keyboard = false
        compose.waitForIdle()
        compose.onNodeWithText("Undo").assertExists()
        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle()

        assertEquals(history, restored)
    }

    @Test
    fun anOfferTheBarShowsStillExpires() {
        show()
        compose.onNodeWithContentDescription("Clear search history").performClick()
        compose.onNodeWithText("Undo").assertExists()

        compose.mainClock.advanceTimeBy(10_000)
        compose.waitForIdle()

        compose.onNodeWithText("Undo").assertDoesNotExist()
    }
}
