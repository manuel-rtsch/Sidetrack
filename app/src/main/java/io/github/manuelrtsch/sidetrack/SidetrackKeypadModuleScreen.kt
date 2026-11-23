package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.input.KeypadModule
import hrln.interactive.sidekit.design.theme.SidekitTheme

/**
 * The four keypad layouts Sidekit understands, as a one-list picker: choosing a row applies it and
 * leaves the page open so the check mark moves where the press landed.
 */
@Composable
internal fun SidetrackKeypadModuleScreen(
    module: KeypadModule,
    onBack: () -> Unit,
    onSelect: (KeypadModule) -> Unit,
    backLabel: String = "Back"
) {
    SidekitScaffold(
        title = "Keypad layout",
        actions = listOf(SideAction.back("back", backLabel, onBack))
    ) { padding ->
        val listState = rememberLazyListState()
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        SidekitList(padding, modifier = Modifier.selectableGroup(), state = listState) {
            KeypadModule.entries.forEachIndexed { index, option ->
                val selected = option == module
                item(key = option.name) {
                    SidetrackItem(
                        keypadModuleLabel(option),
                        Modifier.listFade(listState, index).then(
                            if (index == 0) Modifier.focusRequester(first) else Modifier
                        ),
                        icon = if (selected) SidekitIcon.Check else null,
                        containerColor = if (selected) SidekitTheme.colors.successA1 else null,
                        selected = selected,
                        selectionRole = Role.RadioButton,
                        onClick = { onSelect(option) }
                    )
                }
            }
        }
    }
}
