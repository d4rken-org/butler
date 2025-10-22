package eu.darken.butler.main.core.motd

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
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Singleton
class MotdSettings @Inject constructor(
    @ApplicationContext private val context: Context,
    json: Json,
) {

    private val Context.dataStore by preferencesDataStore(name = "settings_motd")

    private val dataStore: DataStore<Preferences>
        get() = context.dataStore

    val lastMotd = dataStore.createValue<MotdState?>("motd.state.cache", null, json)
    val lastDismissedMotd = dataStore.createValue<Uuid?>("motd.last.dismissed", null, json)
    val lastFetchTime = dataStore.createValue<Instant?>("motd.last.fetch.time", null, json)
    val isMotdEnabled = dataStore.createValue("motd.enabled", true)


    companion object {
        internal val TAG = logTag("Motd", "Settings")
    }
}