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

val ExplorerViewStyle.Density.listGap: Dp
    get() = when (this) {
        ExplorerViewStyle.Density.COMPACT -> 2.dp
        ExplorerViewStyle.Density.COMFORTABLE -> 4.dp
        ExplorerViewStyle.Density.DETAILED -> 8.dp
    }

/** Permissions and ownership; compact keeps the secondary line to size or item count alone. */
val ExplorerViewStyle.Density.showsFileAttributes: Boolean
    get() = this != ExplorerViewStyle.Density.COMPACT

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

/** A compact tile carries the name alone; the metadata overlays need room to read as text. */
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
