package io.github.manuelrtsch.sidetrack

import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsTutorialTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun theTutorialRowReopensTheWelcomePage() {
        var opened = false
        compose.setContent {
            SidekitPreviewFrame {
                SettingsScreen(
                    account = AccountUiState(signedIn = true),
                    chrome = ChromeUiState(),
                    recentCount = 0,
                    onBack = {},
                    onAudioQuality = {},
                    onNormalization = {},
                    onGapless = {},
                    onAutoplay = {},
                    onClearCache = {},
                    onClearHistory = { false },
                    onUndoClearHistory = {},
                    onSignOut = {},
                    onDismissNotice = {},
                    onAppearance = {},
                    onTutorial = { opened = true }
                )
            }
        }

        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Tutorial"))
        compose.onNodeWithText("Tutorial").performClick()
        compose.runOnIdle { assertTrue(opened) }
    }
}
