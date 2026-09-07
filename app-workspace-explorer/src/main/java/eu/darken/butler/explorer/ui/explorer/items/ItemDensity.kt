package eu.darken.butler.explorer.ui.explorer.items

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.DateTimeStyle
import eu.darken.butler.explorer.core.ExplorerViewStyle

/**
 * Sizes the leading icon area of a row.
 *
 * Both the base's slot and every leaf's own image read this: the slot centers its child without
 * scaling it, so sizing only the slot would add empty space around an unchanged icon.
 */
val ExplorerViewStyle.Density.rowIconSize: Dp
    get() = when (this) {
        ExplorerViewStyle.Density.COMPACT -> 24.dp
        ExplorerViewStyle.Density.COMFORTABLE -> 32.dp
        ExplorerViewStyle.Density.DETAILED -> 48.dp
    }

val ExplorerViewStyle.Density.rowBadgeSize: Dp
    get() = when (this) {
        ExplorerViewStyle.Density.COMPACT -> 12.dp
        ExplorerViewStyle.Density.COMFORTABLE -> 14.dp
        ExplorerViewStyle.Density.DETAILED -> 18.dp
    }

val ExplorerViewStyle.Density.rowPadding: Dp
    get() = when (this) {
        ExplorerViewStyle.Density.COMPACT -> 6.dp
        ExplorerViewStyle.Density.COMFORTABLE -> 8.dp
        ExplorerViewStyle.Density.DETAILED -> 10.dp
    }

/** Only the vertical inset tightens at compact; the horizontal one keeps rows aligned. */
val ExplorerViewStyle.Density.rowVerticalPadding: Dp
    get() = when (this) {
        ExplorerViewStyle.Density.COMPACT -> 2.dp
        ExplorerViewStyle.Density.COMFORTABLE -> 8.dp
        ExplorerViewStyle.Density.DETAILED -> 10.dp
    }

/** Gap between the leading icon and the text column, scaled with the icon it sits beside. */
val ExplorerViewStyle.Density.rowIconGap: Dp
    get() = when (this) {
        ExplorerViewStyle.Density.COMPACT -> 8.dp
        ExplorerViewStyle.Density.COMFORTABLE -> 12.dp
        ExplorerViewStyle.Density.DETAILED -> 16.dp
    }

val ExplorerViewStyle.Density.listGap: Dp
    get() = when (this) {
        ExplorerViewStyle.Density.COMPACT -> 2.dp
        ExplorerViewStyle.Density.COMFORTABLE -> 4.dp
        ExplorerViewStyle.Density.DETAILED -> 8.dp
    }

/** Permissions and ownership; only detailed spends the width on them. */
val ExplorerViewStyle.Density.showsFileAttributes: Boolean
    get() = this == ExplorerViewStyle.Density.DETAILED

/**
 * `1,23 MB` against `1,2 MB`: the short form loses a digit, which is the point at compact.
 */
val ExplorerViewStyle.Density.usesShortFileSize: Boolean
    get() = this == ExplorerViewStyle.Density.COMPACT

/** Compact and comfortable put the modification date on the second line; detailed moves it down. */
val ExplorerViewStyle.Density.showsDatesOnOwnLine: Boolean
    get() = this == ExplorerViewStyle.Density.DETAILED

val ExplorerViewStyle.Density.rowDateStyle: DateTimeStyle
    get() = when (this) {
        ExplorerViewStyle.Density.COMPACT -> DateTimeStyle.COMPACT
        ExplorerViewStyle.Density.COMFORTABLE -> DateTimeStyle.FULL
        ExplorerViewStyle.Density.DETAILED -> DateTimeStyle.FULL
    }

val ExplorerViewStyle.Density.gridIconSize: Dp
    get() = when (this) {
        ExplorerViewStyle.Density.COMPACT -> 16.dp
        ExplorerViewStyle.Density.COMFORTABLE -> 20.dp
        ExplorerViewStyle.Density.DETAILED -> 24.dp
    }

val ExplorerViewStyle.Density.gridDateStyle: DateTimeStyle
    get() = when (this) {
        ExplorerViewStyle.Density.COMPACT -> DateTimeStyle.COMPACT
        ExplorerViewStyle.Density.COMFORTABLE -> DateTimeStyle.COMPACT
        ExplorerViewStyle.Density.DETAILED -> DateTimeStyle.FULL
    }

/**
 * Folder item counts, tile subtitles and timestamps. The file size is NOT covered: it is the one
 * figure a compact tile still carries, in its short form.
 */
val ExplorerViewStyle.Density.showsTileMetadata: Boolean
    get() = this != ExplorerViewStyle.Density.COMPACT

val ExplorerViewStyle.Density.gridBadgeSize: Dp
    get() = when (this) {
        ExplorerViewStyle.Density.COMPACT -> 8.dp
        ExplorerViewStyle.Density.COMFORTABLE -> 10.dp
        ExplorerViewStyle.Density.DETAILED -> 12.dp
    }

val ExplorerViewStyle.Density.gridPrimaryMaxLines: Int
    get() = when (this) {
        ExplorerViewStyle.Density.COMPACT -> 1
        ExplorerViewStyle.Density.COMFORTABLE -> 1
        ExplorerViewStyle.Density.DETAILED -> 2
    }

/**
 * Tile width threshold. `GridCells.Adaptive` only sets a column count, so two steps can land on the
 * same count at some pane widths; the tiles vary their content per step as well.
 */
val ExplorerViewStyle.Density.gridMinSize: Dp
    get() = when (this) {
        ExplorerViewStyle.Density.COMPACT -> 90.dp
        ExplorerViewStyle.Density.COMFORTABLE -> 120.dp
        ExplorerViewStyle.Density.DETAILED -> 160.dp
    }
