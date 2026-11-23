package io.github.manuelrtsch.sidetrack

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelectable
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilitySemanticsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun toggleRowExposesSwitchState() {
        compose.setContent {
            SidekitPreviewFrame {
                SidetrackItem("Autoplay", secondary = "On", toggleable = true, onClick = {})
            }
        }

        compose.onNodeWithText("Autoplay").assertIsToggleable().assertIsOn()
    }

    @Test
    fun sectionLabelsExposeAHeading() {
        compose.setContent { SidekitPreviewFrame { SectionLabel("Recent searches") } }

        // The label is drawn uppercase; the heading flag is what the reading gesture reads.
        compose.onNodeWithText("RECENT SEARCHES")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
    }

    @Test
    fun bufferingIsPartOfTheSeekRowsState() {
        compose.setContent {
            SidekitPreviewFrame {
                Seek(
                    position = remember { mutableStateOf(30_000L) },
                    durationMs = 180_000L,
                    onSeek = {},
                    buffering = true
                )
            }
        }

        // The visible label swaps to "Buffering…"; the state carries the same fact for TalkBack.
        compose.onNodeWithContentDescription("Seek")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Buffering"))
    }

    @Test
    fun aStatusBlockIsAnnouncedOnce() {
        compose.setContent {
            SidekitPreviewFrame {
                LoadSurface(
                    loading = false,
                    loadingTitle = "Loading artist",
                    error = "No internet connection",
                    errorTitle = "Couldn't load this artist"
                )
            }
        }

        // The title is the block's description and the body is its state: the record's own
        // description and the drawn title used to make TalkBack say the same thing twice.
        compose.onAllNodesWithContentDescription("Couldn't load this artist").assertCountEquals(1)
        compose.onNodeWithContentDescription("Couldn't load this artist")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "No internet connection"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onNodeWithText("Couldn't load this artist").assertDoesNotExist()
    }

    @Test
    fun aFirstLoadIsALiveRegion() {
        compose.setContent {
            SidekitPreviewFrame { LoadSurface(loading = true, loadingTitle = "Loading artist") }
        }

        compose.onNodeWithContentDescription("Loading artist")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun aSettledEmptyBlockIsNotAnnounced() {
        compose.setContent {
            SidekitPreviewFrame {
                LoadSurface(
                    loading = false,
                    loadingTitle = "Loading artist",
                    emptyTitle = "This artist isn't available"
                )
            }
        }

        // An empty state that was already on screen is not news; only a load or a failure changes
        // after focus landed.
        compose.onNodeWithContentDescription("This artist isn't available")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
    }

    @Test
    fun selectedRowExposesRadioState() {
        compose.setContent {
            SidekitPreviewFrame {
                SidetrackItem(
                    "Very high",
                    selected = true,
                    selectionRole = Role.RadioButton,
                    onClick = {}
                )
            }
        }

        compose.onNodeWithText("Very high").assertIsSelectable().assertIsSelected()
    }
}
