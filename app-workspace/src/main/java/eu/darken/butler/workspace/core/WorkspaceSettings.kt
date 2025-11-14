package eu.darken.butler.workspace.core

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
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkspaceSettings @Inject constructor(
    @ApplicationContext private val context: Context,
    debugSettings: DebugSettings,
    json: Json,
) : PreferenceScreenData {

    private val Context.dataStore by preferencesDataStore(name = "workspace_settings")

    override val dataStore: DataStore<Preferences>
        get() = context.dataStore

    val showTipBadgeExplanation = dataStore.createValue("workspace.manager.tip.badgeexplanation.show", true)

    val showTipFabLongPress = dataStore.createValue("workspace.manager.tip.addtablongpress.show", true)

    val swipeGesturesEnabled = dataStore.createValue("workspace.swipe.gestures.enabled", true)

    val onDemandWorkspaceCreation = dataStore.createValue("workspace.swipe.ondemand.enabled", true)

    val livePreview = dataStore.createValue("workspace.preview.live.enabled", true)

    val paneMode = dataStore.createValue("workspace.pane.mode", "AUTO")

    override val mapper = PreferenceStoreMapper(
        debugSettings.isDebugMode,
        showTipBadgeExplanation,
        showTipFabLongPress,
        swipeGesturesEnabled,
        onDemandWorkspaceCreation,
        livePreview,
        paneMode,
    )

    companion object {
        internal val TAG = logTag("Workspace", "Settings")
    }
}