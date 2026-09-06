package eu.darken.butler.searcher.ui.search.items

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.searcher.core.SearcherViewStyle

val SearcherViewStyle.Density.rowIconSize: Dp
    get() = when (this) {
        SearcherViewStyle.Density.COMPACT -> 24.dp
        SearcherViewStyle.Density.COMFORTABLE -> 40.dp
        SearcherViewStyle.Density.DETAILED -> 48.dp
    }

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
        SearcherViewStyle.Density.COMPACT -> 4.dp
        SearcherViewStyle.Density.COMFORTABLE -> 8.dp
        SearcherViewStyle.Density.DETAILED -> 12.dp
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

/** A compact tile carries the name alone; the metadata overlays need room to read as text. */
val SearcherViewStyle.Density.showsTileMetadata: Boolean
    get() = this != SearcherViewStyle.Density.COMPACT

val SearcherViewStyle.Density.gridPrimaryMaxLines: Int
    get() = when (this) {
        SearcherViewStyle.Density.COMPACT -> 1
        SearcherViewStyle.Density.COMFORTABLE -> 1
        SearcherViewStyle.Density.DETAILED -> 2
    }
