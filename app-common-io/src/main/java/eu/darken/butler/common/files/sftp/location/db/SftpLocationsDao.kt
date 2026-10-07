package eu.darken.butler.common.files.sftp.location.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Dao
interface SftpLocationsDao {

    @Query("SELECT * FROM sftp_locations ORDER BY createdAt ASC")
    fun getAll(): Flow<List<SftpLocationEntity>>

    @Query("SELECT * FROM sftp_locations WHERE locationId = :locationId")
    suspend fun get(locationId: Uuid): SftpLocationEntity?

    @Upsert
    suspend fun upsert(entity: SftpLocationEntity)

    @Query("DELETE FROM sftp_locations WHERE locationId = :locationId")
    suspend fun delete(locationId: Uuid)

    /**
     * The host and port are part of the predicate, so a write that lands after the user edited the
     * endpoint updates nothing: a delayed probe result must not stamp `nas-b` with the time
     * `nas-a` answered.
     */
    @Query("UPDATE sftp_locations SET lastSeenAt = :at WHERE locationId = :locationId AND host = :host AND port = :port")
    suspend fun markSeen(locationId: Uuid, host: String, port: Int, at: Instant)

    /**
     * Replaces the pin only while the row still points at [host]:[port] and still holds the pin of
     * [expectedTrustRevision], so a confirmation dialog opened for `nas-a` cannot pin its key onto a
     * location since edited to `nas-b`, nor replace a key trusted after it was opened.
     *
     * @return the number of rows changed, 0 if the location is gone, points elsewhere or was retrusted
     */
    @Query(
        "UPDATE sftp_locations SET hostKeyType = :hostKeyType, hostKeyBlob = :hostKeyBlob, " +
            "hostKeyFingerprint = :hostKeyFingerprint, trustRevision = trustRevision + 1, updatedAt = :updatedAt " +
            "WHERE locationId = :locationId AND host = :host AND port = :port " +
            "AND trustRevision = :expectedTrustRevision"
    )
    suspend fun retrust(
        locationId: Uuid,
        host: String,
        port: Int,
        expectedTrustRevision: Int,
        hostKeyType: String,
        hostKeyBlob: ByteArray,
        hostKeyFingerprint: String,
        updatedAt: Instant,
    ): Int

    /**
     * Reads and rewrites one row in a single transaction, so [transform] decides on the row as it is
     * at write time, not as it was when an edit started.
     *
     * @return the stored row, null if the location does not exist
     */
    @Transaction
    suspend fun rewrite(
        locationId: Uuid,
        transform: (SftpLocationEntity) -> SftpLocationEntity,
    ): SftpLocationEntity? {
        val current = get(locationId) ?: return null
        return transform(current).also { upsert(it) }
    }
}
