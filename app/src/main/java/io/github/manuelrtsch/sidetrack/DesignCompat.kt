package io.github.manuelrtsch.sidetrack

import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.icons.SidekitIcon

/* Undo's bar shape and factory come from Sidekit; its wording lives in UndoActions.kt. */

fun SideAction.Companion.delete(key: String, label: String, onClick: () -> Unit) = SideAction(key, label, SidekitIcon.DeleteForever, tone = ActionTone.Destructive, onClick = onClick)

fun SideAction.Companion.cancel(key: String, label: String, onClick: () -> Unit) = SideAction(key, label, tone = ActionTone.Muted, onClick = onClick)
