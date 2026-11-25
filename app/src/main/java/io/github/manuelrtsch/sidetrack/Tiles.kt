package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import hrln.interactive.sidekit.design.components.SidekitIconItem
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.SidekitTile
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import io.github.manuelrtsch.sidetrack.ui.VinylSpinner

@Composable
fun RowScope.IconTile(
    icon: SidekitIcon,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: TileTone = TileTone.Neutral,
    weight: Float = 1f
) {
    val colors = SidekitTheme.colors
    SidekitIconItem(
        icon,
        label,
        modifier.weight(weight),
        color = tone.colour(colors),
        iconSize = SidekitTheme.spacing.tileIconSize,
        onClick = onClick
    )
}

/**
 * Repeat is a three-state cycle (off, context, track), so the two on-states need distinct
 * glyphs: a tone change alone cannot say whether the queue repeats everything or only the
 * current track. The plain repeat glyph in the passive tone is off, plain repeat is the context,
 * and repeat-one is the current track.
 */
@Composable
fun RowScope.RepeatTile(
    repeat: RepeatMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    weight: Float = 0.85f
) {
    val colors = SidekitTheme.colors
    val active = repeat != RepeatMode.Off
    val icon = if (repeat == RepeatMode.Track) R.drawable.ic_repeat_one else R.drawable.ic_repeat
    SidekitTile(
        modifier = modifier.weight(weight),
        color = (if (active) TileTone.On else TileTone.Neutral).colour(colors),
        onClick = onClick,
        onClickLabel = repeat.label
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = repeat.label,
            modifier = Modifier.size(SidekitTheme.spacing.tileIconSize),
            colorFilter = ColorFilter.tint(colors.light)
        )
    }
}

/**
 * A deliberate text tile for podcast transport (±30 s). It avoids inventing a new icon while
 * keeping the five-tile transport row intact.
 */
@Composable
fun RowScope.SkipTile(
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    weight: Float = 0.85f
) {
    SidekitTile(
        modifier = modifier.weight(weight),
        color = TileTone.Neutral.colour(SidekitTheme.colors),
        onClick = onClick,
        onClickLabel = contentDescription
    ) {
        SidekitText(
            label,
            style = SidekitTheme.typography.label,
            color = SidekitTheme.colors.light
        )
    }
}

/**
 * The play slot while a source is still resolving: the record's arc instead of a pause button
 * that promises audio. Pressing it still cancels the start through the ordinary play/pause path.
 */
@Composable
fun StartingTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = SidekitTheme.colors
    SidekitTile(
        modifier = modifier,
        color = TileTone.Play.colour(colors),
        onClick = onClick,
        onClickLabel = "Cancel starting playback"
    ) {
        VinylSpinner(
            describeAs = "Starting playback",
            diameter = SidekitTheme.spacing.tileIconSize,
            color = colors.light
        )
    }
}

@Composable
fun PlayTile(
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    playLabel: String = "Play",
    pauseLabel: String = "Pause"
) {
    SidekitIconItem(
        if (playing) SidekitIcon.Pause else SidekitIcon.PlayArrow,
        if (playing) pauseLabel else playLabel,
        modifier,
        color = SidekitTheme.colors.emphasisA1,
        iconSize = SidekitTheme.spacing.tileIconSize,
        onClick = onClick
    )
}

/**
 * A page-local play control. It is a pause button only while this page is where the queue came
 * from, so it can never pause unrelated content, and it is a play button for a page whose items
 * merely contain the item playing now.
 */
@Composable
fun ContextPlayTile(
    /** The collection this tile belongs to. */
    page: MediaKey?,
    /** The collection the queue was started from; null when the queue came from somewhere else. */
    origin: MediaKey?,
    transport: Transport,
    onPlay: () -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
    playLabel: String = "Play",
    pauseLabel: String = "Pause"
) {
    // Whether this page is where the queue came from. The old test asked whether the item playing
    // now happened to be on this page, which forgot the page as soon as another screen started
    // something and showed Play for an album that was audibly playing; the queue records the page
    // it was started from, so the tile can ask it directly.
    val controlsHere = page != null && page == origin
    val onClick = {
        if (controlsHere) onPlayPause() else onPlay()
    }
    if (controlsHere && transport == Transport.Starting) {
        StartingTile(onClick, modifier)
    } else {
        PlayTile(
            playing = controlsHere && transport.isPlaying,
            onClick = onClick,
            modifier = modifier,
            playLabel = playLabel,
            pauseLabel = pauseLabel
        )
    }
}
