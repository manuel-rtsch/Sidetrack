package io.github.manuelrtsch.sidetrack

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * A sign-in that is still running says so, and names what it is waiting for.
 *
 * The request can sit in a browser tab or a media-permission dialog for a long time; the screen
 * used to keep showing the finished-looking form, so a second press looked like the first one
 * doing nothing. The pending state is the record, the same one a first library load uses.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SignInPendingTest {
    @get:Rule val compose = createComposeRule()

    private fun show(
        pending: Boolean,
        passwordAuth: Boolean = false,
        deviceAuth: Boolean = false
    ) {
        compose.setContent {
            SidekitPreviewFrame {
                SignInScreen(
                    ChromeUiState(),
                    onBack = {},
                    onSignIn = {},
                    onDismissNotice = {},
                    serviceName = "Spotify",
                    passwordAuth = passwordAuth,
                    deviceAuth = deviceAuth,
                    pending = pending
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun aBrowserAttemptNamesTheServiceAndKeepsItsExit() {
        show(pending = true)
        compose.onNodeWithText("Waiting for Spotify…").assertExists()
        compose.onNodeWithText("Finish in the browser").assertExists()
        // Back is the only way out of a browser round-trip; the primary action stays live because
        // a second press supersedes an attempt the browser abandoned.
        compose.onNodeWithContentDescription("Back").assertExists()
        compose.onNodeWithContentDescription("Sign in").assertExists()
        compose.onAllNodesWithText("Server").assertCountEquals(0)
    }

    @Test
    fun aDeviceAttemptNamesThePermission() {
        show(pending = true, deviceAuth = true)
        compose.onNodeWithText("Waiting for access…").assertExists()
        compose.onNodeWithContentDescription("Allow access").assertExists()
    }

    @Test
    fun aPasswordAttemptSaysItIsSigningIn() {
        show(pending = true, passwordAuth = true)
        compose.onNodeWithText("Signing in…").assertExists()
        compose.onAllNodesWithText("Server").assertCountEquals(0)
        compose.onAllNodesWithText("Password").assertCountEquals(0)
    }

    @Test
    fun theFormReturnsWhenTheAttemptSettles() {
        show(pending = false, passwordAuth = true)
        compose.onNodeWithText("Server").assertExists()
        compose.onNodeWithText("Username").assertExists()
        compose.onAllNodesWithText("Signing in…").assertCountEquals(0)
    }
}
