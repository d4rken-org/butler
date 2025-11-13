package eu.darken.butler.provider.documents.core

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
class DocumentsProviderSettings @Inject constructor(
    @ApplicationContext private val context: Context,
) : PreferenceScreenData {

    private val Context.dataStore by preferencesDataStore(name = "settings_documents_provider")

    override val dataStore: DataStore<Preferences>
        get() = context.dataStore

    val isEnabled = dataStore.createValue("provider.enabled", true)

    override val mapper = PreferenceStoreMapper(
        isEnabled,
    )

    companion object {
        internal val TAG = logTag("Provider", "Documents", "Settings")
    }
}
