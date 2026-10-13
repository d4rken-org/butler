package eu.darken.butler.common.files.webdav.location.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Dao
interface WebDavLocationsDao {

    @Query("SELECT * FROM webdav_locations ORDER BY createdAt ASC")
    fun getAll(): Flow<List<WebDavLocationEntity>>

    @Query("SELECT * FROM webdav_locations WHERE locationId = :locationId")
    suspend fun get(locationId: Uuid): WebDavLocationEntity?

    @Upsert
    suspend fun upsert(entity: WebDavLocationEntity)

    @Query("DELETE FROM webdav_locations WHERE locationId = :locationId")
    suspend fun delete(locationId: Uuid)

    @Query("UPDATE webdav_locations SET lastSeenAt = :at WHERE locationId = :locationId")
    suspend fun setLastSeen(locationId: Uuid, at: Instant)

    /**
     * Stamps the row only while [isSameEndpoint] holds for it at write time: the row stores a URL, the
     * probe reports the host and port it reached, so the comparison cannot be a plain SQL predicate.
     * A delayed result for `nas-a` must not stamp a location since edited to `nas-b`.
     */
    @Transaction
    suspend fun markSeen(
        locationId: Uuid,
        at: Instant,
        isSameEndpoint: (WebDavLocationEntity) -> Boolean,
    ) {
        val current = get(locationId) ?: return
        if (isSameEndpoint(current)) setLastSeen(locationId, at)
    }

    /**
     * Replaces the pin only while the row still points at [url] and still holds the pin of
     * [expectedTrustRevision], so a confirmation dialog opened for `https://nas-a/dav/` cannot pin its
     * certificate onto a location since edited to another URL, nor replace one trusted after it was
     * opened. Works the same whether something was pinned before or not.
     *
     * @return the number of rows changed, 0 if the location is gone, points elsewhere or was retrusted
     */
    @Query(
        "UPDATE webdav_locations SET certificateDer = :certificateDer, " +
            "certificateFingerprint = :certificateFingerprint, trustRevision = trustRevision + 1, " +
            "updatedAt = :updatedAt " +
            "WHERE locationId = :locationId AND url = :url AND trustRevision = :expectedTrustRevision"
    )
    suspend fun retrust(
        locationId: Uuid,
        url: String,
        expectedTrustRevision: Int,
        certificateDer: ByteArray,
        certificateFingerprint: String,
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
        transform: (WebDavLocationEntity) -> WebDavLocationEntity,
    ): WebDavLocationEntity? {
        val current = get(locationId) ?: return null
        return transform(current).also { upsert(it) }
    }
}
