package io.github.manuelrtsch.sidetrack

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.SidekitTile
import hrln.interactive.sidekit.design.theme.SidekitTheme
import io.github.manuelrtsch.sidetrack.playback.formatListDuration
import io.github.manuelrtsch.sidetrack.ui.LinkArrow
import io.github.manuelrtsch.sidetrack.ui.LocalSwipeActionRevealed

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    SidekitText(
        text.uppercase(),
        // Keep the label sitting the same distance inside the row content it heads.
        modifier
            .padding(start = rememberMediaRowMetrics().horizontalPadding + 3.dp, top = 7.dp, bottom = 1.dp)
            // Uppercase and the gap above carry the section start for the eye only. Heading is what
            // a screen reader's heading gesture jumps between, and every call site is a section
            // start: recently played, top tracks, albums, up next, recent searches, categories.
            .semantics { heading() },
        style = SidekitTheme.typography.small,
        color = SidekitTheme.colors.surfaceA6
    )
}

@Composable
fun MediaRow(
    item: MediaItem,
    modifier: Modifier = Modifier,
    index: Int? = null,
    showArt: Boolean = true,
    secondary: String = item.creator,
    trailing: String? = formatListDuration(item.durationMs),
    trailingContent: (@Composable () -> Unit)? = null,
    /**
     * Non-null marks the row as the audio that is playing now: a progress line along the row's
     * lower edge, plus "Playing" or "Paused" for a screen reader. Null leaves the row plain.
     */
    audio: RowAudio? = null,
    /** Non-null turns the row into a checkbox with this checked state; [onClick] toggles it. */
    checked: Boolean? = null,
    onClick: () -> Unit
) {
    val colors = SidekitTheme.colors
    val metrics = rememberMediaRowMetrics()
    // A revealed side button is the thing being read: give its row the trailing space back.
    val actionRevealed = LocalSwipeActionRevealed.current
    SidekitTile(
        modifier = modifier.fillMaxWidth().height(metrics.height)
            // The chip is decoration; the row is what a screen reader reads, and the queue's state
            // is not otherwise spoken anywhere in a list.
            .playbackState(audio)
            .then(
                if (checked != null) {
                    Modifier.toggleable(value = checked, role = Role.Checkbox, onValueChange = { onClick() })
                } else {
                    Modifier
                }
            ),
        color = if (item.playable) colors.surfaceA3 else colors.surfaceA2,
        onClick = if (checked == null) onClick else null
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = metrics.horizontalPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showArt) {
                Box(Modifier.size(metrics.artworkSize)) {
                    Cover(
                        item.artRes,
                        Modifier.fillMaxSize(),
                        shape = metrics.artworkShape,
                        artUrl = item.artUrl,
                        requestSize = metrics.artworkSize
                    )
                    // Over the artwork rather than beside the artist: the marker costs the text
                    // block no width and reads as a sticker on the cover.
                    if (item.explicit) {
                        ExplicitBadge(
                            metrics.explicitBadgeSize,
                            metrics.explicitBadgeStyle,
                            // Flush with the cover's lower/right edges: the chip fills the
                            // rounded corner's cutout and reads as part of the artwork rather
                            // than a sticker floating inside it.
                            Modifier.align(Alignment.BottomEnd)
                        )
                    }
                }
                Spacer(Modifier.width(metrics.contentGap))
            } else if (index != null) {
                Box(Modifier.width(metrics.trackNumberWidth), contentAlignment = Alignment.Center) {
                    val number = index + 1
                    SidekitText(
                        "$number",
                        style = if (number >= 100) metrics.trackNumberDenseStyle else metrics.trackNumberStyle,
                        color = colors.surfaceA6
                    )
                }
                Spacer(Modifier.width(metrics.contentGap))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(metrics.textGap)) {
                SidekitText(item.title, style = metrics.titleStyle, maxLines = 1)
                if (!showArt && item.explicit) {
                    // Without a cover the badge needs a home; keep it before the artist so the
                    // secondary line still starts with the marker instead of a stray "· E".
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ExplicitBadge(metrics.explicitBadgeSize, metrics.explicitBadgeStyle)
                        SidekitText(
                            secondary,
                            Modifier.weight(1f),
                            style = metrics.secondaryStyle,
                            color = colors.surfaceA6,
                            maxLines = 1
                        )
                    }
                } else {
                    SidekitText(
                        secondary,
                        style = metrics.secondaryStyle,
                        color = colors.surfaceA6,
                        maxLines = 1
                    )
                }
            }
            if (trailingContent != null) {
                Spacer(Modifier.width(metrics.trailingGap))
                trailingContent()
            } else if (trailing != null && !actionRevealed) {
                Spacer(Modifier.width(metrics.trailingGap))
                SidekitText(
                    trailing,
                    style = metrics.trailingStyle,
                    color = colors.surfaceA6
                )
            }
        }
        // Full-bleed along the row's own lower edge, inside the item's corner: the row already has
        // a height the list is solved for (see [MediaRowMetrics]), so the line is drawn over it and
        // the pitch does not move. One mark and not two: an item row is marked by the offset it is
        // at, the way a podcast row already is, and a chip on the cover as well would put two
        // accents on the one row that already has the loudest cue on the screen.
        if (audio != null) {
            RowPlaybackLine(audio, item.durationMs, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/**
 * The explicit-content marker: a small dark chip in the artwork's corner. It carries its own
 * semantics so a screen reader still hears "Explicit" after the title and artist are merged.
 */
@Composable
private fun ExplicitBadge(size: Dp, style: TextStyle, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(SidekitTheme.colors.surfaceA1.copy(alpha = 0.82f))
            .clearAndSetSemantics { contentDescription = "Explicit" },
        contentAlignment = Alignment.Center
    ) {
        SidekitText("E", style = style, color = SidekitTheme.colors.light)
    }
}

@Composable
fun EmptyMessage(title: String, modifier: Modifier = Modifier, hint: String? = null) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SidekitText(title, style = SidekitTheme.typography.bodySmall, color = SidekitTheme.colors.surfaceA6)
        if (hint != null) SidekitText(hint, style = SidekitTheme.typography.caption, color = SidekitTheme.colors.surfaceA6)
    }
}

