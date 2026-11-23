package io.github.manuelrtsch.sidetrack

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.input.Keypad
import hrln.interactive.sidekit.design.input.KeypadModule
import hrln.interactive.sidekit.design.input.SystemKeys
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.design.theme.contentColorFor
import hrln.interactive.sidekit.sdk.input.SharedPreferencesKeypadPreferences

/**
 * The habit a welcome row teaches. Rows are compared by habit, not by their printed label, so a
 * key press can light up the one row it demonstrates on any module.
 */
internal enum class KeypadHabit { Back, Options, RowMenu, Centre, Edges }

/** One printed key and the one or two sentences a new listener needs to remember it. */
internal data class KeypadEssential(
    /** The label printed on the key. The gesture is described in [detail]. */
    val key: String,
    val detail: String,
    val habit: KeypadHabit
)

/**
 * The welcome row a key press demonstrates, or null when the key teaches nothing.
 *
 * [keyCode] is the code the screen receives: Sidekit rewrites the game controller's and the mini
 * QWERTY's navigation keys to the canonical ones first (see `NavControls`), so B arrives as Back
 * and X as Options. Options itself never reaches a screen - the bar swallows it - which is why
 * [KeypadEssentials] also lights that row while the bar is open.
 */
internal fun keypadHabitForKey(module: KeypadModule, keyCode: Int): KeypadHabit? = when {
    SystemKeys.isOptions(keyCode) -> KeypadHabit.Options

    keyCode == KeyEvent.KEYCODE_CALL || keyCode == KeyEvent.KEYCODE_BACK -> KeypadHabit.Back

    // The Sundial's centre and edges are media keys, and a headset sends the same codes; only
    // claim them while the Sundial is the module the screen is teaching.
    module == KeypadModule.Sundial && keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ->
        KeypadHabit.Centre

    module == KeypadModule.Sundial &&
        (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) ->
        KeypadHabit.Edges

    else -> null
}

/**
 * The module's name as Sidetrack prints it. Sidekit calls the controller a "Game controller";
 * Sidetrack says "Gamepad", which fits the layout row and reads as one word next to the others.
 */
internal fun keypadModuleLabel(module: KeypadModule): String =
    if (module == KeypadModule.GamePad) "Gamepad" else module.label

/** The module after [current] in the order the modules are printed, wrapping at the end. */
internal fun nextKeypadModule(current: KeypadModule): KeypadModule {
    val modules = KeypadModule.entries
    val index = modules.indexOf(current).takeIf { it >= 0 } ?: 0
    return modules[(index + 1) % modules.size]
}

/**
 * Applies a picked keypad module to the attached keypad, or remembers it when there is none.
 *
 * A custom mapping fixes the module by design: picking a module is choosing which one the attached
 * keys are read as, which is exactly what [Keypad.useCustomMapping] does. Without this branch the
 * mapping would keep overriding the choice and the picker would appear to do nothing.
 */
internal fun selectKeypadModule(
    keypad: Keypad?,
    prefs: SharedPreferencesKeypadPreferences,
    module: KeypadModule
) {
    when {
        keypad == null -> prefs.writeModule(module)
        keypad.mapper.customModule != null -> keypad.useCustomMapping(module)
        else -> keypad.chooseModule(module)
    }
}

/**
 * The keypad habits Sidetrack depends on, written for the attached module.
 *
 * T9 and QWERTY have dedicated Back and Options keys; the Sundial and GamePad turn gestures on
 * other keys into the same controls. Keeping the translation here means Welcome and the Keypad settings
 * cannot drift into giving different instructions for the same module.
 */
