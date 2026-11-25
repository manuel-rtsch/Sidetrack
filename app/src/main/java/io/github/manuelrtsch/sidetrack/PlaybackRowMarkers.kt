package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hrln.interactive.sidekit.design.theme.SidekitTheme
import kotlin.math.floor
import kotlin.math.sqrt
import kotlinx.coroutines.flow.StateFlow

/**
 * What a list row says about the audio, and it says it once.
 *
 * The row that is playing now is marked by where it has got to: [RowPlaybackLine] along its lower
 * edge, the line a podcast row already draws for where it was left off. A collection row has no
 * offset of its own, so an album, playlist, show or artist whose content is playing is marked by
 * [PlaybackChip] on its cover instead. Never both: two accents on one row at 480 px read as clutter
 * rather than as one fact, and a chip beside the line would be the second telling.
 *
 * Built once per list from [PlaybackUiState.audioFor] rather than read per row, so a row receives
 * a small value it can compare and skip on. [position] is carried, not collected: only
 * [RowPlaybackLine] subscribes to it, one composable per screen at most, so the one-second clock
 * tick never reaches the list itself.
 *
 * There is deliberately no animation. A permanently running equaliser would keep the display
 * pipeline awake for as long as audio plays (the same reason Now Playing only animates the bars in
 * its transient confirmation), and the frozen-versus-moving line already separates playing from
 * paused: a paused queue draws its line at the offset it stopped at, in the muted grey.
 */
@Immutable
data class RowAudio(
    /** True while the transport runs; false when the queue is only parked on this item. */
    val playing: Boolean,
    /** The seek clock, for the current row's progress line. */
    val position: StateFlow<Long>
)

/** What a screen reader hears about a row's playback; see [playbackState]. */
private const val PLAYING_STATE = "Playing"
private const val PAUSED_STATE = "Paused"

/**
 * The row's own report of its playback, for a screen reader. The chip on the cover is silenced
 * (it would otherwise be read twice), so the state has to be announced by the row that carries it.
 */
fun Modifier.playbackState(audio: RowAudio?): Modifier = if (audio == null) {
    this
} else {
    semantics { stateDescription = if (audio.playing) PLAYING_STATE else PAUSED_STATE }
}

/** The chip's node, so a test can tell a marked collection from an unmarked one. */
internal const val ROW_CHIP_TAG = "rowChip"

/**
 * The marker a collection row carries: the explicit badge's dark chip, with three bars instead of
 * a letter.
 *
 * It is drawn rather than iconed because Sidekit has no equaliser glyph; the bars are the same
 * idea as [PlayingBars] on the Now Playing tile, held still. It takes [MediaRowMetrics.explicitBadgeSize],
 * so the marker is two-fifths of the cover wide rather than a speck on it. Silenced for a screen
 * reader: the row it sits on says "Playing" in its own state description, and a chip that
 * announced itself too would be read twice.
 */
@Composable
fun PlaybackChip(size: Dp, modifier: Modifier = Modifier) {
    val color = SidekitTheme.colors.light
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(SidekitTheme.colors.surfaceA1.copy(alpha = 0.82f))
            .testTag(ROW_CHIP_TAG)
            .clearAndSetSemantics { }
            .drawBehind {
                // Three bars in a 2:3:2.4 rhythm, the tallest in the middle: a level meter read at
                // a glance. Fractions of the chip, so both row sizes share one drawing.
                val barWidth = this.size.minDimension * 0.13f
                val gap = barWidth * 0.62f
                val total = barWidth * 3 + gap * 2
                val left = (this.size.width - total) / 2f
                val heights = floatArrayOf(0.42f, 0.62f, 0.5f)
                val radius = CornerRadius(barWidth / 2f, barWidth / 2f)
                val bottom = this.size.height * 0.5f + this.size.height * 0.31f
                heights.forEachIndexed { index, fraction ->
                    val height = this.size.height * fraction
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(left + index * (barWidth + gap), bottom - height),
                        size = Size(barWidth, height),
                        cornerRadius = radius
                    )
                }
            }
    )
}

