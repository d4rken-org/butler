package eu.darken.butler.common.theming

import eu.darken.butler.common.R
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.preferences.EnumPreference
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ThemeColor(override val label: CaString) : EnumPreference<ThemeColor> {
    @SerialName("GREEN") GREEN(R.string.ui_theme_color_green_label.toCaString()),
    @SerialName("BLUE") BLUE(R.string.ui_theme_color_blue_label.toCaString()),
    @SerialName("AMOLED") AMOLED(R.string.ui_theme_color_amoled_label.toCaString()),
}