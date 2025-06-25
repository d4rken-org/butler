package eu.darken.butler.workspace.core

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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkspaceSettings @Inject constructor(
    @ApplicationContext private val context: Context,
    debugSettings: DebugSettings,
    moshi: Moshi,
) : PreferenceScreenData {

    private val Context.dataStore by preferencesDataStore(name = "workspace_settings")

    override val dataStore: DataStore<Preferences>
        get() = context.dataStore

    val isButtonActionsFlipped = dataStore.createValue("workspace.manager.action.flipped", false)

    override val mapper = PreferenceStoreMapper(
        debugSettings.isDebugMode,
        isButtonActionsFlipped,
    )

    companion object {
        internal val TAG = logTag("Workspace", "Settings")
    }
}