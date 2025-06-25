package eu.darken.butler.common.theming

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import eu.darken.butler.common.R
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.preferences.EnumPreference

@JsonClass(generateAdapter = false)
enum class ThemeStyle(override val label: CaString) : EnumPreference<ThemeStyle> {
    @Json(name = "DEFAULT") DEFAULT(R.string.ui_theme_style_default_label.toCaString()),
    @Json(name = "MATERIAL_YOU") MATERIAL_YOU(R.string.ui_theme_style_materialyou_label.toCaString()),
    @Json(name = "MEDIUM_CONTRAST") MEDIUM_CONTRAST(R.string.ui_theme_style_medium_contrast_label.toCaString()),
    @Json(name = "HIGH_CONTRAST") HIGH_CONTRAST(R.string.ui_theme_style_high_contrast_label.toCaString()),
}
