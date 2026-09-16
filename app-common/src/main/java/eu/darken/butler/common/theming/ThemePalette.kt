package eu.darken.butler.common.theming

import com.materialkolor.PaletteStyle
import eu.darken.butler.common.R
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.preferences.EnumPreference
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ThemePalette(
    override val label: CaString,
    val style: PaletteStyle,
) : EnumPreference<ThemePalette> {
    @SerialName("TONAL_SPOT") TONAL_SPOT(
        R.string.ui_theme_palette_tonalspot_label.toCaString(),
        PaletteStyle.TonalSpot,
    ),
    @SerialName("NEUTRAL") NEUTRAL(
        R.string.ui_theme_palette_neutral_label.toCaString(),
        PaletteStyle.Neutral,
    ),
    @SerialName("VIBRANT") VIBRANT(
        R.string.ui_theme_palette_vibrant_label.toCaString(),
        PaletteStyle.Vibrant,
    ),
    @SerialName("EXPRESSIVE") EXPRESSIVE(
        R.string.ui_theme_palette_expressive_label.toCaString(),
        PaletteStyle.Expressive,
    ),
    @SerialName("RAINBOW") RAINBOW(
        R.string.ui_theme_palette_rainbow_label.toCaString(),
        PaletteStyle.Rainbow,
    ),
    @SerialName("FRUIT_SALAD") FRUIT_SALAD(
        R.string.ui_theme_palette_fruitsalad_label.toCaString(),
        PaletteStyle.FruitSalad,
    ),
    @SerialName("MONOCHROME") MONOCHROME(
        R.string.ui_theme_palette_monochrome_label.toCaString(),
        PaletteStyle.Monochrome,
    ),
    @SerialName("FIDELITY") FIDELITY(
        R.string.ui_theme_palette_fidelity_label.toCaString(),
        PaletteStyle.Fidelity,
    ),
    @SerialName("CONTENT") CONTENT(
        R.string.ui_theme_palette_content_label.toCaString(),
        PaletteStyle.Content,
    ),
}
