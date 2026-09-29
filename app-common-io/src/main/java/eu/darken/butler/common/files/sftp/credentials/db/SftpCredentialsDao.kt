package eu.darken.butler.common.files.sftp.credentials.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

@Dao
interface SftpCredentialsDao {

    @Query("SELECT * FROM sftp_credentials")
    fun getAll(): Flow<List<SftpCredentialEntity>>

    @Query("SELECT * FROM sftp_credentials")
    suspend fun getAllOnce(): List<SftpCredentialEntity>

    @Query("SELECT * FROM sftp_credentials WHERE locationId = :locationId AND credentialVersion = :credentialVersion")
    suspend fun get(locationId: Uuid, credentialVersion: Int): SftpCredentialEntity?

    @Upsert
    suspend fun upsert(entity: SftpCredentialEntity)

    /** Every generation of a location, e.g. when the location itself is removed. */
    @Query("DELETE FROM sftp_credentials WHERE locationId = :locationId")
    suspend fun delete(locationId: Uuid)

    @Query("DELETE FROM sftp_credentials WHERE locationId = :locationId AND credentialVersion = :credentialVersion")
    suspend fun deleteGeneration(locationId: Uuid, credentialVersion: Int)

    @Query("DELETE FROM sftp_credentials WHERE locationId = :locationId AND credentialVersion != :keepVersion")
    suspend fun deleteOtherGenerations(locationId: Uuid, keepVersion: Int)
}
