package eu.darken.butler.explorer.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.datastore.PreferenceScreenData
import eu.darken.butler.common.datastore.PreferenceStoreMapper
import eu.darken.butler.common.datastore.createValue
import eu.darken.butler.common.debug.logging.logTag
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExplorerSettings @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val json: Json,
) : PreferenceScreenData {

    private val Context.dataStore by preferencesDataStore(name = "settings_explorer")

    override val dataStore: DataStore<Preferences>
        get() = context.dataStore

    val sortSettings = dataStore.createValue("explorer.sort.default", SortSettings(), json)

    override val mapper = PreferenceStoreMapper(
        sortSettings,
    )

    companion object {
        internal val TAG = logTag("Explorer", "Settings")
    }
}