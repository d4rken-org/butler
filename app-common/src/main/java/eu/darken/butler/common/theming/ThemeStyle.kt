package eu.darken.butler.common.theming

import eu.darken.butler.common.R
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.preferences.EnumPreference
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ThemeStyle(override val label: CaString) : EnumPreference<ThemeStyle> {
    @SerialName("DEFAULT") DEFAULT(R.string.ui_theme_style_default_label.toCaString()),
    @SerialName("MATERIAL_YOU") MATERIAL_YOU(R.string.ui_theme_style_materialyou_label.toCaString()),
    @SerialName("MEDIUM_CONTRAST") MEDIUM_CONTRAST(R.string.ui_theme_style_medium_contrast_label.toCaString()),
    @SerialName("HIGH_CONTRAST") HIGH_CONTRAST(R.string.ui_theme_style_high_contrast_label.toCaString()),
}
