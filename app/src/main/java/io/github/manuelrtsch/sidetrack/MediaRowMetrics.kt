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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hrln.interactive.sidekit.design.theme.SidekitTheme
import kotlin.math.roundToInt

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
 * rather than a number, so the artwork radius below needs the value locally, and [RowPlaybackLine]
 * derives where its ends are swallowed from it.
 */
internal val ItemCornerRadius = 36.dp

/**
 * The cover's corner radius when it sits [inset] inside a list item: the item radius minus that
 * inset. Only concentric when the inset is the same on every side, which is why the rows keep
 * their artwork inset equal to the vertical one: 80 on 100 leaves 10 all round (radius 26), and
 * the compact 64 on 80 leaves 8 (radius 28).
 */
fun artworkShapeFor(inset: Dp): RoundedCornerShape = RoundedCornerShape((ItemCornerRadius - inset).coerceAtLeast(0.dp))

/**
 * A spacious row is 100 dp, not the 104 dp `SidekitTheme.spacing.itemHeight`, because a row's
 * height is a budget question on the fixed 480 x 640 canvas. The body between the header and the
 * action bar is 435 dp in the default compact chrome (640 - 10 pagePadding - 65 header - 10
 * pageGap - 120 bar, gap and bottom padding), and Sidekit's lists space rows by 3 dp between and
 * not after. Sidekit's own 104 dp rows filled the older 425 dp body exactly (4 x 104 + 3 x 3),
 * which read as a list that ended at the fold instead of one that scrolls.
 *
 * The rule the rows are solved for: four whole rows plus a quarter of the fifth, so the cover's top
 * edge says "there is more" after four rows a screen. With n whole rows and f of the next one,
 * `body = n (h + 3) + f h`, so `h = (body - 3n) / (n + f)`:
 *
 * - spacious: `(435 - 12) / 4.25 = 99.53`, therefore 100 dp: 4 x 103 = 412 and 23 dp of the fifth
 *   row peeking, a shade under the quarter;
 * - compact: `(435 - 15) / 5.25 = 80` exactly: 5 x 83 = 415 and 20 dp of the sixth peeking.
 *
 * Both heights are whole dp on purpose. The canvas pins 1 dp to 1 pixel (`SidekitCanvas`), so a
 * 99.5 dp row does not exist: it lays out at 100 and would only have hidden the fact that the
 * quarter is not reachable with whole pixels. 23 of 100 is as close as the budget allows.
 *
 * Re-derive both numbers if the chrome changes again: they assume the compact chrome's 65 dp
 * header. Sidekit's token stays at 104 for every other app and for the tile band on Home.
 *
 * Nothing horizontal follows this scale: the library and search category squares are bound by the
 * page width and keep one size in both modes (see [categoryCell]).
 */
private val SpaciousRowHeight = 100.dp
private val SpaciousArtworkSize = 80.dp

/**
 * The dense layout buys the fifth row outright: 80 dp rows on an 83 dp pitch show five rows plus a
 * quarter of the sixth (435 = 5 x 83 + 20).
 */
private val CompactRowHeight = 80.dp
private val CompactArtworkSize = 64.dp

/**
 * The artwork's margin inside a row, and so the row's content inset. Equal on every side, so the
 * cover's radius is exactly the item's 36 minus the gap: 80 on 100 leaves 10 all round (radius
 * 26), the compact 64 on 80 leaves 8 (radius 28).
 */
internal val ListItemContentInset = 10.dp
private val CompactItemContentInset = 8.dp
private val CompactContentGap = 10.dp
private val CompactTextGap = 0.dp

/**
 * The gap between the text block and the duration. Smaller than [contentGap] because the duration
 * is right-aligned and needs no matching inset; 8 dp still keeps the two from touching.
 */
private val TrailingGap = 8.dp

/**
 * Wide enough for two tiny digits (26 dp) with a little air either side. Three digits drop to
 * [MediaRowMetrics.trackNumberDenseStyle] at 18 sp so 100+ still fits in the same column.
 */
private val TrackNumberWidth = 30.dp

