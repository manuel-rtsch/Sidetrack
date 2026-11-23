package io.github.manuelrtsch.sidetrack

import android.content.Context
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import hrln.interactive.sidekit.design.input.Keypad
import hrln.interactive.sidekit.design.input.KeypadModule
import hrln.interactive.sidekit.design.input.T9Words
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SidetrackOnboardingTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * The selector prints only the module's own name, so it is found by its title and the name is
     * checked on the page - which also catches the "· Sidekit" and "· tap to switch" suffixes
     * that used to explain the row.
     */
    private fun assertSelectedKeypad(label: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Keypad"))
        compose.onNodeWithText("Keypad").assertExists()
        compose.onNodeWithText(label).assertExists()
    }

    @Before fun clearLocalSettings() {
        context.getSharedPreferences(SidetrackLocalSettings.FILE, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(SidetrackLocalSettings.KEYPAD_FILE, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun finishingTheSetupRemembersIt() {
        var done = false
        compose.setContent {
            SidekitPreviewFrame {
                SidetrackOnboardingScreen(
                    hostBacked = false,
                    onAbout = {},
                    onKeyboard = {},
                    onDone = { done = true }
                )
            }
        }

        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Keypad guide").assertExists()
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Make it yours").assertExists()
        compose.onNodeWithText("Get started").performClick()

        compose.runOnIdle {
            assertTrue(done)
            assertTrue(SidetrackLocalSettings(context).hasSeenOnboarding())
        }
    }

    @Test
    fun theWelcomeTeachesTheThreeEssentials() {
        compose.setContent {
            SidekitPreviewFrame {
                SidetrackOnboardingScreen(
                    hostBacked = false,
                    onAbout = {},
                    onKeyboard = {},
                    onDone = {}
                )
            }
        }

        compose.onNodeWithText("Step 1 of 3").assertExists()
        compose.onNodeWithText("Go back a screen.").assertExists()
        compose.onNodeWithText("Tap for this page’s actions.").assertExists()
        compose.onNodeWithText("Press ••• for item actions.").assertExists()
        // The full diagram is its own page now, so the first page still points instead of teaching it.
        compose.onNodeWithText("Click the keys below to find out what they do").assertDoesNotExist()
        assertFalse(SidetrackLocalSettings(context).hasSeenOnboarding())
    }

    @Test
    fun theWelcomeStartsOnItsFirstRowAndMovesWithTheDpad() {
        compose.setContent {
            SidekitPreviewFrame {
                SidetrackOnboardingScreen(
                    hostBacked = false,
                    onAbout = {},
                    onKeyboard = {},
                    onDone = {}
                )
            }
        }

        compose.onNodeWithText("Keypad").assertIsFocused()
        compose.onNodeWithText("Keypad").performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        compose.onNodeWithText("Back key").assertIsFocused()

        // Down steps onto the essentials card, which is its own selectable stop.
        compose.onNodeWithText("Back key").performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        compose.onNodeWithText("Go back a screen.").assertIsFocused()
    }

    @Test
    fun theEssentialsCardOpensTheKeyboardScreen() {
        var opened = false
        compose.setContent {
            SidekitPreviewFrame {
                SidetrackOnboardingScreen(
                    hostBacked = false,
                    onAbout = {},
                    onKeyboard = { opened = true },
                    onDone = {}
                )
            }
        }

        compose.onNode(hasScrollToIndexAction())
            .performScrollToNode(hasText("Go back a screen."))
        // Invoke the card's onClick instead of tapping its centre: the card's middle can be under
        // the action bar on the smaller preview panel.
        compose.onNodeWithText("Go back a screen.")
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertTrue(opened) }
    }

    @Test
    fun theKeypadRowOpensTheModulePicker() {
        compose.setContent {
            SidekitPreviewFrame {
                SidetrackOnboardingScreen(
                    hostBacked = false,
                    onAbout = {},
                    onKeyboard = {},
                    onDone = {}
                )
            }
        }

        // T9 is the standalone default; the picker lists every module and closes after a choice.
        assertSelectedKeypad("T9")
        compose.onNodeWithText("Keypad").performClick()
        compose.onNodeWithText("Mini QWERTY").performClick()
        assertSelectedKeypad("Mini QWERTY")

        // The Sundial's rows are the ones a new listener has to read.
        compose.onNodeWithText("Keypad").performClick()
        compose.onNodeWithText("Sundial").performClick()
        assertSelectedKeypad("Sundial")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Centre"))
        compose.onNodeWithText("Tap to play or pause. Hold to confirm.").assertExists()
    }

    @Test
    fun aHostBackedWelcomeCanStillSwitchTheModule() {
        compose.setContent {
            SidekitPreviewFrame {
                SidetrackOnboardingScreen(
                    hostBacked = true,
                    onAbout = {},
                    onKeyboard = {},
                    onDone = {}
                )
            }
        }

        compose.onNodeWithText("Keypad").performClick()
        compose.onNodeWithText("Mini QWERTY").performClick()

        // A host owns the choice, but the page it owns the instructions on can still correct it.
        assertSelectedKeypad("Mini QWERTY")
        // There is no accessibility row to open on a host-backed install.
        compose.onNodeWithText("Back key").assertDoesNotExist()
    }

    @Test
    fun thePageFollowsAKeypadSidekitDetectsWhileItIsOpen() {
        val keypad = Keypad.forTesting(
            CoroutineScope(Dispatchers.Unconfined),
            words = T9Words.parse(""),
            module = KeypadModule.T9
        )
        compose.setContent {
            SidekitPreviewFrame(keypad = keypad) {
                SidetrackOnboardingScreen(
                    hostBacked = true,
                    onAbout = {},
                    onKeyboard = {},
                    onDone = {}
                )
            }
        }

        compose.onNodeWithText("Tap for this page’s actions.").assertExists()

        // What Sidekit's detection does when the game controller's buttons arrive.
        compose.runOnIdle { keypad.useModule(KeypadModule.GamePad) }

        assertSelectedKeypad("Gamepad")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("X"))
        compose.onNodeWithText("Go back a screen.").assertExists()

        // A manual pick is a preview; the attached module wins as soon as it changes.
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Keypad"))
        compose.onNodeWithText("Keypad").performClick()
        compose.onNodeWithText("T9").performClick()
        assertSelectedKeypad("T9")
        compose.runOnIdle { keypad.useModule(KeypadModule.Sundial) }
        assertSelectedKeypad("Sundial")
    }

    @Test
    fun theKeyboardGuideStaysClosedUntilAsked() {
        compose.setContent {
            SidekitPreviewFrame {
                SidetrackKeyboardScreen(onBack = {})
            }
        }

        compose.onNodeWithText("Keypad guide").assertExists()
        compose.onNodeWithText("Hide").assertDoesNotExist()

        compose.onNode(hasScrollToIndexAction())
            .performScrollToNode(hasText("Keypad guide"))
        compose.onNodeWithText("Keypad guide").performSemanticsAction(SemanticsActions.OnClick)

        compose.onNodeWithText("Hide").assertExists()
    }

    @Test
    fun theMakeItYoursStepLinksToTheSettingsScreens() {
        var appearance = false
        compose.setContent {
            SidekitPreviewFrame {
                SidetrackOnboardingScreen(
                    hostBacked = false,
                    onAbout = {},
                    onKeyboard = {},
                    onAppearance = { appearance = true },
                    canSetQuality = true,
                    onDone = {}
                )
            }
        }

        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Step 3 of 3").assertExists()
        compose.onNodeWithText("Appearance").performClick()
        compose.runOnIdle { assertTrue(appearance) }

        // The keypad belongs to the first page; this page is only the other settings.
        compose.onNodeWithText("Keypad").assertDoesNotExist()
        compose.onNodeWithText("Audio quality").assertExists()
    }

    @Test
    fun theAudioQualityRowFollowsTheServiceCapabilities() {
        compose.setContent {
            SidekitPreviewFrame {
                SidetrackOnboardingScreen(
                    hostBacked = false,
                    onAbout = {},
                    onKeyboard = {},
                    canSetQuality = false,
                    onDone = {}
                )
            }
        }

        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Audio quality").assertDoesNotExist()
    }

    @Test
    fun backFromMakeItYoursReturnsThroughTheGuideToTheKeypad() {
        compose.setContent {
            SidekitPreviewFrame {
                SidetrackOnboardingScreen(
                    hostBacked = false,
                    onAbout = {},
                    onKeyboard = {},
                    onDone = {}
                )
            }
        }

        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Continue").performClick()
        // The guide prints a Back key too, so pick the action bar's button, which comes last.
        compose.onAllNodesWithText("Back").onLast().performClick()

        compose.onNodeWithText("Keypad guide").assertExists()
        compose.onNodeWithText("Step 2 of 3").assertExists()

        compose.onAllNodesWithText("Back").onLast().performClick()
        compose.onNodeWithText("Keypad").assertExists()
        compose.onNodeWithText("Step 1 of 3").assertExists()
    }

    @Test
    fun theGuidePageAnswersTappingAKey() {
        compose.setContent {
            SidekitPreviewFrame {
                SidetrackOnboardingScreen(
                    hostBacked = false,
                    onAbout = {},
                    onKeyboard = {},
                    onDone = {}
                )
            }
        }

        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Keypad guide").assertExists()
        compose.onNodeWithText("Step 2 of 3").assertExists()
        compose.onNodeWithText("Tap a key to see what it does on a tap and a hold.").assertExists()

        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Up"))
        compose.onNodeWithText("Up").performClick()
        compose.onNodeWithText("Move focus up.").assertExists()
    }

    @Test
    fun theFirstRunResumesAtTheStoredStep() {
        SidetrackLocalSettings(context).onboardingStep = 2
        compose.setContent {
            SidekitPreviewFrame {
                SidetrackOnboardingScreen(
                    hostBacked = false,
                    onAbout = {},
                    onKeyboard = {},
                    onDone = {}
                )
            }
        }

        compose.onNodeWithText("Make it yours").assertExists()
        compose.onNodeWithText("Step 3 of 3").assertExists()
    }

    @Test
    fun theFirstRunCanResumeOnTheGuide() {
        SidetrackLocalSettings(context).onboardingStep = 1
        compose.setContent {
            SidekitPreviewFrame {
                SidetrackOnboardingScreen(
                    hostBacked = false,
                    onAbout = {},
                    onKeyboard = {},
                    onDone = {}
                )
            }
        }

        compose.onNodeWithText("Keypad guide").assertExists()
        compose.onNodeWithText("Step 2 of 3").assertExists()
    }

    @Test
    fun helpStartsAtTheKeypadAndDoesNotWriteSetupProgress() {
        SidetrackLocalSettings(context).onboardingStep = 1
        var back = false
        compose.setContent {
            SidekitPreviewFrame {
                SidetrackOnboardingScreen(
                    hostBacked = false,
                    onAbout = {},
                    onKeyboard = {},
                    onDone = {},
                    onBack = { back = true }
                )
            }
        }

        compose.onNodeWithText("Keypad").assertExists()
        compose.onNodeWithText("Step 1 of 3").assertExists()
        // Help starts fresh even when the first run had saved a later step.
        assertFalse(back)
        assertFalse(SidetrackLocalSettings(context).hasSeenOnboarding())
    }

    @Test
    fun aHostBackedInstallHidesTheBackKeyRow() {
        compose.setContent {
            SidekitPreviewFrame {
                SidetrackOnboardingScreen(
                    hostBacked = true,
                    onAbout = {},
                    onKeyboard = {},
                    onDone = {}
                )
            }
        }

        assertSelectedKeypad("T9")
        compose.onNodeWithText("Back key").assertDoesNotExist()
        assertFalse(SidetrackLocalSettings(context).hasSeenOnboarding())
    }
}
