package eu.darken.butler.common.theming

import eu.darken.butler.common.R
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.preferences.EnumPreference
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ThemeMode(override val label: CaString) : EnumPreference<ThemeMode> {
    @SerialName("SYSTEM") SYSTEM(R.string.ui_theme_mode_system_label.toCaString()),
    @SerialName("DARK") DARK(R.string.ui_theme_mode_dark_label.toCaString()),
    @SerialName("LIGHT") LIGHT(R.string.ui_theme_mode_light_label.toCaString()),
}

