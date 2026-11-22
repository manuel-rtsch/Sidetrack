package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.FileLocalFavorites
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The liked list: order, idempotence, and the promise that a lost file means "nothing liked". */
class FileLocalFavoritesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store(file: File = File(folder.root, "liked.txt")) = FileLocalFavorites(file)

    @Test fun `a track is liked once and keeps the order it was added in`() {
        val favorites = store()

        favorites.set("3", true)
        favorites.set("1", true)
        favorites.set("3", true)

        assertEquals(listOf("3", "1"), favorites.ids())
        assertTrue(favorites.contains("3"))
        assertFalse(favorites.contains("2"))
    }

    @Test fun `unliking removes it and an album's tracks can be saved at once`() {
        val favorites = store()

        favorites.setAll(listOf("1", "2", "3"), saved = true)
        favorites.set("2", false)

        assertEquals(listOf("1", "3"), favorites.ids())

        favorites.setAll(listOf("1", "3"), saved = false)
        assertTrue(favorites.ids().isEmpty())
    }

    @Test fun `the list survives a new instance and a missing file reads as empty`() {
        val file = File(folder.root, "liked.txt")
        store(file).setAll(listOf("1", "2"), saved = true)

        assertEquals(listOf("1", "2"), store(file).ids())
        assertTrue(store(File(folder.root, "not-created.txt")).ids().isEmpty())
    }
}
