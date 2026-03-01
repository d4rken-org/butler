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
import eu.darken.butler.common.theming.ThemeColor
import eu.darken.butler.common.theming.ThemeMode
import eu.darken.butler.common.theming.ThemeState
import eu.darken.butler.common.theming.ThemeStyle
import eu.darken.butler.common.updater.UpdateChecker
import eu.darken.butler.main.core.motd.MotdSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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

    val isOnboardingCompleted = dataStore.createValue("core.onboarding.completed", false)

    val isUpdateCheckEnabled = dataStore.createValue("updater.check.enabled", updateChecker.isEnabledByDefault())

    val isConfirmExitEnabled = dataStore.createValue("core.confirm.exit.enabled", true)

    override val mapper = PreferenceStoreMapper(
        debugSettings.isDebugMode,
        themeMode,
        themeStyle,
        themeColor,
        motdSettings.isMotdEnabled,
        isUpdateCheckEnabled,
        isConfirmExitEnabled,
    )

    companion object {
        internal val TAG = logTag("Core", "Settings")
    }
}

val GeneralSettings.themeState: Flow<ThemeState>
    get() = combine(themeMode.flow, themeStyle.flow, themeColor.flow) { mode, style, color ->
        ThemeState(mode, style, color)
    }

val GeneralSettings.themeStateBlocking: ThemeState
    get() = ThemeState(themeMode.valueBlocking, themeStyle.valueBlocking, themeColor.valueBlocking)