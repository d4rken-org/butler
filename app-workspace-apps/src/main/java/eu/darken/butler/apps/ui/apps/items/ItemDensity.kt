package eu.darken.butler.apps.ui.apps.items

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.workspace.contracts.apps.AppsViewStyle

val AppsViewStyle.Density.rowIconSize: Dp
    get() = when (this) {
        AppsViewStyle.Density.COMPACT -> 24.dp
        AppsViewStyle.Density.COMFORTABLE -> 40.dp
        AppsViewStyle.Density.DETAILED -> 56.dp
    }

val AppsViewStyle.Density.gridIconSize: Dp
    get() = when (this) {
        AppsViewStyle.Density.COMPACT -> 40.dp
        AppsViewStyle.Density.COMFORTABLE -> 56.dp
        AppsViewStyle.Density.DETAILED -> 64.dp
    }

val AppsViewStyle.Density.listGap: Dp
    get() = when (this) {
        AppsViewStyle.Density.COMPACT -> 2.dp
        AppsViewStyle.Density.COMFORTABLE -> 4.dp
        AppsViewStyle.Density.DETAILED -> 8.dp
    }

/**
 * Tile width threshold. `GridCells.Adaptive` only sets a column count, so two steps can land on the
 * same count at some pane widths; the tiles vary their content per step as well.
 */
val AppsViewStyle.Density.gridMinSize: Dp
    get() = when (this) {
        AppsViewStyle.Density.COMPACT -> 90.dp
        AppsViewStyle.Density.COMFORTABLE -> 120.dp
        AppsViewStyle.Density.DETAILED -> 160.dp
    }

/**
 * The package name, the version string and the size chip.
 *
 * Tags are NOT covered by this: they carry actionable state such as "Disabled", so they stay at
 * every density.
 */
val AppsViewStyle.Density.showsSecondaryMetadata: Boolean
    get() = this != AppsViewStyle.Density.COMPACT
