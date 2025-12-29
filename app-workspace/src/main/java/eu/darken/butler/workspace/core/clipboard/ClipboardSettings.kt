package eu.darken.butler.workspace.core.clipboard

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
class ClipboardSettings @Inject constructor(
    @ApplicationContext private val context: Context,
) : PreferenceScreenData {

    private val Context.dataStore by preferencesDataStore(name = "clipboard_settings")

    override val dataStore: DataStore<Preferences>
        get() = context.dataStore

    val removeOnPaste = dataStore.createValue("clipboard.remove.on.paste", true)

    val maxItems = dataStore.createValue("clipboard.max.items", 3)

    override val mapper = PreferenceStoreMapper(
        removeOnPaste,
        maxItems,
    )

    companion object {
        internal val TAG = logTag("Clipboard", "Settings")
    }
}
