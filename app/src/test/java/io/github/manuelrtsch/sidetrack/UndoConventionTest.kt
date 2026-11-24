package io.github.manuelrtsch.sidetrack

import hrln.interactive.sidekit.design.action.ActionMotion
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.action.undoBar
import hrln.interactive.sidekit.design.icons.SidekitIcon
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Undo is one pattern, so it is pinned in one place: a grey Undo that rises in place, names what it
 * puts back, and is always added to the right of the bar's continuation. Undo is for destructive
 * edits only; creation reports with a notice instead.
 */
class UndoConventionTest {

    private val continuation = SideAction("back", "Back", SidekitIcon.ArrowLeftAlt, onClick = {})

    @Test
    fun `undo is grey, labelled and names the edit`() {
        val undo = undoAction("undoRemove", "remove Track from queue") {}

        assertEquals("Undo", undo.label)
        assertEquals("Undo remove Track from queue", undo.contentDescription)
        assertEquals(SidekitIcon.Undo, undo.icon)
        assertEquals(ActionTone.Muted, undo.tone)
        assertEquals(ActionMotion.Up, undo.motion)
    }

    @Test
    fun `undo is added to the right of the continuation`() {
        val undo = undoAction("undoRemove", "remove Track from queue") {}
        val actions = undoBar(continuation, undo)

        assertEquals(listOf("back", "undoRemove"), actions.map { it.key })
    }

    @Test
    fun `no undo means the bar keeps the continuation alone`() {
        assertEquals(listOf(continuation), undoBar(continuation, null))
    }

    @Test
    fun `the queue replaced bar keeps its status and offers undo last`() {
        val actions = queueReplacedActions(onDismiss = {}, onUndo = {})

        assertEquals(listOf("queueReplaced", "undoQueueReplace"), actions.map { it.key })
        assertEquals("Undo queue replacement", actions.last().contentDescription)
        assertEquals(ActionTone.Muted, actions.last().tone)
    }
}
