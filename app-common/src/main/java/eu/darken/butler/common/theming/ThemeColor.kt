package eu.darken.butler.common.theming

import androidx.compose.ui.graphics.Color
import eu.darken.butler.common.R
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.preferences.EnumPreference
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ThemeColor(
    override val label: CaString,
    /** The seed this preset generates from, or null when the seed comes from settings. */
    val preset: ThemeSeed?,
) : EnumPreference<ThemeColor> {
    @SerialName("GREEN") GREEN(
        R.string.ui_theme_color_green_label.toCaString(),
        ThemeSeed(Color(0xFF006D36), ThemePalette.TONAL_SPOT),
    ),
    @SerialName("BLUE") BLUE(
        R.string.ui_theme_color_blue_label.toCaString(),
        ThemeSeed(Color(0xFF1565C0), ThemePalette.TONAL_SPOT),
    ),
    @SerialName("AMOLED") AMOLED(
        R.string.ui_theme_color_amoled_label.toCaString(),
        ThemeSeed(Color(0xFFE65100), ThemePalette.TONAL_SPOT, isAmoled = true),
    ),
    @SerialName("CUSTOM") CUSTOM(
        R.string.ui_theme_color_custom_label.toCaString(),
        null,
    ),
}
