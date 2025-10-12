package eu.darken.butler.common.files.saf.location

import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.saf.location.db.SAFLocationPreferenceDao
import eu.darken.butler.common.files.saf.location.db.SAFLocationPreferenceEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed preferences for SAF location user customizations.
 *
 * Stores user preferences like custom labels and hidden state
 * for each SAF location. Keyed by location ID.
 */
@Singleton
class SAFLocationPreferences @Inject constructor(
    private val dao: SAFLocationPreferenceDao,
) {

    /**
     * Flow of all location preferences as a map (locationId -> LocationPreference).
     * Emits a new map whenever any preference changes.
     */
    val locations: Flow<Map<String, LocationPreference>> = dao.getAllPreferences()
        .map { entities ->
            entities.associate { entity ->
                entity.locationId to LocationPreference(
                    locationId = entity.locationId,
                    userLabel = entity.userLabel,
                    isHidden = entity.isHidden,
                )
            }
        }

    suspend fun getLocationPreference(locationId: String): LocationPreference {
        val entity = dao.getPreference(locationId)
        return entity?.let {
            LocationPreference(
                locationId = it.locationId,
                userLabel = it.userLabel,
                isHidden = it.isHidden,
            )
        } ?: LocationPreference(locationId)
    }

    suspend fun updateLocationPreference(
        locationId: String,
        update: (LocationPreference) -> LocationPreference
    ) {
        val current = getLocationPreference(locationId)
        val updated = update(current)
        dao.upsert(
            SAFLocationPreferenceEntity(
                locationId = updated.locationId,
                userLabel = updated.userLabel,
                isHidden = updated.isHidden,
            )
        )
    }

    suspend fun removeLocationPreference(locationId: String) {
        dao.delete(locationId)
    }

    /**
     * Remove preferences for locations that no longer have active permissions.
     * Should be called during location cache refresh.
     */
    suspend fun cleanup(activeLocationIds: List<String>) {
        if (activeLocationIds.isEmpty()) {
            // Don't delete everything if the list is empty (might be a bug)
            return
        }
        dao.cleanup(activeLocationIds)
    }

    companion object {
        internal val TAG = logTag("SAF", "Location", "Preferences")
    }
}
