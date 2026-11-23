package io.github.manuelrtsch.sidetrack

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * Done does nothing below two letters, and the hint that explains why is not drawn under the
 * keyboard. The rule reaches the screen reader through the field instead, so it lands on the same
 * node as the text it applies to rather than somewhere further down the page.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchFieldStateDescriptionTest {
    @get:Rule val compose = createComposeRule()

    private fun show(query: String) = compose.showSearchField(query)

    /** The action bar's Search button carries the same description, so the field is matched by use. */
    private fun field() = compose.onNode(hasSetTextAction() and hasContentDescription("Search"))

    @Test
    fun aOneLetterTermExplainsItselfOnTheField() {
        show("b")
        field().assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "Keep typing, at least two letters"
            )
        )
    }

    @Test
    fun aSearchableTermLeavesTheFieldUnannotated() {
        show("bowie")
        field().assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }
}
