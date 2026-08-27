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

    @Query("SELECT * FROM smb_credentials")
    suspend fun getAllOnce(): List<SmbCredentialEntity>

    @Query("SELECT * FROM smb_credentials WHERE locationId = :locationId AND credentialVersion = :credentialVersion")
    suspend fun get(locationId: Uuid, credentialVersion: Int): SmbCredentialEntity?

    @Upsert
    suspend fun upsert(entity: SmbCredentialEntity)

    /** Every generation of a location, e.g. when the location itself is removed. */
    @Query("DELETE FROM smb_credentials WHERE locationId = :locationId")
    suspend fun delete(locationId: Uuid)

    @Query("DELETE FROM smb_credentials WHERE locationId = :locationId AND credentialVersion = :credentialVersion")
    suspend fun deleteGeneration(locationId: Uuid, credentialVersion: Int)

    @Query("DELETE FROM smb_credentials WHERE locationId = :locationId AND credentialVersion != :keepVersion")
    suspend fun deleteOtherGenerations(locationId: Uuid, keepVersion: Int)
}