/** The progress line's node, and its fill's, so a test can tell "not started yet" from "not there". */
internal const val ROW_PROGRESS_TAG = "rowProgress"
internal const val ROW_PROGRESS_FILL_TAG = "rowProgressFill"

/** The line's thickness, and the depth the tile's corner arc has to clear to swallow an end. */
internal val RowProgressHeight = 4.dp

/**
 * How far inside the tile's edge the line starts: the point where the bottom corner's
 * [ItemCornerRadius] arc has climbed the line's full [RowProgressHeight].
 *
 * The arc is the full radius wide across the row's height, but the line only occupies the bottom
 * few dp, so the arc has already moved this far in by the time it reaches the line's top. Inset by
 * the radius instead, the line's square end sat in the flat stretch and showed its hard corner.
 * Inset by this, the end is under the arc: the curve swallows it, the fill's first pixel appears on
 * the arc rather than after it, and a full line ends exactly on the visible edge.
 *
 * Derived in dp, not px: dp-to-px is linear, so the same number holds at every density. Floored to
 * the whole dp because padding lands on whole pixels: rounded up, the end would peek back out from
 * under the arc and show a sliver of its square corner.
 */
internal val RowProgressInset = run {
    val radius = ItemCornerRadius.value
    val height = RowProgressHeight.value
    floor(radius - sqrt(2f * radius * height - height * height)).dp
}

/**
 * The current row's own lower edge, from 0 to [durationMs]: how far the audio has come, read in
 * the row instead of on Now Playing.
 *
 * This is the same language as a podcast row's resume line (four dp, muted track, accent fill,
 * part of the row and not a control). It is the only thing on a list that subscribes to the seek
 * clock, and it recomposes alone.
 *
 * The line runs wider than the flat part of the lower edge and lets the tile's own rounding swallow
 * its ends: inset by [RowProgressInset] at both ends, each square end sits exactly where the corner
 * arc has climbed the line's full height, so the curve hides it and the fill appears from the very
 * first pixel. Inset by the whole [ItemCornerRadius] instead, the ends landed in the flat stretch
 * and their hard corners read as cut off; drawn full-bleed, the line was hidden for the first and
 * last seconds of a track.
 *
 * The track is drawn from the first frame rather than only once there is something to fill: this is
 * the row's one mark, so a row playing from the top still has to look different from its
 * neighbours. An item that reports no duration has nothing to measure and draws nothing.
 */
@Composable
fun RowPlaybackLine(audio: RowAudio, durationMs: Long, modifier: Modifier = Modifier) {
    // Subscribed before the guard below: a line composed while the clock still reads zero has to
    // hear the first tick, and only a reader of the state is recomposed when it arrives.
    val position by audio.position.collectAsStateWithLifecycle()
    if (durationMs <= 0L) return
    val fraction = (position.toFloat() / durationMs).coerceIn(0f, 1f)
    ProgressLine(
        fraction,
        if (audio.playing) SidekitTheme.colors.emphasisA1 else SidekitTheme.colors.surfaceA6,
        modifier
    )
}

/**
 * The line itself, shared by everything that draws one along a row's lower edge: the item playing
 * now and a podcast episode's resume point. Both sit under the same [ItemCornerRadius] tile, so
 * both take the same [RowProgressInset] and let the corner arc swallow their ends.
 */
@Composable
internal fun ProgressLine(fraction: Float, fillColor: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(RowProgressHeight)
            // Not the radius: only the top of the line has to clear the arc. At this inset the
            // arc meets the line's upper edge, so the curve, not a square corner, is where the
            // fill starts and stops.
            .padding(horizontal = RowProgressInset)
            .testTag(ROW_PROGRESS_TAG)
    ) {
        Box(Modifier.fillMaxSize().background(SidekitTheme.colors.surfaceA5))
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .testTag(ROW_PROGRESS_FILL_TAG)
                    .background(fillColor)
            )
        }
    }
}
