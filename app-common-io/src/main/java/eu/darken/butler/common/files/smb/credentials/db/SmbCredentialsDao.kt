package eu.darken.butler.common.files.smb.credentials.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

@Dao
interface SmbCredentialsDao {

    @Query("SELECT * FROM smb_credentials")
    fun getAll(): Flow<List<SmbCredentialEntity>>

    @Query("SELECT * FROM smb_credentials WHERE locationId = :locationId")
    suspend fun get(locationId: Uuid): SmbCredentialEntity?

    @Query("SELECT locationId FROM smb_credentials")
    suspend fun getLocationIds(): List<Uuid>

    @Upsert
    suspend fun upsert(entity: SmbCredentialEntity)

    @Query("DELETE FROM smb_credentials WHERE locationId = :locationId")
    suspend fun delete(locationId: Uuid)
}
