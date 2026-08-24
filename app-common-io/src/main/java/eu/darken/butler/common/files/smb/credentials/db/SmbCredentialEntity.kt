package eu.darken.butler.common.files.smb.credentials.db

import androidx.room.Entity
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Encrypted credential for one generation of one location. Lives in its own database file so it can
 * be excluded from backups without excluding the locations themselves.
 *
 * The generation is part of the key so writing a new one cannot destroy the one still referenced by
 * the location row: the credential is written first, and only a committed location row retires its
 * predecessor.
 */
@Entity(tableName = "smb_credentials", primaryKeys = ["locationId", "credentialVersion"])
data class SmbCredentialEntity(
    val locationId: Uuid,
    /** Must match the location row's generation, a mismatch means the credential is stale. */
    val credentialVersion: Int,
    val envelopeVersion: Int,
    val payloadVersion: Int,
    val keyAlias: String,
    val iv: ByteArray,
    val ciphertext: ByteArray,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SmbCredentialEntity) return false
        return locationId == other.locationId &&
            credentialVersion == other.credentialVersion &&
            envelopeVersion == other.envelopeVersion &&
            payloadVersion == other.payloadVersion &&
            keyAlias == other.keyAlias &&
            iv.contentEquals(other.iv) &&
            ciphertext.contentEquals(other.ciphertext) &&
            createdAt == other.createdAt &&
            updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = locationId.hashCode()
        result = 31 * result + credentialVersion
        result = 31 * result + envelopeVersion
        result = 31 * result + payloadVersion
        result = 31 * result + keyAlias.hashCode()
        result = 31 * result + iv.contentHashCode()
        result = 31 * result + ciphertext.contentHashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        return result
    }
}
