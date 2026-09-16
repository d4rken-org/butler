package eu.darken.butler.common.theming

import androidx.compose.ui.graphics.Color

data class ThemeSeed(
    val seed: Color,
    val palette: ThemePalette,
    val isAmoled: Boolean = false,
)
