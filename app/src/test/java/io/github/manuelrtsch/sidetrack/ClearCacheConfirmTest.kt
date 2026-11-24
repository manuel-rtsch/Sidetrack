package io.github.manuelrtsch.sidetrack

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
 * Clearing the cache cannot be undone by anyone, so the row asks before it writes: the question is
 * the bar, and the close square is the "no".
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClearCacheConfirmTest {
    @get:Rule val compose = createComposeRule()

    private var cleared = 0

    private fun show() {
        compose.setContent {
            SidekitTheme(spacing = SidekitSpacing.Compact) {
                SidekitCanvas {
                    SettingsScreen(
                        AccountUiState(),
                        ChromeUiState(),
                        recentCount = 3,
                        serviceName = "Spotify",
                        onBack = {},
                        onAudioQuality = {},
                        onNormalization = {},
                        onGapless = {},
                        onAutoplay = {},
                        onClearCache = { cleared += 1 },
                        onClearHistory = { true },
                        onUndoClearHistory = {},
                        onSignOut = {},
                        onDismissNotice = {},
                        onAppearance = {}
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun theRowAsksBeforeItClears() {
        show()
        compose.onNodeWithText("Clear cache").performClick()
        compose.waitForIdle()

        assertEquals("The row only asks", 0, cleared)
        compose.onNodeWithContentDescription("Clear the cache? Nothing can be brought back").assertExists()

        compose.onNodeWithContentDescription("Clear the cache? Nothing can be brought back").performClick()
        compose.waitForIdle()
        assertEquals(1, cleared)
    }

    @Test
    fun theCloseSquareCancelsAndTouchesNothing() {
        show()
        compose.onNodeWithText("Clear cache").performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Cancel").performClick()
        compose.waitForIdle()

        assertEquals(0, cleared)
        compose.onNodeWithContentDescription("Clear the cache? Nothing can be brought back").assertDoesNotExist()
    }
}
