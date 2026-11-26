package io.github.manuelrtsch.sidetrack

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.components.SidekitCard
import hrln.interactive.sidekit.design.components.SidekitIconImage
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.SidekitTile
import hrln.interactive.sidekit.design.input.KeypadModule
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.sdk.R as SdkR
import hrln.interactive.sidekit.sdk.settings.KeypadGuideKey
import hrln.interactive.sidekit.sdk.settings.keypadGuideRows

/**
 * The keypad diagram with a detail card that grows to fit what the key does.
 *
 * Sidekit's [hrln.interactive.sidekit.sdk.settings.KeypadGuide] draws the same diagram, but its
 * answer card is a fixed 112 dp tall and clips each action to two lines. A key with a long tap and
 * a long hold loses the end of both, so Sidetrack renders the rows itself and lets the card take the
 * height the sentences need.
 */
@Composable
internal fun SidetrackKeypadGuide(module: KeypadModule, modifier: Modifier = Modifier) {
    val resources = LocalContext.current.resources
    val rows = remember(module, resources) { guideRows(module, resources) }
    var selected by remember(module) { mutableStateOf<GuideKey?>(null) }
    val spacing = SidekitTheme.spacing
    Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing.pageGap)) {
        selected?.let { guide ->
            val key = guide.key
            SidekitCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        key.icon?.let { SidekitIconImage(it, size = 28.dp) }
                        SidekitText(key.label, style = SidekitTheme.typography.label, maxLines = 1)
                    }
                    GuideAction(stringResource(SdkR.string.sk_sdk_guide_tap), key.tap)
                    GuideAction(
                        stringResource(SdkR.string.sk_sdk_guide_hold),
                        key.hold ?: stringResource(SdkR.string.sk_sdk_guide_no_long_press),
                        muted = key.hold == null
                    )
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(spacing.itemOuterGap)) {
            rows.forEach { row ->
                val keyHeight = if (row.size > 3) 52.dp else 64.dp
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.itemOuterGap)) {
                    row.forEach { guide ->
                        if (guide == null) {
                            Spacer(Modifier.weight(1f).height(keyHeight))
                        } else {
                            val key = guide.key
                            // What is printed on the key: its glyph, or the legend on a blank cap.
                            val icon = if (guide.printed == null && guide.showIcon) key.icon else null
                            val legend = guide.printed ?: key.label.takeIf { guide.showLabel }
                            SidekitTile(
                                Modifier.weight(1f).height(keyHeight),
                                color = if (selected == guide) SidekitTheme.colors.emphasisA1 else SidekitTheme.colors.surfaceA3,
                                onClick = { selected = guide },
                                onClickLabel = stringResource(SdkR.string.sk_sdk_guide_show_key, key.label)
                            ) {
                                // The printed glyph above the key's name, as on the module itself.
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    icon?.let { SidekitIconImage(it, size = 20.dp) }
                                    legend?.let {
                                        SidekitText(
                                            it,
                                            style = if (icon == null) SidekitTheme.typography.small else SidekitTheme.typography.tiny,
                                            // Two lines so a two-word name still fits a fifth of the width.
                                            maxLines = if (icon == null) 1 else 2
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (module == KeypadModule.Sundial) {
            SidekitText(
                stringResource(SdkR.string.sk_sdk_guide_sundial_note),
                style = SidekitTheme.typography.small,
                color = SidekitTheme.colors.surfaceA6,
                maxLines = 2
            )
        }
        if (!module.typesOnKeypad) {
            SidekitText(
                stringResource(SdkR.string.sk_sdk_guide_onscreen_keyboard),
                style = SidekitTheme.typography.small,
                color = SidekitTheme.colors.surfaceA6,
                maxLines = 2
            )
        }
    }
}

/**
 * One cell of the diagram. [key] carries the translated name and the actions the detail card
 * shows; [printed] replaces that name with the legend on the physical key, and [showLabel] and
 * [showIcon] decide what the cap itself draws.
 */
private data class GuideKey(
    val key: KeypadGuideKey,
    val printed: String? = null,
    val showLabel: Boolean = true,
    val showIcon: Boolean = true
)

/**
 * The rows as the module actually looks. Sidekit's rows carry every action, but their caps are
 * plain labels; here the D-pad and chrome keys become their printed glyphs, the controller's face
 * buttons keep the letters on the shell, and the mini QWERTY's five-key top and bottom rows are
 * folded back into the shape of the hardware.
 */
private fun guideRows(module: KeypadModule, resources: Resources): List<List<GuideKey?>> {
    val rows = keypadGuideRows(module, resources)
    return when (module) {
        // Everything above the numpad is a glyph key. The centre key is the one cap that really
        // prints a word, so it keeps its "OK".
        KeypadModule.T9 -> rows.map { row ->
            row.map { key -> key?.let { GuideKey(it, showLabel = it.icon == null) } }
        }

        // The D-pad, Search and Home are glyphs; the face buttons are printed Y / X / B / A.
        // The positions mirror Sidekit's five-column controller: Y at (2,3), X at (3,2),
        // B at (3,4) and A at (4,3). The other glyph keys keep only their icon.
        KeypadModule.GamePad -> rows.mapIndexed { r, row ->
            row.mapIndexed { c, key ->
                key?.let {
                    when (r to c) {
                        0 to 1, 0 to 4, 1 to 0, 1 to 2, 2 to 1, 4 to 0 -> GuideKey(it, showLabel = false)
                        2 to 3 -> GuideKey(it, printed = "Y", showIcon = false)
                        3 to 2 -> GuideKey(it, printed = "X", showIcon = false)
                        3 to 4 -> GuideKey(it, printed = "B", showIcon = false)
                        4 to 3 -> GuideKey(it, printed = "A", showIcon = false)
                        else -> GuideKey(it)
                    }
                }
            }
        }

        KeypadModule.Qwerty -> qwertyRows(rows, resources)

        else -> rows.map { row -> row.map { key -> key?.let { GuideKey(it) } } }
    }
}

private fun qwertyRows(rows: List<List<KeypadGuideKey?>>, resources: Resources): List<List<GuideKey?>> {
    val navigation = rows.firstOrNull().orEmpty()
    val call = navigation.getOrNull(0)
    val home = navigation.getOrNull(1)
    val endCall = navigation.getOrNull(2)
    // The two dash keys beside Home are plain Back and Recents. They print a dash, not a word.
    val back = call?.let { KeypadGuideKey(it.label, it.tap) }
    val recents = KeypadGuideKey(
        resources.getString(R.string.keypad_guide_recents),
        resources.getString(R.string.keypad_guide_show_recents)
    )
    val dash = "\u2013"
    val top = listOf(
        call?.let { GuideKey(it, showLabel = false) },
        back?.let { GuideKey(it, printed = dash, showIcon = false) },
        home?.let { GuideKey(it, showLabel = false) },
        GuideKey(recents, printed = dash, showIcon = false),
        endCall?.let { GuideKey(it, showLabel = false) }
    )
    val letters = rows.drop(1).dropLast(2).map { row -> row.map { key -> key?.let { GuideKey(it) } } }
    // The rocker, SYM, Space, aA and Enter share the bottom row on the module.
    val suggestRow = rows.getOrNull(rows.size - 2)
    val enterRow = rows.lastOrNull()
    val bottom = listOf(
        suggestRow?.getOrNull(0),
        suggestRow?.getOrNull(1),
        enterRow?.getOrNull(0),
        suggestRow?.getOrNull(2),
        enterRow?.getOrNull(1)
    ).map { key -> key?.let { GuideKey(it) } }
    return listOf(top) + letters + listOf(bottom)
}

@Composable
private fun GuideAction(label: String, action: String, muted: Boolean = false) {
    Row(
        Modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        SidekitText(
            label,
            Modifier.width(52.dp),
            style = SidekitTheme.typography.tiny,
            color = SidekitTheme.colors.surfaceA6,
            maxLines = 1
        )
        if (muted) {
            SidekitText(
                action,
                Modifier.weight(1f),
                style = SidekitTheme.typography.tiny,
                color = SidekitTheme.colors.surfaceA6,
                maxLines = Int.MAX_VALUE
            )
        } else {
            SidekitText(
                action,
                Modifier.weight(1f),
                style = SidekitTheme.typography.tiny,
                maxLines = Int.MAX_VALUE
            )
        }
    }
}
