package eu.darken.butler.common.adb

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
class AdbSettings @Inject constructor(
    @ApplicationContext private val context: Context,
) : PreferenceScreenData {

    private val Context.dataStore by preferencesDataStore(name = "settings_shizuku")

    override val dataStore: DataStore<Preferences>
        get() = context.dataStore

    val useShizuku = dataStore.createValue("core.shizuku.enabled", null as Boolean?)

    val useAdb = useShizuku

    /** ADB access was switched on while no server was reachable, so ask for permission once one is. */
    val permissionPromptPending = dataStore.createValue("core.shizuku.permission.prompt_pending", false)

    override val mapper = PreferenceStoreMapper(
        useShizuku
    )

    companion object {
        internal val TAG = logTag("ADB", "Settings")
    }
}