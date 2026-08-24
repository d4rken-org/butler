package eu.darken.butler.common.files.smb.location.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
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
}
