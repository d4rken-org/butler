package eu.darken.butler.common.theming

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

// TODO: Make this semantic by computing based on scrim luminance (white if scrim is dark, black if light)
val ColorScheme.onScrim: Color
    get() = Color.White
