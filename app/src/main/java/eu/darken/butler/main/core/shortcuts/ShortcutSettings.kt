package eu.darken.butler.main.core.shortcuts

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.datastore.PreferenceScreenData
import eu.darken.butler.common.datastore.PreferenceStoreMapper
import eu.darken.butler.common.datastore.createValue
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.serialization.SerializationCommon
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShortcutSettings @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @SerializationCommon private val json: Json,
) : PreferenceScreenData {

    private val Context.dataStore by preferencesDataStore(name = "settings_shortcuts")

    override val dataStore: DataStore<Preferences>
        get() = context.dataStore

    val isEnabled = dataStore.createValue("shortcuts.enabled", true)

    val autoRememberEnabled = dataStore.createValue("shortcuts.last_accessed.enabled", true)

    val maxShortcuts = dataStore.createValue("shortcuts.last_accessed.max_count", 3)

    val minAccessCount = dataStore.createValue("shortcuts.last_accessed.min_access", 3)

    val lastAccessedData = dataStore.createValue("shortcuts.last_accessed.data", LastAccessedPaths(), json)

    override val mapper = PreferenceStoreMapper(
        isEnabled,
        autoRememberEnabled,
        maxShortcuts,
        minAccessCount,
    )

    companion object {
        internal val TAG = logTag("Shortcuts", "Settings")
    }
}