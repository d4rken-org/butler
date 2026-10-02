package eu.darken.butler.common.files.sftp.location.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The pinned host key lives in the same row as the endpoint, so a write can never leave a key
 * paired with a server it was not accepted for.
 */
@Entity(tableName = "sftp_locations")
data class SftpLocationEntity(
    @PrimaryKey
    val locationId: Uuid,
    val label: String?,
    val host: String,
    val port: Int,
    val username: String,
    /** Verbatim user input, empty for the server's initial directory. */
    val basePath: String,
    val authType: String,
    val rememberCredential: Boolean,
    val credentialVersion: Int,
    val hostKeyType: String,
    val hostKeyBlob: ByteArray,
    val hostKeyFingerprint: String,
    val trustRevision: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** When a probe last found this host's port answering, null if that never happened. */
    val lastSeenAt: Instant? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SftpLocationEntity) return false
        return locationId == other.locationId &&
            label == other.label &&
            host == other.host &&
            port == other.port &&
            username == other.username &&
            basePath == other.basePath &&
            authType == other.authType &&
            rememberCredential == other.rememberCredential &&
            credentialVersion == other.credentialVersion &&
            hostKeyType == other.hostKeyType &&
            hostKeyBlob.contentEquals(other.hostKeyBlob) &&
            hostKeyFingerprint == other.hostKeyFingerprint &&
            trustRevision == other.trustRevision &&
            createdAt == other.createdAt &&
            updatedAt == other.updatedAt &&
            lastSeenAt == other.lastSeenAt
    }

    override fun hashCode(): Int {
        var result = locationId.hashCode()
        result = 31 * result + (label?.hashCode() ?: 0)
        result = 31 * result + host.hashCode()
        result = 31 * result + port
        result = 31 * result + username.hashCode()
        result = 31 * result + basePath.hashCode()
        result = 31 * result + authType.hashCode()
        result = 31 * result + rememberCredential.hashCode()
        result = 31 * result + credentialVersion
        result = 31 * result + hostKeyType.hashCode()
        result = 31 * result + hostKeyBlob.contentHashCode()
        result = 31 * result + hostKeyFingerprint.hashCode()
        result = 31 * result + trustRevision
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + (lastSeenAt?.hashCode() ?: 0)
        return result
    }
}
