package io.github.manuelrtsch.sidetrack

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The category square is the one size in Sidetrack that is not on the row scale: it is bound by the
 * page width, so it has a single value in both compact and spacious mode. These assertions are what
 * keeps it from being "unified" with the artwork covers later: five covers do not fill the page.
 */
class CategoryCellTest {
    private val pageWidth = 480.dp
    private val pagePadding = 10.dp
    private val gap = 3.dp
    private val columns = 5

    private val contentWidth = pageWidth - pagePadding * 2

    @Test
    fun `five squares and four gaps fill the page exactly`() {
        val cell = categoryCell(contentWidth, columns, gap)
        assertEquals(contentWidth, cell * columns + gap * (columns - 1))
    }

    @Test
    fun `the square is 89_6 and does not follow the row scale`() {
        val cell = categoryCell(contentWidth, columns, gap)
        assertEquals(89.6f, cell.value, 0.01f)
        // Between the compact row and the spacious one, which is why it reads the same in both.
        assertEquals(true, cell > 78.dp)
        assertEquals(true, cell < 97.dp)
    }

    @Test
    fun `a cover-sized square would not fill the page`() {
        val cover = 77.dp
        val rowWidth = cover * columns + gap * (columns - 1)
        // 63 dp of the page would be left empty, or the gaps would have to grow to 18.75.
        assertEquals(397f, rowWidth.value, 0.01f)
        assertEquals(63f, (contentWidth - rowWidth).value, 0.01f)
        assertEquals(18.75f, ((contentWidth - cover * columns) / (columns - 1)).value, 0.01f)
    }
}
