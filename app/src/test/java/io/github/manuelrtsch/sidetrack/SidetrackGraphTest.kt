package io.github.manuelrtsch.sidetrack

import android.content.Intent
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What a route means before Navigation sees it, and what a launch intent asks the shell to do.
 *
 * The keypad Back coverage lives in [KeypadBackNavigationTest], which reads [KEYPAD_BACK_ROUTES]
 * instead of repeating it, so this file keeps only the addressing rules.
 */
@RunWith(RobolectricTestRunner::class)
class SidetrackGraphTest {
    @Test fun `a JSON null artwork url does not become a null route argument`() {
        // Navigation's StringType turns the literal "null" into a real null, which fails a
        // non-nullable argument before the artwork screen is even built.
        val route = artworkRoute(0, "null", "null")
        assertTrue(route.contains("url=&"))
        assertFalse(route.contains("url=null"))
        assertTrue(route.contains("label=Artwork"))
        assertFalse(route.contains("label=null"))
    }

    @Test fun `the notification action asks for now playing`() {
        assertTrue(SidetrackLinks.handle(Intent(SidetrackLinks.ACTION_OPEN_NOW_PLAYING)))
    }

    @Test fun `any other action is ignored`() {
        assertFalse(SidetrackLinks.handle(Intent(Intent.ACTION_MAIN)))
    }

    @Test fun `a missing intent is ignored`() {
        assertFalse(SidetrackLinks.handle(null))
    }

    @After
    fun tearDown() {
        // The pending request is process state; leaving it set would navigate the next test.
        SidetrackLinks.reset()
    }
}
