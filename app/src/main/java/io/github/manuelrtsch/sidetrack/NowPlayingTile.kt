package io.github.manuelrtsch.sidetrack

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode as AnimationRepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hrln.interactive.sidekit.design.action.ActionMotion
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitIconImage
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.SidekitTile
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.design.theme.contentColorFor
import io.github.manuelrtsch.sidetrack.playback.SleepTimerState
import io.github.manuelrtsch.sidetrack.playback.tileText
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.flow.StateFlow

@Composable
fun NowPlayingTile(
    current: MediaItem?,
    isPlaying: Boolean,
    sleepTimer: StateFlow<SleepTimerState>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = SidekitTheme.colors
    val timer by sleepTimer.collectAsStateWithLifecycle()
    val timerLabel = timer.tileText()
    val countdown = timer as? SleepTimerState.Countdown
    // One overline slot, ranked: the sleep timer's outcome explains the pause, a frozen or live
    // timer replaces the playing label, and the icon beside the text carries playback state so the
    // text itself can stay inside the tile's one-line budget.
    val paused = current != null && !isPlaying
    val overlineIcon: SidekitIcon?
    val overlineLabel: String
    when {
        current == null -> {
            overlineIcon = null
            overlineLabel = "NOTHING PLAYING"
        }

        timer == SleepTimerState.Finished && timerLabel != null -> {
            overlineIcon = SidekitIcon.Bedtime
            overlineLabel = timerLabel
        }

        paused && countdown != null -> {
            // The pause icon already says "PAUSED", so the text is the timer itself: at 11
            // characters it sits well inside the slot that "NOTHING PLAYING" sets.
            overlineIcon = SidekitIcon.Pause
            overlineLabel = "${countdown.remainingMinutes} MIN LEFT"
        }

        paused && timerLabel != null -> {
            // Same label as a playing end-of-track timer; only the icon differs.
            overlineIcon = SidekitIcon.Pause
            overlineLabel = timerLabel
        }

        paused -> {
            overlineIcon = SidekitIcon.Pause
            overlineLabel = "PAUSED"
        }

        timerLabel != null -> {
            overlineIcon = SidekitIcon.Bedtime
            overlineLabel = timerLabel
        }

        else -> {
            overlineIcon = null
            overlineLabel = "NOW PLAYING"
        }
    }
    SidekitTile(modifier, onClick = onClick, onClickLabel = "Now playing") {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = ListItemContentInset),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            if (current == null) {
                Box(Modifier.size(ListArtworkSize).clip(artworkShapeFor(ListItemContentInset)).background(colors.surfaceA2), contentAlignment = Alignment.Center) {
                    SidekitIconImage(SidekitIcon.MusicNote2, size = 36.dp)
                }
            } else {
                Cover(current.artRes, Modifier.size(ListArtworkSize), shape = artworkShapeFor(ListItemContentInset), artUrl = current.artUrl, requestSize = ListArtworkSize)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                // The overline carries the state, so the title keeps the full width and never
                // competes with a trailing label.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    overlineIcon?.let {
                        SidekitIconImage(it, size = 24.dp, tint = colors.surfaceA6)
                    }
                    SidekitText(
                        overlineLabel,
                        style = SidekitTheme.typography.small,
                        color = colors.surfaceA6,
                        maxLines = 1
                    )
                }
                SidekitText(
                    current?.title ?: "Pick a track",
                    style = SidekitTheme.typography.bodySmall,
                    maxLines = 2
                )
            }
        }
    }
}

/**
 * The transient "now playing" confirmation. It replaces the action bar for a few seconds after a
 * new item starts, then the normal actions return. Tapping it opens Now Playing; the square X
 * dismisses it immediately.
 *
 * The Options key is deliberately not a dismiss: Sidekit's `ChromeState.toggleOptions()` owns that
 * key and opens the screen's options instead. A later Sidekit improvement can make Options dismiss
 * an auto-dismiss action before opening options.
 */
fun nowPlayingNoticeActions(
    item: MediaItem,
    onOpen: () -> Unit,
    onDismiss: () -> Unit
): List<SideAction> = listOf(
    SideAction(
        key = "nowPlayingNotice:${item.id}",
        // The title is drawn inside the art (see [NowPlayingNoticeContent]) rather than as the
        // bar's label. Sidekit centres an action's art and label as a pair, so a short title left
        // the cover floating 50 dp from the button's left edge; anchoring the row here puts the
        // cover 18 dp from the left, top and bottom instead. No trailing arrow either: it cost 50 dp
        // of a 334 dp row, and the accent surface, the dismiss square beside it and the content
        // description already say the button opens Now Playing.
        tone = ActionTone.Primary,
        motion = ActionMotion.Up,
        contentDescription = "Playing ${item.title}. Open Now Playing",
        artKey = item.id,
        art = { NowPlayingNoticeContent(item) },
        onClick = onOpen
    ),
    SideAction.square(
        key = "nowPlayingNoticeDismiss",
        icon = SidekitIcon.Close,
        contentDescription = "Dismiss now playing notice",
        onClick = onDismiss,
        tone = ActionTone.Muted,
        motion = ActionMotion.Up
    )
)

/** The notice's cover: 64 dp, so the compact 100 dp bar leaves 18 dp above and below. */
private val NoticeArtSize = 64.dp

/** Space between the cover and the title. */
private val NoticeGap = 10.dp

