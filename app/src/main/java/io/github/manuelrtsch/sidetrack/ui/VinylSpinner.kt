package io.github.manuelrtsch.sidetrack.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode as AnimationRepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.theme.SidekitTheme

/** One turn of the record. Fast enough to read as work in flight, slow enough to follow. */
private const val SPIN_CYCLE_MS = 800

/** The arc sits in the groove band, between the centre dot and the outer ring. */
private const val ARC_RADIUS = 0.56f

/** Part circle: a little over a quarter, so the eye can see it move and see it come round. */
private const val ARC_SWEEP = 100f

/**
 * The launcher icon's record, cut down to a trailing indicator for a row that is fetching.
 *
 * The icon's rings and grooves are too fine to survive at row size, so the figure keeps three marks
 * that still read there: the outer ring, the centre label as a dot, and one short arc in the groove
 * band turning round it. Everything that does not move is dimmed, which leaves the arc as the thing
 * the eye follows.
 *
 * The cost is kept to the draw phase, as the playing bars over the artwork do it: one 0..1 phase is
 * read inside the draw lambda, so a frame invalidates the drawing and nothing else — no
 * recomposition, no relayout.
 */
@Composable
fun VinylSpinner(
    /** What a screen reader hears in place of the movement. */
    describeAs: String,
    modifier: Modifier = Modifier,
    /** The row arrow's size by default: the spinner takes that slot over while a page loads. */
    diameter: Dp = RowLinkArrowSize,
    color: Color = SidekitTheme.colors.light
) {
    val transition = rememberInfiniteTransition(label = "vinylSpinner")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SPIN_CYCLE_MS, easing = LinearEasing),
            repeatMode = AnimationRepeatMode.Restart
        ),
        label = "recordPhase"
    )
    Canvas(modifier.size(diameter).semantics { contentDescription = describeAs }) {
        val radius = size.minDimension / 2f
        val centre = Offset(size.width / 2f, size.height / 2f)
        // The still marks stay dim so the arc is the thing the eye follows, and the ring is drawn
        // inside the bounds so the row's spacing stays honest at any size the spinner is given.
        val rim = radius * 0.10f
        drawCircle(color.copy(alpha = 0.40f), radius - rim / 2f, centre, style = Stroke(width = rim))
        drawCircle(color.copy(alpha = 0.50f), radius * 0.19f, centre)
        val arcRadius = radius * ARC_RADIUS
        drawArc(
            color = color,
            // Start at twelve o'clock and go clockwise, the direction a record turns.
            startAngle = phase * 360f - 90f,
            sweepAngle = ARC_SWEEP,
            useCenter = false,
            topLeft = Offset(centre.x - arcRadius, centre.y - arcRadius),
            size = Size(arcRadius * 2f, arcRadius * 2f),
            style = Stroke(width = radius * 0.21f, cap = StrokeCap.Round)
        )
    }
}
