package eu.darken.butler.searcher.ui.search.items

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.searcher.core.SearcherViewStyle

val SearcherViewStyle.Density.rowIconSize: Dp
    get() = when (this) {
        SearcherViewStyle.Density.COMPACT -> 24.dp
        SearcherViewStyle.Density.COMFORTABLE -> 32.dp
        SearcherViewStyle.Density.DETAILED -> 48.dp
    }

val SearcherViewStyle.Density.rowPadding: Dp
    get() = when (this) {
        SearcherViewStyle.Density.COMPACT -> 6.dp
        SearcherViewStyle.Density.COMFORTABLE -> 8.dp
        SearcherViewStyle.Density.DETAILED -> 10.dp
    }

/** Only the vertical inset tightens at compact; the horizontal one keeps rows aligned. */
val SearcherViewStyle.Density.rowVerticalPadding: Dp
    get() = when (this) {
        SearcherViewStyle.Density.COMPACT -> 2.dp
        SearcherViewStyle.Density.COMFORTABLE -> 8.dp
        SearcherViewStyle.Density.DETAILED -> 10.dp
    }

/** Gap between the leading icon and the text column, scaled with the icon it sits beside. */
val SearcherViewStyle.Density.rowIconGap: Dp
    get() = when (this) {
        SearcherViewStyle.Density.COMPACT -> 8.dp
        SearcherViewStyle.Density.COMFORTABLE -> 12.dp
        SearcherViewStyle.Density.DETAILED -> 16.dp
    }

/**
 * `1,23 MB` against `1,2 MB`: the short form loses a digit, which is the point at compact.
 */
val SearcherViewStyle.Density.usesShortFileSize: Boolean
    get() = this == SearcherViewStyle.Density.COMPACT

/**
 * Detailed gives the parent path the whole second line and moves size and date to a third, so a
 * deep path stops competing with them for width.
 */
val SearcherViewStyle.Density.showsMetadataOnOwnLine: Boolean
    get() = this == SearcherViewStyle.Density.DETAILED

/** Length the match excerpt is cut to before it is rendered. */
val SearcherViewStyle.Density.matchLineLength: Int
    get() = when (this) {
        SearcherViewStyle.Density.COMPACT -> 60
        SearcherViewStyle.Density.COMFORTABLE -> 60
        SearcherViewStyle.Density.DETAILED -> 140
    }

val SearcherViewStyle.Density.matchLineMaxLines: Int
    get() = when (this) {
        SearcherViewStyle.Density.COMPACT -> 1
        SearcherViewStyle.Density.COMFORTABLE -> 1
        SearcherViewStyle.Density.DETAILED -> 2
    }

val SearcherViewStyle.Density.listGap: Dp
    get() = when (this) {
        SearcherViewStyle.Density.COMPACT -> 2.dp
        SearcherViewStyle.Density.COMFORTABLE -> 4.dp
        SearcherViewStyle.Density.DETAILED -> 8.dp
    }

/**
 * Tile width threshold. `GridCells.Adaptive` only sets a column count, so two steps can land on the
 * same count at some pane widths; the tiles vary their content per step as well.
 */
val SearcherViewStyle.Density.gridMinSize: Dp
    get() = when (this) {
        SearcherViewStyle.Density.COMPACT -> 90.dp
        SearcherViewStyle.Density.COMFORTABLE -> 120.dp
        SearcherViewStyle.Density.DETAILED -> 160.dp
    }

val SearcherViewStyle.Density.gridIconSize: Dp
    get() = when (this) {
        SearcherViewStyle.Density.COMPACT -> 16.dp
        SearcherViewStyle.Density.COMFORTABLE -> 20.dp
        SearcherViewStyle.Density.DETAILED -> 24.dp
    }

/** A compact tile carries the name alone; the metadata overlays need room to read as text. */
val SearcherViewStyle.Density.showsTileMetadata: Boolean
    get() = this != SearcherViewStyle.Density.COMPACT

val SearcherViewStyle.Density.gridPrimaryMaxLines: Int
    get() = when (this) {
        SearcherViewStyle.Density.COMPACT -> 1
        SearcherViewStyle.Density.COMFORTABLE -> 1
        SearcherViewStyle.Density.DETAILED -> 2
    }
