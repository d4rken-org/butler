package eu.darken.butler.common.theming

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** Content color for elements drawn on a [ColorScheme.scrim] overlay; contrasts with the scrim. */
val ColorScheme.onScrim: Color
    get() = if (scrim.luminance() < 0.5f) Color.White else Color.Black
