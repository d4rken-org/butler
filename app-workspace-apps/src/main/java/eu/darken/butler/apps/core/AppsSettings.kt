package eu.darken.butler.apps.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.apps.core.engine.AppsState
import eu.darken.butler.apps.core.engine.SortSettings
import eu.darken.butler.common.datastore.PreferenceScreenData
import eu.darken.butler.common.datastore.PreferenceStoreMapper
import eu.darken.butler.common.datastore.createValue
import eu.darken.butler.common.debug.logging.logTag
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppsSettings @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) : PreferenceScreenData {

    private val Context.dataStore by preferencesDataStore(name = "settings_apps")

    override val dataStore: DataStore<Preferences>
        get() = context.dataStore

    val defaultFilterConfig = dataStore.createValue(
        "apps.filter.default",
        AppsState.FilterConfig(),
        json,
    )

    val defaultSortSettings = dataStore.createValue(
        "apps.sort.default",
        SortSettings(),
        json,
    )

    val viewMode = dataStore.createValue(
        "apps.view.mode",
        ViewMode.LIST,
        json,
    )

    override val mapper = PreferenceStoreMapper(
        defaultFilterConfig,
        defaultSortSettings,
        viewMode,
    )

    @Serializable
    enum class ViewMode {
        LIST,
        GRID,
        ;
    }

    companion object {
        internal val TAG = logTag("Apps", "Settings")
    }
}
