package eu.darken.butler.main.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.datastore.PreferenceScreenData
import eu.darken.butler.common.datastore.PreferenceStoreMapper
import eu.darken.butler.common.datastore.createValue
import eu.darken.butler.common.debug.DebugSettings
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.theming.ThemeMode
import eu.darken.butler.common.theming.ThemeStyle
import eu.darken.butler.common.updater.UpdateChecker
import eu.darken.butler.main.core.motd.MotdSettings
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeneralSettings @Inject constructor(
    @ApplicationContext private val context: Context,
    debugSettings: DebugSettings,
    moshi: Moshi,
    motdSettings: MotdSettings,
    updateChecker: UpdateChecker
) : PreferenceScreenData {

    private val Context.dataStore by preferencesDataStore(name = "settings_core")

    override val dataStore: DataStore<Preferences>
        get() = context.dataStore

    val themeMode = dataStore.createValue("core.ui.theme.mode", ThemeMode.SYSTEM, moshi)
    val themeStyle = dataStore.createValue("core.ui.theme.style", ThemeStyle.DEFAULT, moshi)

    val usePreviews = dataStore.createValue("core.ui.previews.enabled", true)

    val isOnboardingCompleted = dataStore.createValue("core.onboarding.completed", false)

    val isUpdateCheckEnabled = dataStore.createValue("updater.check.enabled", updateChecker.isEnabledByDefault())

    val isConfirmExitEnabled = dataStore.createValue("core.confirm.exit.enabled", true)

    override val mapper = PreferenceStoreMapper(
        debugSettings.isDebugMode,
        themeMode,
        themeStyle,
        usePreviews,
        motdSettings.isMotdEnabled,
        isUpdateCheckEnabled,
        isConfirmExitEnabled,
    )

    companion object {
        internal val TAG = logTag("Core", "Settings")
    }
}