package eu.darken.butler.common.files.network

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.files.sftp.location.SftpLocation
import eu.darken.butler.common.files.smb.location.SmbLocation
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A manually added network location of any protocol, as listed next to each other. */
sealed interface NetworkLocation {
    val id: Uuid
    val displayName: CaString
    val endpointLabel: String
    val host: String
    val port: Int
    val createdAt: Instant
    val lastSeenAt: Instant?

    data class Smb(val location: SmbLocation) : NetworkLocation {
        override val id: Uuid get() = location.id
        override val displayName: CaString get() = location.displayName
        override val endpointLabel: String get() = location.endpointLabel
        override val host: String get() = location.host
        override val port: Int get() = location.port
        override val createdAt: Instant get() = location.createdAt
        override val lastSeenAt: Instant? get() = location.lastSeenAt
    }

    data class Sftp(val location: SftpLocation) : NetworkLocation {
        override val id: Uuid get() = location.id
        override val displayName: CaString get() = location.displayName
        override val endpointLabel: String get() = location.endpointLabel
        override val host: String get() = location.host
        override val port: Int get() = location.port
        override val createdAt: Instant get() = location.createdAt
        override val lastSeenAt: Instant? get() = location.lastSeenAt
    }
}
