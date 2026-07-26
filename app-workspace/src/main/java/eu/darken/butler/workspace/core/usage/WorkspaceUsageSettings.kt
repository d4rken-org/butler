package eu.darken.butler.workspace.core.usage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.datastore.createValue
import eu.darken.butler.common.debug.logging.logTag
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dedicated store for workspace type usage counters.
 *
 * Kept out of `WorkspaceSettings` on purpose: this value is written on every tab creation and
 * `WorkspaceSettings.mapper` feeds the preference screen and settings export — churn data does not
 * belong there. Nothing here is user-configurable, hence no `PreferenceScreenData`.
 */
@Singleton
class WorkspaceUsageSettings @Inject constructor(
    @param:ApplicationContext private val context: Context,
    json: Json,
) {

    private val Context.dataStore by preferencesDataStore(name = "workspace_usage")

    val dataStore: DataStore<Preferences>
        get() = context.dataStore

    /**
     * [onErrorFallbackToDefault] is required: with the default (false) a malformed persisted blob
     * would terminate the flow permanently, killing the ranking with no way for [WorkspaceUsageRepo]
     * to repair it.
     */
    val usageData = dataStore.createValue(
        "workspace.usage.types",
        WorkspaceUsageData(),
        json,
        onErrorFallbackToDefault = true,
    )

    companion object {
        internal val TAG = logTag("Workspace", "Usage", "Settings")
    }
}