@Composable
fun LibraryRow(
    @DrawableRes artRes: Int,
    title: String,
    secondary: String,
    modifier: Modifier = Modifier,
    artUrl: String? = null,
    /** False while a row gutter is open: the squares take the right edge from the arrow. */
    showArrow: Boolean = true,
    /**
     * Non-null marks a collection whose content is playing. A collection row has no offset of its
     * own — the position belongs to the track or episode that is playing, not to the album or
     * playlist it came from — so it gets the chip and never the progress line.
     */
    audio: RowAudio? = null,
    onClick: () -> Unit
) {
    val colors = SidekitTheme.colors
    val metrics = rememberMediaRowMetrics()
    SidekitTile(
        modifier.fillMaxWidth().height(metrics.height).playbackState(audio),
        color = colors.surfaceA3,
        onClick = onClick
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = metrics.horizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(metrics.contentGap)
        ) {
            Box(Modifier.size(metrics.artworkSize)) {
                Cover(artRes, Modifier.fillMaxSize(), shape = metrics.artworkShape, artUrl = artUrl, requestSize = metrics.artworkSize)
                if (audio != null) PlaybackChip(metrics.explicitBadgeSize, Modifier.align(Alignment.BottomStart))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(metrics.textGap)) {
                SidekitText(title, style = metrics.titleStyle, maxLines = 1)
                SidekitText(secondary, style = metrics.secondaryStyle, color = colors.surfaceA6, maxLines = 1)
            }
            if (showArrow) LinkArrow()
        }
    }
}
