package eu.darken.butler.apps.ui.apps.items

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.workspace.contracts.apps.AppsViewStyle

val AppsViewStyle.Density.rowIconSize: Dp
    get() = when (this) {
        AppsViewStyle.Density.COMPACT -> 24.dp
        AppsViewStyle.Density.COMFORTABLE -> 32.dp
        AppsViewStyle.Density.DETAILED -> 48.dp
    }

val AppsViewStyle.Density.gridIconSize: Dp
    get() = when (this) {
        AppsViewStyle.Density.COMPACT -> 40.dp
        AppsViewStyle.Density.COMFORTABLE -> 56.dp
        AppsViewStyle.Density.DETAILED -> 64.dp
    }

/** Horizontal inset of a row; matches the Explorer listing so the two read as the same list. */
val AppsViewStyle.Density.rowPadding: Dp
    get() = when (this) {
        AppsViewStyle.Density.COMPACT -> 6.dp
        AppsViewStyle.Density.COMFORTABLE -> 8.dp
        AppsViewStyle.Density.DETAILED -> 10.dp
    }

val AppsViewStyle.Density.rowVerticalPadding: Dp
    get() = when (this) {
        AppsViewStyle.Density.COMPACT -> 2.dp
        AppsViewStyle.Density.COMFORTABLE -> 8.dp
        AppsViewStyle.Density.DETAILED -> 10.dp
    }

/** Gap between the app icon and its text column, scaled with the icon it sits beside. */
val AppsViewStyle.Density.rowIconGap: Dp
    get() = when (this) {
        AppsViewStyle.Density.COMPACT -> 8.dp
        AppsViewStyle.Density.COMFORTABLE -> 12.dp
        AppsViewStyle.Density.DETAILED -> 16.dp
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
