package io.github.manuelrtsch.sidetrack

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import hrln.interactive.sidekit.design.canvas.SidekitCanvas
import hrln.interactive.sidekit.design.theme.SidekitSpacing
import hrln.interactive.sidekit.design.theme.SidekitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment

@RunWith(AndroidJUnit4::class)
class AboutVersionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `the git describe tail is not part of the plain version`() {
        assertEquals("0.3.5", plainVersionName("0.3.5-11-g55e317f"))
        assertEquals("0.3.5", plainVersionName("0.3.5-11-g55e317f-dirty"))
        assertEquals("0.3.5", plainVersionName("0.3.5-11-g55e317f-dirty-dev"))
        assertEquals("0.3.5", plainVersionName("0.3.5-dirty"))
        assertEquals("0.3.5", plainVersionName("0.3.5-dev"))
        assertEquals("0.3.5", plainVersionName("0.3.5"))
        assertEquals("0.1.0", plainVersionName("0.1.0"))
    }

    @Test
    fun buildNumberOnlyAppearsAfterTheVersionRowIsClicked() {
        val context = RuntimeEnvironment.getApplication()
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionCode = packageInfo.longVersionCode
        val versionName = plainVersionName(packageInfo.versionName.orEmpty())

        compose.setContent {
            SidekitTheme(spacing = SidekitSpacing.Compact) {
                SidekitCanvas {
                    SidetrackAboutScreen(onBack = {})
                }
            }
        }
        compose.waitForIdle()

        val buildDetails = "Build $versionCode · $spotifyEngineLabel"

        compose.onNodeWithText("Sidetrack $versionName").assertExists()
        compose.onNodeWithText(buildDetails).assertDoesNotExist()
        compose.onNodeWithText("Sidetrack $versionName").performClick()
        compose.waitForIdle()
        // The engine is part of the same reveal: a tester's screenshot then says whether the APK
        // carries the native bridge or the rate-limited Web API fallback.
        compose.onNodeWithText(buildDetails).assertExists()

        // The row toggles: a second press puts the plain version back.
        compose.onNodeWithText("Sidetrack $versionName").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(buildDetails).assertDoesNotExist()
    }

    @Test
    fun rowsAreFocusStopsInKeypadOrder() {
        compose.setContent {
            SidekitTheme(spacing = SidekitSpacing.Compact) {
                SidekitCanvas {
                    SidetrackAboutScreen(onBack = {})
                }
            }
        }
        compose.waitForIdle()

        // Entry focus lands on the first row rather than nowhere, as on every other list screen.
        compose.onNodeWithText("Version").assertIsFocused()
        compose.onNodeWithText("Version").performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        compose.onNodeWithText("Source code").assertIsFocused()
        compose.onNodeWithText("Source code").performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        compose.onNodeWithText("Author").assertIsFocused()
        compose.onNodeWithText("Author").performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        compose.onNodeWithText("Built with Sidekit").assertIsFocused()
        compose.onNodeWithText("Built with Sidekit").performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        // The licence card is a reading stop: focusable so the list can scroll it into view. Its
        // text sits under the focus target, so match the focused ancestor.
        compose.onNode(isFocused() and hasAnyDescendant(hasText("Licenses"))).assertExists()
        // No toggle: reaching the card is enough for the table to be on the page.
        compose.onNodeWithText("Jellyfin SDK for Kotlin").assertExists()
        compose.onNodeWithText("LGPL-3.0").assertExists()
    }

    @Test
    fun `the engine label names the bridge only when the build packaged it`() {
        assertEquals(
            if (BuildConfig.NATIVE_SPOTIFY) "Native Spotify engine" else "Spotify Web API, no native bridge",
            spotifyEngineLabel
        )
    }
}
