package io.github.manuelrtsch.sidetrack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The hint under "Search in" explains why Done does nothing yet. With the on-screen keyboard up the
 * body is only as tall as the IME leaves it, so the line fell past the edge and was drawn cut in
 * half by the keyboard; with the keyboard up the hint is now not drawn at all, and the same rule is
 * carried by the field's own state description instead.
 */
class SearchHintPolicyTest {
    @Test
    fun `the hint is not drawn under the on-screen keyboard`() {
        assertNull(searchHint(typed = "", keyboardVisible = true))
        assertNull(searchHint(typed = "b", keyboardVisible = true))
        assertNull(searchHint(typed = "bowie", keyboardVisible = true))
    }

    @Test
    fun `an empty field asks for two letters`() {
        assertEquals("Type at least two letters", searchHint(typed = "", keyboardVisible = false))
        assertEquals("Type at least two letters", searchHint(typed = "   ", keyboardVisible = false))
    }

    @Test
    fun `one letter asks for more`() {
        assertEquals("Keep typing", searchHint(typed = "b", keyboardVisible = false))
        assertEquals("Keep typing", searchHint(typed = " b ", keyboardVisible = false))
    }

    @Test
    fun `a searchable term points at Done`() {
        assertEquals("Press Done to search", searchHint(typed = "bo", keyboardVisible = false))
        assertEquals("Press Done to search", searchHint(typed = "bowie", keyboardVisible = false))
    }

    @Test
    fun `a term too short is announced on the field`() {
        assertEquals("Keep typing, at least two letters", searchFieldStateDescription("b"))
        assertEquals("Keep typing, at least two letters", searchFieldStateDescription(" b "))
    }

    @Test
    fun `an empty or searchable field says nothing extra`() {
        assertNull(searchFieldStateDescription(""))
        assertNull(searchFieldStateDescription("   "))
        assertNull(searchFieldStateDescription("bo"))
    }
}
