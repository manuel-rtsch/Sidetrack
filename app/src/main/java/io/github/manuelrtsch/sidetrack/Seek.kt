package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.theme.SidekitTheme
import io.github.manuelrtsch.sidetrack.playback.formatDuration
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Wait for key repeat to pause before asking the native player to seek. */
private const val KEY_SEEK_DEBOUNCE_MS = 300L

/**
 * Mirrors Music's seek row so the transport feels the same in both apps.
 */
@Composable
fun Seek(
    position: State<Long>,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    buffering: Boolean = false
) {
    val seekable = durationMs > 0
    // Seek once, on release: a seek per drag delta floods the native player and makes the bar take
    // seconds to settle on the final position.
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    // Key repeat sends KeyDown over and over; the target accumulates locally so holding left or
    // right keeps stepping instead of repeatedly seeking to the same one-step-ahead offset.
    var keyTargetMs by remember { mutableStateOf<Long?>(null) }
    var keySeekPending by remember { mutableStateOf(false) }
    var keySeekJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    // Slide mode: left/right nudges the track instead of moving focus; losing focus leaves the mode.
    var sliding by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    // Reading the 5 Hz clock tick in composition would recompose the whole row and its semantics on
    // every sample; the draw lambda reads it raw so a new sample only redraws, while everything
    // composed reads a derived value that changes once a second.
    val secondMs by remember(position) { derivedStateOf { position.value / 1_000L * 1_000L } }
    val shownPositionMs = when {
        dragFraction != null -> (dragFraction!! * durationMs).toLong()
        keyTargetMs != null -> keyTargetMs!!
        else -> secondMs
    }
    val shownFraction = if (seekable) (shownPositionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val trackColor = SidekitTheme.colors.surfaceA4
    val progressColor = SidekitTheme.colors.light
    // The knob turns accent-coloured while the bar owns the keypad, so slide mode is visible.
    val knobColor = if (sliding) SidekitTheme.colors.emphasisA1 else progressColor
    // Slide mode's taller strip takes its extra height from the artwork, fine while scrubbing.
    val blockHeight = if (sliding) 64.dp else 48.dp
    val stripHeight = if (sliding) 40.dp else 24.dp
    val barInset = if (sliding) 16.dp else 0.dp
    // Times sit outside the bar's row so the track can span the whole body without making the
    // screen taller; tabular digits keep the counting label from jittering.
    val labelStyle = SidekitTheme.typography.caption
    val timeStyle = remember(labelStyle) { labelStyle.copy(fontFeatureSettings = "tnum") }
    val interaction = remember { MutableInteractionSource() }
    fun seekTo(target: Float) {
        if (seekable) onSeek((target.coerceIn(0f, 1f) * durationMs).toLong())
    }

    // Repeated presses build on the pending target, not a position the UI has not recomposed yet;
    // the player is only asked once the key has been quiet, since seeking on every repeat drained
    // the audio buffer and left the screen silent while it caught up.
    fun nudge(direction: Float) {
        sliding = true
        if (!seekable) return
        // The clock itself, not the second the labels were rendered with: a step builds on where
        // the track actually is, so holding the key does not drift behind it.
        val target = ((keyTargetMs ?: position.value) + direction.toLong() * 10_000L)
            .coerceIn(0L, durationMs)
        keyTargetMs = target
        keySeekPending = true
        keySeekJob?.cancel()
        keySeekJob = scope.launch {
            delay(KEY_SEEK_DEBOUNCE_MS)
            if (keyTargetMs == target && keySeekPending) {
                keySeekPending = false
                onSeek(target)
            }
        }
    }

    fun finishKeySeek() {
        keySeekJob?.cancel()
        keySeekJob = null
        val target = keyTargetMs
        keyTargetMs = null
        if (target != null && keySeekPending) {
            keySeekPending = false
            onSeek(target)
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(blockHeight)
            .pointerInput(durationMs) {
                detectTapGestures { offset ->
                    focusRequester.requestFocus()
                    sliding = true
                    keySeekJob?.cancel()
                    keyTargetMs = null
                    keySeekPending = false
                    seekTo(offset.x / size.width)
                }
            }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        focusRequester.requestFocus()
                        sliding = true
                        keySeekJob?.cancel()
                        keyTargetMs = null
                        keySeekPending = false
                        if (seekable) dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                    },
                    onHorizontalDrag = { change, _ ->
                        if (seekable) dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        val target = dragFraction
                        dragFraction = null
                        if (target != null) seekTo(target)
                    },
                    onDragCancel = { dragFraction = null }
                )
            }
    ) {
        Row(
            // Labels sit close to the artist line while the bar keeps its distance; the 2 dp lift
            // stops the times crowding the track without shrinking the artwork.
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, if (sliding) (-8).dp.roundToPx() else (-4).dp.roundToPx()) }
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            SidekitText(
                if (buffering) "Buffering…" else formatDuration(shownPositionMs),
                style = timeStyle
            )
            SidekitText(formatDuration(durationMs), style = timeStyle)
        }
        // The focus ring belongs to the bar, not the labels above it. Keeping the focusable node
        // on this shorter bottom strip makes the ring hug the track and knob.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(stripHeight)
                .onKeyEvent { event ->
                    when (event.key) {
                        Key.DirectionLeft, Key.DirectionRight -> when (event.type) {
                            KeyEventType.KeyDown -> {
                                nudge(if (event.key == Key.DirectionLeft) -1f else 1f)
                                true
                            }

                            // Releasing the key commits the pending target immediately.
                            KeyEventType.KeyUp -> {
                                finishKeySeek()
                                true
                            }

                            else -> false
                        }

                        else -> false
                    }
                }
                .indication(interaction, LocalIndication.current)
                .focusRequester(focusRequester)
                .onFocusChanged {
                    sliding = it.isFocused
                    if (!it.isFocused) finishKeySeek()
                }
                .focusable(interactionSource = interaction)
                .semantics {
                    if (seekable) {
                        contentDescription = "Seek"
                        // A buffering bar says so in its state, not just in the label above it.
                        stateDescription = if (buffering) {
                            "Buffering"
                        } else {
                            "${formatDuration(shownPositionMs)} of ${formatDuration(durationMs)}"
                        }
                        progressBarRangeInfo = ProgressBarRangeInfo(shownFraction, 0f..1f)
                        setProgress { target ->
                            seekTo(target)
                            true
                        }
                    }
                }
                .drawBehind {
                    // The live fraction, read here rather than in composition: dragging and
                    // stepping use their own local targets, everything else follows the clock.
                    fun fractionOf(ms: Long) = (ms.toFloat() / durationMs).coerceIn(0f, 1f)
                    val barFraction = when {
                        dragFraction != null -> dragFraction!!
                        keyTargetMs != null && seekable -> fractionOf(keyTargetMs!!)
                        seekable -> fractionOf(position.value)
                        else -> 0f
                    }
                    val h = 6.dp.toPx()
                    val knob = 9.dp.toPx()
                    // The focus ring stays full width; only the track and knob pull in while
                    // focused so they do not collide with the ring.
                    val inset = barInset.toPx()
                    val trackWidth = size.width - inset * 2
                    // Centre the track and knob in the focus strip so the ring has equal air
                    // above and below them.
                    val y = (size.height - h) / 2
                    drawRoundRect(trackColor, Offset(inset, y), Size(trackWidth, h), CornerRadius(h / 2))
                    if (barFraction > 0f) {
                        val filled = inset + trackWidth * barFraction
                        drawRoundRect(
                            progressColor,
                            Offset(inset, y),
                            Size(trackWidth * barFraction, h),
                            CornerRadius(h / 2)
                        )
                        drawCircle(knobColor, knob, Offset(filled, y + h / 2))
                    }
                }
        )
    }
}
