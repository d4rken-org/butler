package eu.darken.butler.common.files.smb.location.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Dao
interface SmbLocationsDao {

    @Query("SELECT * FROM smb_locations ORDER BY createdAt ASC")
    fun getAll(): Flow<List<SmbLocationEntity>>

    @Query("SELECT * FROM smb_locations WHERE locationId = :locationId")
    suspend fun get(locationId: Uuid): SmbLocationEntity?

    @Upsert
    suspend fun upsert(entity: SmbLocationEntity)

    @Query("DELETE FROM smb_locations WHERE locationId = :locationId")
    suspend fun delete(locationId: Uuid)

    /**
     * The host and port are part of the predicate, so a write that lands after the user edited the
     * endpoint updates nothing: a delayed probe result must not stamp `nas-b` with the time
     * `nas-a` answered.
     */
    @Query("UPDATE smb_locations SET lastSeenAt = :at WHERE locationId = :locationId AND host = :host AND port = :port")
    suspend fun markSeen(locationId: Uuid, host: String, port: Int, at: Instant)
}
