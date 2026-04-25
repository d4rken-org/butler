package eu.darken.butler.workspace.core.operations.history

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.datastore.PreferenceScreenData
import eu.darken.butler.common.datastore.PreferenceStoreMapper
import eu.darken.butler.common.datastore.createValue
import eu.darken.butler.common.debug.DebugSettings
import eu.darken.butler.common.debug.logging.logTag
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HistorySettings @Inject constructor(
    @ApplicationContext private val context: Context,
    debugSettings: DebugSettings,
) : PreferenceScreenData {

    private val Context.dataStore by preferencesDataStore(name = "settings_history")

    override val dataStore: DataStore<Preferences>
        get() = context.dataStore

    val saveHistory = dataStore.createValue("history.enabled", true)
    val maxHistoryItems = dataStore.createValue("history.maximum", 200)

    override val mapper = PreferenceStoreMapper(
        debugSettings.isDebugMode,
        saveHistory,
        maxHistoryItems,
    )

    companion object {
        internal val TAG = logTag("Workspace", "Operations", "History", "Settings")
    }
}