/**
 * How much of the cover's width a badge takes: [PlaybackChip] and the explicit chip are the same
 * size, and at two-fifths of the artwork both the "E" and the three bars are readable at arm's
 * length instead of being a texture on the corner. 80 dp gives 32 dp, the compact 64 gives 26
 * after rounding: the canvas pins 1 dp to 1 px, so a fractional 25.6 would only be rounded by the
 * layout anyway.
 */
private const val BADGE_WIDTH_FRACTION = 0.4f

/** [artwork]'s two-fifths, as a whole dp, so the chip lands on the pixel the canvas draws. */
private fun badgeSize(artwork: Dp): Dp = (artwork.value * BADGE_WIDTH_FRACTION).roundToInt().dp

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
    val trailingGap: Dp,
    val textGap: Dp,
    val trackNumberWidth: Dp,
    val titleStyle: TextStyle,
    val secondaryStyle: TextStyle,
    /**
     * One step below [secondaryStyle]: the duration is a reference value, not part of the
     * title/artist pair, and the smaller type buys the title a little width without moving it.
     * Tabular figures keep the shortened values aligned down the list.
     */
    val trailingStyle: TextStyle,
    val trackNumberStyle: TextStyle,
    /** For a three-digit index, which would otherwise overflow the narrowed number column. */
    val trackNumberDenseStyle: TextStyle,
    /** The explicit chip's size, and [PlaybackChip]'s: [BADGE_WIDTH_FRACTION] of [artworkSize]. */
    val explicitBadgeSize: Dp,
    val explicitBadgeStyle: TextStyle
)

/** The metrics for the rows currently on screen, following the compact-rows setting. */
@Composable
fun rememberMediaRowMetrics(): MediaRowMetrics {
    val compact = LocalCompactRows.current
    val typography = SidekitTheme.typography
    // A row's height is its own budget (see [SpaciousRowHeight]) rather than a spacing token, so
    // the metrics no longer follow [SidekitTheme.spacing] and do not need to re-derive on it.
    return remember(compact, typography) {
        if (compact) {
            MediaRowMetrics(
                height = CompactRowHeight,
                artworkSize = CompactArtworkSize,
                artworkShape = artworkShapeFor(CompactItemContentInset),
                horizontalPadding = CompactItemContentInset,
                contentGap = CompactContentGap,
                trailingGap = TrailingGap,
                textGap = CompactTextGap,
                trackNumberWidth = TrackNumberWidth,
                // 32 -> 27 and 28 -> 24: the two-line block still reads as a title over a
                // subtitle inside the shorter row without dropping to the tiny label size. The
                // 31 leading and the zero gap are the same tightening the spacious row got, and
                // keep the block at 60 so the 64 cover stays the taller child.
                titleStyle = typography.bodySmall.copy(fontSize = 27.sp, lineHeight = 31.sp),
                secondaryStyle = typography.small,
                trailingStyle = typography.tiny.copy(fontFeatureSettings = "tnum"),
                trackNumberStyle = typography.tiny,
                trackNumberDenseStyle = typography.tiny.copy(fontSize = 18.sp, lineHeight = 22.sp),
                explicitBadgeSize = badgeSize(CompactArtworkSize),
                explicitBadgeStyle = typography.tiny.copy(
                    fontSize = 18.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Medium
                )
            )
        } else {
            MediaRowMetrics(
                height = SpaciousRowHeight,
                artworkSize = SpaciousArtworkSize,
                artworkShape = artworkShapeFor(ListItemContentInset),
                horizontalPadding = ListItemContentInset,
                contentGap = 13.dp,
                trailingGap = TrailingGap,
                textGap = 1.dp,
                trackNumberWidth = TrackNumberWidth,
                // Colour, weight and size already separate the two lines, so their leading gives
                // back the 3 dp each the shorter row needs: 37 + 1 + 32 = 70 against the 77 cover.
                titleStyle = typography.bodySmall.copy(lineHeight = 37.sp),
                secondaryStyle = typography.caption.copy(lineHeight = 32.sp),
                trailingStyle = typography.small.copy(fontFeatureSettings = "tnum"),
                trackNumberStyle = typography.tiny,
                trackNumberDenseStyle = typography.tiny.copy(fontSize = 18.sp, lineHeight = 22.sp),
                explicitBadgeSize = badgeSize(SpaciousArtworkSize),
                explicitBadgeStyle = typography.tiny.copy(
                    fontSize = 22.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.Medium
                )
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
