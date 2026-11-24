package io.github.manuelrtsch.sidetrack

import hrln.interactive.sidekit.design.action.ActionMotion
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.NoticeKind
import hrln.interactive.sidekit.design.icons.SidekitIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every prompt is one bar — a decision on the left, a close square on the right — so the shapes are
 * pinned in one place: the three tones, the label that states the act, the close square that never
 * changes the world, and the cursor that lands on it.
 */
class PromptBarTest {

    private fun offering(label: String = "Restore queue") = restoreAction(
        key = "restoreQueue",
        label = label,
        description = "Restore the queue this tap replaced",
        onClick = {}
    )

    @Test
    fun `the restore names the act, stays grey and rises in place`() {
        val restore = offering()

        assertEquals("Restore queue", restore.label)
        assertEquals("Restore the queue this tap replaced", restore.contentDescription)
        assertEquals(SidekitIcon.Undo, restore.icon)
        assertEquals(ActionTone.Muted, restore.tone)
        assertEquals(ActionMotion.Up, restore.motion)
    }

    @Test
    fun `the label states the inverse rather than a bare Undo`() {
        val restore = offering("Restore Sultans of Swing")

        assertTrue("The label has to say what comes back", restore.label != "Undo")
    }

    @Test
    fun `the restore bar leads with the restore and closes with a square`() {
        val actions = restoreBar(offering(), "dismissUndo") {}

        assertEquals(listOf("restoreQueue", "dismissUndo"), actions.map { it.key })
        assertEquals(SidekitIcon.Close, actions.last().icon)
        assertEquals("Dismiss", actions.last().contentDescription)
        assertEquals(ActionTone.Muted, actions.last().tone)
    }

    @Test
    fun `a confirmation is red, asks a question, and its square cancels`() {
        val actions = confirmBar(
            confirm = confirmAction("clearCache", "Clear cache?", "Clear cache? This cannot be undone") {},
            cancelKey = "cancelClearCache",
            onCancel = {}
        )

        assertEquals(listOf("clearCache", "cancelClearCache"), actions.map { it.key })
        assertEquals(ActionTone.Destructive, actions.first().tone)
        assertTrue("The question mark carries the warning", actions.first().label!!.endsWith("?"))
        assertEquals(SidekitIcon.Close, actions.last().icon)
        assertEquals("Cancel", actions.last().contentDescription)
    }

    @Test
    fun `a notice is one message and a square that only shortens the wait`() {
        val normal = listOf(offering())

        assertEquals(normal, noticeBar(null, {}, normal))
        val actions = noticeBar("Added track to Road Trip", {}, normal, NoticeKind.Success)
        assertEquals(listOf("notice", "dismissNotice"), actions.map { it.key })
        assertEquals("Added track to Road Trip", actions.first().label)
        assertEquals(ActionTone.Muted, actions.first().tone)
        assertEquals("Dismiss the notice", actions.last().contentDescription)
    }

    @Test
    fun `a notice outranks a restore, and the restore is still there afterwards`() {
        // The failure is what the user has to read; the offer it hid is not consumed by it.
        val bar = sidetrackActions(
            chrome = ChromeUiState(notice = "Couldn't reach the service", queueReplaced = true),
            onDismissNotice = {},
            onOpenNowPlaying = {},
            onDismissNowPlaying = {},
            actions = listOf(offering()),
            onUndoQueueReplace = {}
        )

        assertEquals(listOf("notice", "dismissNotice"), bar.map { it.key })
        assertNull(bar.firstOrNull { it.key == "restoreQueue" })
    }
}
