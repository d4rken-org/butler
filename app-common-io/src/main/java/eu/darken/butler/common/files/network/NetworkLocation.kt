package eu.darken.butler.common.files.network

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.files.sftp.location.SftpLocation
import eu.darken.butler.common.files.smb.location.SmbLocation
import eu.darken.butler.common.files.webdav.location.WebDavLocation
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

    /** Whether it signs in with a secret that is only held in memory, not remembered. */
    val usesSessionCredential: Boolean

    data class Smb(val location: SmbLocation) : NetworkLocation {
        override val id: Uuid get() = location.id
        override val displayName: CaString get() = location.displayName
        override val endpointLabel: String get() = location.endpointLabel
        override val host: String get() = location.host
        override val port: Int get() = location.port
        override val createdAt: Instant get() = location.createdAt
        override val lastSeenAt: Instant? get() = location.lastSeenAt
        override val usesSessionCredential: Boolean
            get() = location.authType == SmbLocation.AuthType.PASSWORD && !location.rememberCredential
    }

    data class Sftp(val location: SftpLocation) : NetworkLocation {
        override val id: Uuid get() = location.id
        override val displayName: CaString get() = location.displayName
        override val endpointLabel: String get() = location.endpointLabel
        override val host: String get() = location.host
        override val port: Int get() = location.port
        override val createdAt: Instant get() = location.createdAt
        override val lastSeenAt: Instant? get() = location.lastSeenAt
        override val usesSessionCredential: Boolean get() = !location.rememberCredential
    }

    data class WebDav(val location: WebDavLocation) : NetworkLocation {
        override val id: Uuid get() = location.id
        override val displayName: CaString get() = location.displayName
        override val endpointLabel: String get() = location.endpointLabel
        override val host: String get() = location.host
        override val port: Int get() = location.port
        override val createdAt: Instant get() = location.createdAt
        override val lastSeenAt: Instant? get() = location.lastSeenAt
        override val usesSessionCredential: Boolean
            get() = location.authType == WebDavLocation.AuthType.BASIC && !location.rememberCredential
    }
}
