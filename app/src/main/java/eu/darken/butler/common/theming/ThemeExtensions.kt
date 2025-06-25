package eu.darken.butler.common.theming

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.preferences.EnumPreference
import eu.darken.butler.main.core.GeneralSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

val GeneralSettings.themeState: Flow<ThemeState>
    get() = combine(
        themeMode.flow,
        themeStyle.flow
    ) { mode, style ->
        ThemeState(mode, style)
    }