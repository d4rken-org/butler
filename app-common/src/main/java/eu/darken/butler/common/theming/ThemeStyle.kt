package eu.darken.butler.common.theming

import com.materialkolor.Contrast
import eu.darken.butler.common.R
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.preferences.EnumPreference
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ThemeStyle(
    override val label: CaString,
    val contrastLevel: Double,
) : EnumPreference<ThemeStyle> {
    @SerialName("DEFAULT") DEFAULT(
        R.string.ui_theme_style_default_label.toCaString(),
        Contrast.Default.value,
    ),
    /** Below API 31 there is no platform scheme, so this still reaches the generator. */
    @SerialName("MATERIAL_YOU") MATERIAL_YOU(
        R.string.ui_theme_style_materialyou_label.toCaString(),
        Contrast.Default.value,
    ),
    @SerialName("MEDIUM_CONTRAST") MEDIUM_CONTRAST(
        R.string.ui_theme_style_medium_contrast_label.toCaString(),
        Contrast.Medium.value,
    ),
    @SerialName("HIGH_CONTRAST") HIGH_CONTRAST(
        R.string.ui_theme_style_high_contrast_label.toCaString(),
        Contrast.High.value,
    ),
}
