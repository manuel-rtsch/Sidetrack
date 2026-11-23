package io.github.manuelrtsch.sidetrack

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
    val rows = remember(module, resources) { keypadGuideRows(module, resources) }
    var selected by remember(module) { mutableStateOf<KeypadGuideKey?>(null) }
    val spacing = SidekitTheme.spacing
    Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing.pageGap)) {
        selected?.let { key ->
            SidekitCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SidekitText(key.label, style = SidekitTheme.typography.label, maxLines = 1)
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
                    row.forEach { key ->
                        if (key == null) {
                            Spacer(Modifier.weight(1f).height(keyHeight))
                        } else {
                            SidekitTile(
                                Modifier.weight(1f).height(keyHeight),
                                color = if (selected == key) SidekitTheme.colors.emphasisA1 else SidekitTheme.colors.surfaceA3,
                                onClick = { selected = key },
                                onClickLabel = stringResource(SdkR.string.sk_sdk_guide_show_key, key.label)
                            ) {
                                SidekitText(key.label, style = SidekitTheme.typography.small, maxLines = 1)
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
