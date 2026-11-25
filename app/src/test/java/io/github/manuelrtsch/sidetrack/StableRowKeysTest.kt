package io.github.manuelrtsch.sidetrack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A reorderable list cannot rank duplicates by position: crossing an identical row would rename
 * both of them, which replaces the moving row's node and, mid-drag, its gesture.
 */
class StableRowKeysTest {
    private val a = "spotify:track:a"
    private val b = "spotify:track:b"

    @Test fun `the same track twice gets two keys`() {
        val keys = StableRowKeys().keys(listOf(a, a))

        assertEquals(2, keys.toSet().size)
        assertTrue(keys[0].startsWith(a))
        assertTrue(keys[1].startsWith(a))
    }

    @Test fun `moving the second duplicate past the first keeps both keys`() {
        val keys = StableRowKeys()
        val before = keys.keys(listOf(a, a, b))

        keys.move(from = 1, to = 0)
        val after = keys.keys(listOf(a, a, b))

        // The entry that was second is now first and keeps the key it had.
        assertEquals(before[1], after[0])
        assertEquals(before[0], after[1])
        assertEquals(before[2], after[2])
    }

    @Test fun `moving an entry keeps its key when the list is re-read`() {
        val keys = StableRowKeys()
        val before = keys.keys(listOf(a, b, a))

        keys.move(from = 2, to = 0)

        // The data is now a, a, b: the moved second `a` is first and keeps its key.
        assertEquals(listOf(before[2], before[0], before[1]), keys.keys(listOf(a, a, b)))
    }

    @Test fun `an appended row gets a new key and the old ones stay`() {
        val keys = StableRowKeys()
        val before = keys.keys(listOf(a, b))

        val after = keys.keys(listOf(a, b, a))

        assertEquals(before, after.take(2))
        assertNotEquals(after[2], after[0])
        assertNotEquals(after[2], after[1])
    }
}
