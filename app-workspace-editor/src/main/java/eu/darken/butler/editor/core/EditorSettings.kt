package eu.darken.butler.editor.core

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
class EditorSettings @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : PreferenceScreenData {

    private val Context.dataStore by preferencesDataStore(name = "settings_editor")

    override val dataStore: DataStore<Preferences>
        get() = context.dataStore

    val showLineNumbers = dataStore.createValue("editor.line_numbers.show", true)

    override val mapper = PreferenceStoreMapper(
        showLineNumbers,
    )

    companion object {
        internal val TAG = logTag("Editor", "Settings")
    }
}
