package eu.darken.butler.common.theming

import androidx.compose.material3.ColorScheme

object ThemeColorProvider {

    fun getLightColorScheme(color: ThemeColor, style: ThemeStyle): ColorScheme = when (color) {
        ThemeColor.GREEN -> ButlerColorsGreen.lightScheme(style)
        ThemeColor.BLUE -> ButlerColorsBlue.lightScheme(style)
        ThemeColor.AMOLED -> ButlerColorsAmoled.lightScheme(style)
    }

    fun getDarkColorScheme(color: ThemeColor, style: ThemeStyle): ColorScheme = when (color) {
        ThemeColor.GREEN -> ButlerColorsGreen.darkScheme(style)
        ThemeColor.BLUE -> ButlerColorsBlue.darkScheme(style)
        ThemeColor.AMOLED -> ButlerColorsAmoled.darkScheme(style)
    }
}
