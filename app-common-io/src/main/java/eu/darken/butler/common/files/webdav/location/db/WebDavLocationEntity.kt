package eu.darken.butler.common.files.webdav.location.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The pinned certificate lives in the same row as the URL, so a write can never leave a pin paired
 * with a server it was not accepted for. Both certificate columns are null when nothing is pinned.
 */
@Entity(tableName = "webdav_locations")
data class WebDavLocationEntity(
    @PrimaryKey
    val locationId: Uuid,
    val label: String?,
    /** The normalized collection URL, ending in `/`. */
    val url: String,
    val authType: String,
    val username: String?,
    val rememberCredential: Boolean,
    val credentialVersion: Int,
    val certificateDer: ByteArray?,
    val certificateFingerprint: String?,
    val trustRevision: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** When a probe last found this host's port answering, null if that never happened. */
    val lastSeenAt: Instant? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WebDavLocationEntity) return false
        return locationId == other.locationId &&
            label == other.label &&
            url == other.url &&
            authType == other.authType &&
            username == other.username &&
            rememberCredential == other.rememberCredential &&
            credentialVersion == other.credentialVersion &&
            certificateDer.contentEquals(other.certificateDer) &&
            certificateFingerprint == other.certificateFingerprint &&
            trustRevision == other.trustRevision &&
            createdAt == other.createdAt &&
            updatedAt == other.updatedAt &&
            lastSeenAt == other.lastSeenAt
    }

    override fun hashCode(): Int {
        var result = locationId.hashCode()
        result = 31 * result + (label?.hashCode() ?: 0)
        result = 31 * result + url.hashCode()
        result = 31 * result + authType.hashCode()
        result = 31 * result + (username?.hashCode() ?: 0)
        result = 31 * result + rememberCredential.hashCode()
        result = 31 * result + credentialVersion
        result = 31 * result + certificateDer.contentHashCode()
        result = 31 * result + (certificateFingerprint?.hashCode() ?: 0)
        result = 31 * result + trustRevision
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + (lastSeenAt?.hashCode() ?: 0)
        return result
    }
}