/** The Sidephone's logical bar width: the 480 dp panel minus the 10 dp page padding on either side. */
private val NoticeBarWidth = 460.dp

/** Sidekit measures action content against the slot minus this edge margin, then centres it. */
private val NoticeSlotEdge = 8.dp

/** Sidekit's gap between the notice and the close square. */
private val NoticeButtonGap = 10.dp

/**
 * A circle: at an 18 dp inset its curve follows the pill's rounded end, so the gap stays even from
 * the left cap round to the top and bottom edges. A rounded square would pinch at the corners.
 */
private val NoticeArtShape = RoundedCornerShape(NoticeArtSize / 2)

/**
 * The notice's whole row: cover, then the title, anchored to the button's left edge.
 *
 * Drawing the title here instead of passing `label` is what makes the spacing uniform. Sidekit
 * centres an art-and-label pair, so a short title left the cover floating far from the left edge.
 * This row fills the content slot instead, and its padding puts the cover the same distance from
 * the left, top and bottom: half the bar's spare height.
 *
 * The type rule stays Sidekit's: 36 sp while the title fits one row, 30 sp with a tighter leading
 * once it needs two, so the pair still fits the fixed bar.
 */
@Composable
private fun NowPlayingNoticeContent(item: MediaItem) {
    val colors = SidekitTheme.colors
    val foreground = colors.contentColorFor(ActionTone.Primary.color(colors))
    val typography = SidekitTheme.typography
    val spacing = SidekitTheme.spacing
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    // Half the bar's spare height is the only margin the cover can keep on all three sides.
    val margin = ((spacing.actionBarHeight - NoticeArtSize) / 2).coerceAtLeast(0.dp)
    // Sidekit's slot minus its edge margin, then the cover, its gap and an equal margin on the right.
    val contentWidth = NoticeBarWidth - spacing.actionBarHeight - NoticeButtonGap - NoticeSlotEdge * 2
    val inset = (margin - NoticeSlotEdge).coerceAtLeast(0.dp)
    val textWidth = contentWidth - inset * 2 - NoticeArtSize - NoticeGap
    val style = remember(item.title, textWidth, typography, density) {
        val oneRow = measurer.measure(
            text = item.title,
            style = typography.body,
            overflow = TextOverflow.Ellipsis,
            maxLines = 2,
            constraints = Constraints(maxWidth = with(density) { textWidth.roundToPx() })
        ).lineCount <= 1
        if (oneRow) typography.body else typography.label.copy(lineHeight = typography.label.fontSize * 1.15f)
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = inset),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NoticeGap)
    ) {
        Box(Modifier.size(NoticeArtSize)) {
            Cover(
                item.artRes,
                Modifier.fillMaxSize(),
                shape = NoticeArtShape,
                artUrl = item.artUrl,
                // The size is known: skip RemoteArtworkImage's BoxWithConstraints subcomposition and
                // ask the loader for exactly this square instead of whatever it would measure.
                requestSize = NoticeArtSize
            )
            PlayingBars(
                Modifier
                    .align(Alignment.BottomEnd)
                    // Bleeds just past the cover's bottom-right corner, over the button surface.
                    .offset(x = 4.dp, y = 4.dp)
                    .size(32.dp)
            )
        }
        SidekitText(
            item.title,
            modifier = Modifier.weight(1f).semantics { text = AnnotatedString(item.title) },
            style = style,
            color = foreground,
            maxLines = 2
        )
    }
}

/** One cycle of the equaliser: line up with the sine's period so the repeat does not jump. */
private const val BAR_CYCLE_MS = 1_100

/** Tallest bar, as a fraction of the circle. */
private const val BAR_HEIGHT = 0.52f

/** How far each bar trails the one before it, in cycles. */
private const val BAR_PHASE_STEP = 0.3f

private val BarPeaks = floatArrayOf(0.45f, 1f, 0.65f)
private val BarScrim = Color.Black.copy(alpha = 0.6f)
private val TwoPi = 2f * PI.toFloat()

/**
 * Three bars over the artwork, so the confirmation reads as playing rather than paused.
 *
 * One animation drives all three bars. A single 0..1 phase is read inside the draw lambda and each
 * bar's height is that phase shifted by its own offset, which keeps the whole picture a draw-phase
 * invalidation with one snapshot write per frame instead of three animation clocks chasing the same
 * effect. The sine's period matches the repeat, so the cycle wraps without a visible jump.
 */
@Composable
private fun PlayingBars(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "nowPlayingBars")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = BAR_CYCLE_MS, easing = LinearEasing),
            repeatMode = AnimationRepeatMode.Restart
        ),
        label = "barPhase"
    )
    Canvas(modifier) {
        drawCircle(BarScrim, radius = size.minDimension / 2f)
        val barWidth = size.width * 0.15f
        val gap = size.width * 0.09f
        val total = barWidth * BarPeaks.size + gap * (BarPeaks.size - 1)
        val startX = (size.width - total) / 2f
        val bottom = size.height * 0.72f
        BarPeaks.forEachIndexed { index, peak ->
            // 0.35..1 keeps every bar visible at the bottom of the wave, as the old reverse
            // animation did, so the row reads as a meter rather than three blinking ticks.
            val wave = 0.5f + 0.5f * sin(TwoPi * (phase + index * BAR_PHASE_STEP))
            val height = size.height * BAR_HEIGHT * peak * (0.35f + 0.65f * wave)
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(startX + index * (barWidth + gap), bottom - height),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f)
            )
        }
    }
}