internal fun keypadEssentials(module: KeypadModule): List<KeypadEssential> {
    fun back(key: String, detail: String = "Go back a screen.") = KeypadEssential(key, detail, KeypadHabit.Back)

    fun options(key: String, detail: String = "Tap for this page’s actions.") = KeypadEssential(key, detail, KeypadHabit.Options)

    val item = KeypadEssential("•••", "Press ••• for item actions.", KeypadHabit.RowMenu)
    return when (module) {
        KeypadModule.T9, KeypadModule.Qwerty -> listOf(
            back("Back"),
            options("Options"),
            item
        )

        KeypadModule.Sundial -> listOf(
            KeypadEssential("Centre", "Tap to play or pause. Hold to confirm.", KeypadHabit.Centre),
            KeypadEssential(
                "Edges",
                "Tap the left edge for the previous track, the right edge for the next.",
                KeypadHabit.Edges
            ),
            back("Tab", "Hold Tab to go back a screen."),
            options("Enter", "Hold Enter for this page’s actions."),
            item
        )

        KeypadModule.GamePad -> listOf(
            back("Back"),
            options("X"),
            item
        )
    }
}

/**
 * The compact key cheat sheet on Welcome. It is deliberately not a diagram:
 * the full [SidetrackKeypadGuide] is a reference and lives behind "Keypad guide", while these
 * rows are the actions a new listener has to know immediately.
 */
@Composable
internal fun KeypadEssentials(
    module: KeypadModule,
    modifier: Modifier = Modifier,
    customMapping: Boolean = false,
    /** The row the last key press demonstrated, held for a moment so the press has an answer. */
    pressed: KeypadHabit? = null,
    /** The Options bar is open, which is what that key did even though it never reaches a screen. */
    optionsOpen: Boolean = false,
    /** When set, the card is a focus target and activates like a row. */
    onClick: (() -> Unit)? = null
) {
    val essentials = remember(module) { keypadEssentials(module) }
    val surface = SidekitTheme.colors.surfaceA3
    val contentColor = SidekitTheme.colors.contentColorFor(surface)
    // The symbols share one column, measured from the widest of them, so every sentence to their
    // right starts on the same axis instead of stepping in and out with the width of its key.
    val measurer = rememberTextMeasurer()
    val keyStyle = SidekitTheme.typography.small
    val density = LocalDensity.current
    val keycapWidth = remember(essentials, keyStyle, density) {
        val widest = essentials.maxOf { measurer.measure(it.key, keyStyle).size.width }
        // Never narrower than a keycap reads as a key, and never narrow enough to clip a label.
        (with(density) { widest.toDp() } + KEYCAP_PADDING * 2).coerceAtLeast(76.dp)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(SidekitTheme.spacing.pageGap)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(SidekitTheme.shapes.item)
                .background(surface)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            essentials.forEach { essential ->
                val highlighted = essential.habit == pressed ||
                    (optionsOpen && essential.habit == KeypadHabit.Options)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Keycap(essential.key, keycapWidth, highlighted = highlighted)
                    SidekitText(
                        essential.detail,
                        Modifier.weight(1f),
                        style = SidekitTheme.typography.small,
                        color = contentColor,
                        maxLines = 2
                    )
                }
            }
        }
        if (customMapping) {
            SidekitText(
                "A custom mapping is active, so the printed labels may differ.",
                Modifier.padding(horizontal = 18.dp),
                style = SidekitTheme.typography.tiny,
                color = SidekitTheme.colors.surfaceA6,
                maxLines = 2
            )
        }
    }
}

/** The space either side of a keycap's label. */
private val KEYCAP_PADDING = 10.dp

@Composable
private fun Keycap(label: String, width: Dp, highlighted: Boolean = false) {
    // The accent is the case colour every live action wears, so the answer reads as "that key, now".
    val color = if (highlighted) SidekitTheme.colors.primaryA2 else SidekitTheme.colors.surfaceA4
    Box(
        Modifier
            .height(38.dp)
            .width(width)
            .clip(SidekitTheme.shapes.control)
            .background(color)
            .padding(horizontal = KEYCAP_PADDING),
        contentAlignment = Alignment.Center
    ) {
        SidekitText(
            label,
            style = SidekitTheme.typography.small,
            color = SidekitTheme.colors.contentColorFor(color),
            maxLines = 1
        )
    }
}
