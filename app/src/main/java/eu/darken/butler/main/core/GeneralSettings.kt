package eu.darken.butler.main.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.datastore.PreferenceScreenData
import eu.darken.butler.common.datastore.valueBlocking
import eu.darken.butler.common.datastore.PreferenceStoreMapper
import eu.darken.butler.common.datastore.createValue
import eu.darken.butler.common.debug.DebugSettings
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.combine
import eu.darken.butler.common.theming.ThemeColor
import eu.darken.butler.common.theming.ThemeMode
import eu.darken.butler.common.theming.ThemePalette
import eu.darken.butler.common.theming.ThemeState
import eu.darken.butler.common.theming.ThemeStyle
import eu.darken.butler.common.updater.UpdateChecker
import eu.darken.butler.main.core.motd.MotdSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeneralSettings @Inject constructor(
    @ApplicationContext private val context: Context,
    debugSettings: DebugSettings,
    json: Json,
    motdSettings: MotdSettings,
    updateChecker: UpdateChecker,
) : PreferenceScreenData {

    private val Context.dataStore by preferencesDataStore(name = "settings_core")

    override val dataStore: DataStore<Preferences>
        get() = context.dataStore

    val themeMode = dataStore.createValue(
        key = "core.ui.theme.mode",
        defaultValue = ThemeMode.SYSTEM,
        json = json,
        onErrorFallbackToDefault = BuildConfigWrap.BUILD_TYPE == BuildConfigWrap.BuildType.RELEASE,
    )
    val themeStyle = dataStore.createValue(
        key = "core.ui.theme.style",
        defaultValue = ThemeStyle.DEFAULT,
        json = json,
        onErrorFallbackToDefault = BuildConfigWrap.BUILD_TYPE == BuildConfigWrap.BuildType.RELEASE,
    )
    val themeColor = dataStore.createValue(
        key = "core.ui.theme.color",
        defaultValue = ThemeColor.GREEN,
        json = json,
        onErrorFallbackToDefault = BuildConfigWrap.BUILD_TYPE == BuildConfigWrap.BuildType.RELEASE,
    )

    /** An RGB suit color for the mascot. Null keeps the per-theme default. */
    val mascotSuitColor = dataStore.createValue<Int?>(
        key = "core.ui.mascot.suit",
        defaultValue = null,
        json = json,
        onErrorFallbackToDefault = BuildConfigWrap.BUILD_TYPE == BuildConfigWrap.BuildType.RELEASE,
    )

    /** An RGB seed for [ThemeColor.CUSTOM]. Null uses [ThemeState.DEFAULT_CUSTOM_SEED]. */
    val themeCustomSeed = dataStore.createValue<Int?>(
        key = "core.ui.theme.custom.seed",
        defaultValue = null,
        json = json,
        onErrorFallbackToDefault = BuildConfigWrap.BUILD_TYPE == BuildConfigWrap.BuildType.RELEASE,
    )
    val themeCustomPalette = dataStore.createValue(
        key = "core.ui.theme.custom.palette",
        defaultValue = ThemePalette.TONAL_SPOT,
        json = json,
        onErrorFallbackToDefault = BuildConfigWrap.BUILD_TYPE == BuildConfigWrap.BuildType.RELEASE,
    )

    val isOnboardingCompleted = dataStore.createValue("core.onboarding.completed", false)

    val isUpdateCheckEnabled = dataStore.createValue("updater.check.enabled", updateChecker.isEnabledByDefault())

    val isConfirmExitEnabled = dataStore.createValue("core.confirm.exit.enabled", true)

    val isDisplayCutoutAvoided = dataStore.createValue("core.ui.cutout.avoided", false)

    override val mapper = PreferenceStoreMapper(
        debugSettings.isDebugMode,
        themeMode,
        themeStyle,
        themeColor,
        themeCustomSeed,
        themeCustomPalette,
        mascotSuitColor,
        motdSettings.isMotdEnabled,
        isUpdateCheckEnabled,
        isConfirmExitEnabled,
        isDisplayCutoutAvoided,
    )

    companion object {
        internal val TAG = logTag("Core", "Settings")
    }
}

val GeneralSettings.themeState: Flow<ThemeState>
    get() = combine(
        themeMode.flow,
        themeStyle.flow,
        themeColor.flow,
        mascotSuitColor.flow,
        themeCustomSeed.flow,
        themeCustomPalette.flow,
    ) { mode, style, color, suitColor, customSeed, customPalette ->
        ThemeState(mode, style, color, suitColor, customSeed, customPalette)
    }

val GeneralSettings.themeStateBlocking: ThemeState
    get() = ThemeState(
        themeMode.valueBlocking,
        themeStyle.valueBlocking,
        themeColor.valueBlocking,
        mascotSuitColor.valueBlocking,
        themeCustomSeed.valueBlocking,
        themeCustomPalette.valueBlocking,
    )