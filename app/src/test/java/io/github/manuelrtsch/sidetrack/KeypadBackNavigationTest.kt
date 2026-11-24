package io.github.manuelrtsch.sidetrack

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keypad's Back key has to do exactly what the screen's left action does. A route in the set
 * that has no way back would pop to a screen the user did not come from; a route missing from it
 * would strand the user on a screen whose only exit is a button the D-pad cannot reach.
 *
 * The coverage is read from [KEYPAD_BACK_ROUTES] instead of a second list: a screen added to the
 * set is asserted here the moment it is listed, and one removed from it fails here too. A separate
 * list drifted once — the playlist routes sat in the set while no test named them.
 */
class KeypadBackNavigationTest {
    @Test
    fun `every route with a left back action handles the keypad Back key`() {
        KEYPAD_BACK_ROUTES.forEach { route ->
            assertTrue("$route should take Back", keypadBackIsLeftAction(route, canPop = true))
        }
    }

    @Test
    fun `the playlist routes take Back to the page that opened them`() {
        assertTrue(ADD_TO_PLAYLIST in KEYPAD_BACK_ROUTES)
        assertTrue(CREATE_PLAYLIST in KEYPAD_BACK_ROUTES)
        assertTrue(keypadBackIsLeftAction(ADD_TO_PLAYLIST, canPop = true))
        assertTrue(keypadBackIsLeftAction(CREATE_PLAYLIST, canPop = true))
    }

    @Test
    fun `Home and Welcome leave Back to the system`() {
        assertFalse(SIDE_PLAY_START in KEYPAD_BACK_ROUTES)
        assertFalse(SIDE_PLAY_ONBOARDING in KEYPAD_BACK_ROUTES)
        assertFalse(keypadBackIsLeftAction(SIDE_PLAY_START, canPop = true))
        assertFalse(keypadBackIsLeftAction(SIDE_PLAY_ONBOARDING, canPop = true))
        // Sign in carries a Back action of its own now, so the keypad key follows it and only
        // falls back to the system when nothing is behind (a signed-out launch starts here).
        assertFalse(keypadBackIsLeftAction(SIDE_PLAY_SIGN_IN, canPop = false))
    }

    @Test
    fun `a root destination cannot pop even if its route usually has a back action`() {
        KEYPAD_BACK_ROUTES.forEach { route ->
            assertFalse("$route at the root", keypadBackIsLeftAction(route, canPop = false))
        }
    }

    @Test
    fun `an unknown or missing route leaves Back to the system`() {
        assertFalse(keypadBackIsLeftAction("sidetrack/not-a-screen", canPop = true))
        assertFalse(keypadBackIsLeftAction(null, canPop = true))
    }
}
