package io.github.manuelrtsch.sidetrack

import hrln.interactive.sidekit.design.action.SideAction

/**
 * The app's one undo affordance: grey, the Undo glyph, rising in place, and always saying what it
 * puts back — "Undo delete Road Trip", not a bare "Undo".
 *
 * Undo is only for destructive edits: removing, clearing or deleting something the user can see.
 * Creation (adding to a playlist, queueing a collection) reports with a notice instead, because
 * "undo" there would be a second destructive action wearing the safety label.
 *
 * The label stays "Undo" on purpose: every string in this app is written in English, so taking
 * Sidekit's localised default would make one button change language under a translation the rest
 * of the screen does not have.
 */
fun undoAction(key: String, what: String, onClick: () -> Unit): SideAction = SideAction.undo(
    key = key,
    label = "Undo",
    contentDescription = "Undo $what",
    onClick = onClick
)
