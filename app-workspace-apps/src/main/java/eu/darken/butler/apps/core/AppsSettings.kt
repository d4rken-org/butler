package eu.darken.butler.apps.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.datastore.PreferenceScreenData
import eu.darken.butler.common.datastore.PreferenceStoreMapper
import eu.darken.butler.common.datastore.createValue
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.contracts.apps.AppsViewStyle
import eu.darken.butler.workspace.contracts.apps.SortSettings
import eu.darken.butler.workspace.contracts.apps.TagFilterConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

@Singleton
class AppsSettings @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) : PreferenceScreenData {

    private val Context.dataStore by preferencesDataStore(name = "settings_apps")

    override val dataStore: DataStore<Preferences>
        get() = context.dataStore

    val defaultFilterConfig = dataStore.createValue(
        "apps.filter.tags.default",
        TagFilterConfig(),
        json,
        onErrorFallbackToDefault = true,
    )

    val defaultSortSettings = dataStore.createValue(
        "apps.sort.default",
        SortSettings(),
        json,
        onErrorFallbackToDefault = true,
    )

    val defaultViewStyle = dataStore.createValue(
        "apps.view.style.default",
        AppsViewStyle.default(),
        json,
        onErrorFallbackToDefault = true,
    )

    override val mapper = PreferenceStoreMapper(
        defaultFilterConfig,
        defaultSortSettings,
        defaultViewStyle,
    )

    companion object {
        internal val TAG = logTag("Apps", "Settings")
    }
}
