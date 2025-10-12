package eu.darken.butler.common.files.saf.location.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SAFLocationPreferenceDao {

    /**
     * Get all location preferences as a Flow.
     * Emits a new list whenever any preference changes.
     */
    @Query("SELECT * FROM saf_location_preferences")
    fun getAllPreferences(): Flow<List<SAFLocationPreferenceEntity>>

    /**
     * Get a specific location preference by ID
     */
    @Query("SELECT * FROM saf_location_preferences WHERE locationId = :locationId")
    suspend fun getPreference(locationId: String): SAFLocationPreferenceEntity?

    /**
     * Insert or update a location preference
     */
    @Upsert
    suspend fun upsert(entity: SAFLocationPreferenceEntity)

    /**
     * Delete a specific location preference
     */
    @Query("DELETE FROM saf_location_preferences WHERE locationId = :locationId")
    suspend fun delete(locationId: String)

    /**
     * Delete all location preferences
     */
    @Query("DELETE FROM saf_location_preferences")
    suspend fun deleteAll()

    /**
     * Remove orphaned preferences that don't have active permissions.
     * Keeps only preferences whose IDs are in the provided list.
     */
    @Query("DELETE FROM saf_location_preferences WHERE locationId NOT IN (:activeLocationIds)")
    suspend fun cleanup(activeLocationIds: List<String>)

    /**
     * Get count of stored preferences (useful for debugging)
     */
    @Query("SELECT COUNT(*) FROM saf_location_preferences")
    suspend fun getCount(): Int
}
