package eu.darken.butler.common.files.webdav.credentials.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

@Dao
interface WebDavCredentialsDao {

    @Query("SELECT * FROM webdav_credentials")
    fun getAll(): Flow<List<WebDavCredentialEntity>>

    @Query("SELECT * FROM webdav_credentials")
    suspend fun getAllOnce(): List<WebDavCredentialEntity>

    @Query("SELECT * FROM webdav_credentials WHERE locationId = :locationId AND credentialVersion = :credentialVersion")
    suspend fun get(locationId: Uuid, credentialVersion: Int): WebDavCredentialEntity?

    @Upsert
    suspend fun upsert(entity: WebDavCredentialEntity)

    /** Every generation of a location, e.g. when the location itself is removed. */
    @Query("DELETE FROM webdav_credentials WHERE locationId = :locationId")
    suspend fun delete(locationId: Uuid)

    @Query("DELETE FROM webdav_credentials WHERE locationId = :locationId AND credentialVersion = :credentialVersion")
    suspend fun deleteGeneration(locationId: Uuid, credentialVersion: Int)

    @Query("DELETE FROM webdav_credentials WHERE locationId = :locationId AND credentialVersion != :keepVersion")
    suspend fun deleteOtherGenerations(locationId: Uuid, keepVersion: Int)
}
