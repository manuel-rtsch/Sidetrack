package io.github.manuelrtsch.sidetrack

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import hrln.interactive.sidekit.design.canvas.SidekitCanvas
import hrln.interactive.sidekit.design.theme.SidekitSpacing
import hrln.interactive.sidekit.design.theme.SidekitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The field is announced once, whether or not it has text.
 *
 * The placeholder is merged into BasicTextField's own node, so an empty field already carries the
 * word "Search" as its text. Describing it as "Search" too produced "Search, Search, edit box".
 * The description is therefore only set while the field has text, when the placeholder is gone.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchFieldLabelTest {
    @get:Rule val compose = createComposeRule()

    private fun show(query: String) {
        compose.setContent {
            SidekitTheme(spacing = SidekitSpacing.Compact) {
                SidekitCanvas {
                    SearchScreen(
                        SearchUiState(query = query),
                        ChromeUiState(),
                        onBack = {},
                        onSearch = {},
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
    fun anEmptyFieldCarriesNoDuplicateDescription() {
        show("")
        compose.onNode(hasSetTextAction())
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
            .assert(hasText("Search"))
    }

    @Test
    fun aFilledFieldKeepsTheLabel() {
        show("night")
        compose.onNode(hasSetTextAction())
            .assert(hasContentDescription("Search"))
            .assert(hasText("night"))
    }
}
