package eu.darken.butler.common.theming

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** Content color for elements drawn on a [ColorScheme.scrim] overlay; contrasts with the scrim. */
val ColorScheme.onScrim: Color
    get() = if (scrim.luminance() < 0.5f) Color.White else Color.Black

/**
 * "This is good" color, the counterpart of [ColorScheme.error].
 *
 * Material 3 has no success role, and [ColorScheme.primary] cannot stand in for one here: it
 * follows the theme color the user picked, so it says nothing about a state. Both greens clear
 * 4.5:1 against the surface they are picked for.
 */
val ColorScheme.success: Color
    get() = if (surface.luminance() < 0.5f) Color(0xFF7BD88F) else Color(0xFF1B7A32)
