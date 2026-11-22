package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hrln.interactive.sidekit.design.theme.SidekitTheme

/**
 * Whether track, album, playlist and the other media rows use the denser layout.
 *
 * Provided once above the navigation graph so a list does not re-read the preference per row.
 * Off by default: the four-row layout is the design the app ships with, and the dense one buys a
 * fifth row at the cost of smaller artwork and type.
 */
val LocalCompactRows = compositionLocalOf { false }

/**
 * The list item's corner radius. Mirrors `SidekitTheme.shapes.item`, which is 36 dp but is a shape
 * rather than a number, so the artwork radius below needs the value locally.
 */
private val ItemCornerRadius = 36.dp

/**
 * The cover's corner radius when it sits [inset] inside a list item: the item radius minus that
 * inset. Only concentric when the inset is the same on every side, which is why the rows now keep
 * their artwork inset equal to the vertical one: 80 on 104 leaves 12 all round (radius 24), and
 * the compact 64 on 82 leaves 9 (radius 27).
 */
fun artworkShapeFor(inset: Dp): RoundedCornerShape = RoundedCornerShape((ItemCornerRadius - inset).coerceAtLeast(0.dp))

/** The dense layout shrinks a row by about a fifth: 104 dp rows become 82 dp. */
private val CompactRowHeight = 82.dp
private val CompactArtworkSize = 64.dp

/**
 * The artwork's margin inside a row, and so the row's content inset. Equal on every side, so the
 * cover's radius is exactly the item's 36 minus the gap: 80 on 104 leaves 12 all round (radius
 * 24), the compact 64 on 82 leaves 9 (radius 27).
 */
internal val ListItemContentInset = 12.dp
private val CompactItemContentInset = 9.dp
private val CompactContentGap = 10.dp
private val CompactTextGap = 1.dp
private val CompactTrackNumberWidth = 40.dp

/**
 * The measured sizes of a media row. Both branches name every value, so a row reads one object
 * instead of deciding each dimension for itself and drifting from the list's pitch.
 */
@Immutable
data class MediaRowMetrics(
    val height: Dp,
    val artworkSize: Dp,
    /** Concentric with the item's own corner, unlike the fixed 24 dp `shapes.artwork` token. */
    val artworkShape: RoundedCornerShape,
    val horizontalPadding: Dp,
    val contentGap: Dp,
    val textGap: Dp,
    val trackNumberWidth: Dp,
    val titleStyle: TextStyle,
    val secondaryStyle: TextStyle,
    val trackNumberStyle: TextStyle
)

/** The metrics for the rows currently on screen, following the compact-rows setting. */
@Composable
fun rememberMediaRowMetrics(): MediaRowMetrics {
    val compact = LocalCompactRows.current
    val typography = SidekitTheme.typography
    val spacing = SidekitTheme.spacing
    return remember(compact, typography, spacing) {
        if (compact) {
            MediaRowMetrics(
                height = CompactRowHeight,
                artworkSize = CompactArtworkSize,
                artworkShape = artworkShapeFor(CompactItemContentInset),
                horizontalPadding = CompactItemContentInset,
                contentGap = CompactContentGap,
                textGap = CompactTextGap,
                trackNumberWidth = CompactTrackNumberWidth,
                // 32 -> 27 and 28 -> 24: the two-line block still reads as a title over a
                // subtitle inside the shorter row without dropping to the tiny label size.
                titleStyle = typography.bodySmall.copy(fontSize = 27.sp, lineHeight = 33.sp),
                secondaryStyle = typography.small,
                trackNumberStyle = typography.tiny
            )
        } else {
            MediaRowMetrics(
                height = spacing.itemHeight,
                artworkSize = ListArtworkSize,
                artworkShape = artworkShapeFor(ListItemContentInset),
                horizontalPadding = ListItemContentInset,
                contentGap = 13.dp,
                textGap = 2.dp,
                trackNumberWidth = 44.dp,
                titleStyle = typography.bodySmall,
                secondaryStyle = typography.caption,
                trackNumberStyle = typography.small
            )
        }
    }
}

/** The vertical distance between two rows, used wherever layout is measured in row pitches. */
@Composable
fun rememberMediaRowPitch(): Dp = rememberMediaRowMetrics().height + SidekitTheme.spacing.itemOuterGap

/** Reads the saved compact-rows choice and observes later changes to it. */
@Composable
fun rememberCompactRows(): Boolean {
    val context = LocalContext.current.applicationContext
    val settings = remember(context) { SidetrackLocalSettings(context) }
    var compact by remember(settings) { mutableStateOf(settings.compactRows) }
    DisposableEffect(settings) {
        val stop = settings.observeCompactRows { compact = settings.compactRows }
        onDispose(stop)
    }
    return compact
}
