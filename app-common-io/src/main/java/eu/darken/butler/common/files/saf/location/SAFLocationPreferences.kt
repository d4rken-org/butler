package eu.darken.butler.common.files.saf.location

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.datastore.DataStoreValue
import eu.darken.butler.common.datastore.PreferenceScreenData
import eu.darken.butler.common.datastore.PreferenceStoreMapper
import eu.darken.butler.common.datastore.createValue
import eu.darken.butler.common.debug.logging.logTag
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DataStore-backed preferences for SAF location user customizations.
 *
 * Stores user preferences like custom labels, hidden state, and pinned state
 * for each SAF location. Keyed by location ID.
 */
@Singleton
class SAFLocationPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) : PreferenceScreenData {

    private val Context.dataStore by preferencesDataStore(name = "settings_saf")

    override val dataStore: DataStore<Preferences>
        get() = context.dataStore

    /**
     * Map of location ID to user preferences
     */
    val locations: DataStoreValue<Map<String, LocationPreference>> = dataStore.createValue(
        key = "saf.locations",
        defaultValue = emptyMap(),
        json = json,
    )

    /**
     * Get preference for a specific location, or default if not set
     */
    suspend fun getLocationPreference(locationId: String): LocationPreference {
        val current = locations.flow.first()
        return current[locationId] ?: LocationPreference(locationId)
    }

    /**
     * Update preference for a specific location
     */
    suspend fun updateLocationPreference(
        locationId: String,
        update: (LocationPreference) -> LocationPreference
    ) {
        val current = locations.flow.first()
        val existing = current[locationId] ?: LocationPreference(locationId)
        val updated = update(existing)
        locations.update { current + (locationId to updated) }
    }

    /**
     * Remove preference for a specific location
     */
    suspend fun removeLocationPreference(locationId: String) {
        locations.update { current ->
            current - locationId
        }
    }

    override val mapper = PreferenceStoreMapper(
        locations,
    )

    companion object {
        internal val TAG = logTag("SAF", "Location", "Preferences")
    }
}

