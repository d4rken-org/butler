package eu.darken.butler.viewer.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.datastore.PreferenceScreenData
import eu.darken.butler.common.datastore.PreferenceStoreMapper
import eu.darken.butler.common.datastore.createValue
import eu.darken.butler.common.debug.logging.logTag
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ViewerSettings @Inject constructor(
    @ApplicationContext private val context: Context,
) : PreferenceScreenData {

    private val Context.dataStore by preferencesDataStore(name = "settings_viewer")

    override val dataStore: DataStore<Preferences>
        get() = context.dataStore

    val showNextAfterDelete = dataStore.createValue("viewer.delete.show_next", true)

    override val mapper = PreferenceStoreMapper(
        showNextAfterDelete,
    )

    companion object {
        internal val TAG = logTag("Viewer", "Settings")
    }
}
