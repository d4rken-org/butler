package eu.darken.butler.common.files.smb.location.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Entity(tableName = "smb_locations")
data class SmbLocationEntity(
    @PrimaryKey
    val locationId: Uuid,
    val label: String?,
    val host: String,
    val port: Int,
    val share: String,
    /** Base directory below the share root, stored with `/` separators, empty for the share root. */
    val basePath: String,
    val domain: String?,
    val username: String?,
    val authType: String,
    val rememberCredential: Boolean,
    val credentialVersion: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** When a probe last found this host's port answering, null if that never happened. */
    val lastSeenAt: Instant? = null,
)
