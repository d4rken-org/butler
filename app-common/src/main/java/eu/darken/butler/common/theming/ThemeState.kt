package eu.darken.butler.common.theming

import androidx.compose.ui.graphics.Color

data class ThemeState(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val style: ThemeStyle = ThemeStyle.DEFAULT,
    val color: ThemeColor = ThemeColor.GREEN,
    /** An RGB suit color for the mascot, overriding the per-mode default in both modes. */
    val suitColor: Int? = null,
    /** An RGB seed for [ThemeColor.CUSTOM]. Null falls back to [DEFAULT_CUSTOM_SEED]. */
    val customSeed: Int? = null,
    val customPalette: ThemePalette = ThemePalette.TONAL_SPOT,
) {

    /** Resolved regardless of [color], so a picker can show the custom swatch under any preset. */
    val customThemeSeed: ThemeSeed
        get() = ThemeSeed(
            seed = Color((customSeed ?: DEFAULT_CUSTOM_SEED) or 0xFF000000.toInt()),
            palette = customPalette,
        )

    val themeSeed: ThemeSeed
        get() = color.preset ?: customThemeSeed

    companion object {
        /** RGB without alpha, matching how the picker stores it. Butler's own green. */
        const val DEFAULT_CUSTOM_SEED: Int = 0x006D36
    }
}
