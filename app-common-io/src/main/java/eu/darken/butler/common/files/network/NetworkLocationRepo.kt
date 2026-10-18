package eu.darken.butler.common.files.network

import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.sftp.SftpConnectionPool
import eu.darken.butler.common.files.sftp.credentials.SftpCredentialStore
import eu.darken.butler.common.files.sftp.location.SftpLocationManager
import eu.darken.butler.common.files.smb.SmbConnectionPool
import eu.darken.butler.common.files.smb.credentials.SmbCredentialStore
import eu.darken.butler.common.files.smb.location.SmbLocationManager
import eu.darken.butler.common.files.webdav.WebDavClientPool
import eu.darken.butler.common.files.webdav.credentials.WebDavCredentialStore
import eu.darken.butler.common.files.webdav.location.WebDavLocationManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Every network location regardless of protocol, in the order they were added. */
@Singleton
class NetworkLocationRepo @Inject constructor(
    smbLocationManager: SmbLocationManager,
    sftpLocationManager: SftpLocationManager,
    webDavLocationManager: WebDavLocationManager,
    private val smbCredentialStore: SmbCredentialStore,
    private val sftpCredentialStore: SftpCredentialStore,
    private val webDavCredentialStore: WebDavCredentialStore,
    private val smbConnectionPool: SmbConnectionPool,
    private val sftpConnectionPool: SftpConnectionPool,
    private val webDavClientPool: WebDavClientPool,
) {

    val locations: Flow<List<NetworkLocation>> = combine(
        smbLocationManager.locations,
        sftpLocationManager.locations,
        webDavLocationManager.locations,
    ) { smb, sftp, webDav ->
        val all = smb.map { NetworkLocation.Smb(it) } +
            sftp.map { NetworkLocation.Sftp(it) } +
            webDav.map { NetworkLocation.WebDav(it) }
        all.sortedBy { it.createdAt }
    }

    fun credentialAvailability(location: NetworkLocation): Flow<NetworkCredentialAvailability> = when (location) {
        is NetworkLocation.Smb -> smbCredentialStore.availability(location.location).map {
            when (it) {
                SmbCredentialStore.Availability.AVAILABLE -> NetworkCredentialAvailability.AVAILABLE
                SmbCredentialStore.Availability.MISSING -> NetworkCredentialAvailability.MISSING
                SmbCredentialStore.Availability.KEY_UNAVAILABLE -> NetworkCredentialAvailability.KEY_UNAVAILABLE
            }
        }

        is NetworkLocation.Sftp -> sftpCredentialStore.availability(location.location).map {
            when (it) {
                SftpCredentialStore.Availability.AVAILABLE -> NetworkCredentialAvailability.AVAILABLE
                SftpCredentialStore.Availability.MISSING -> NetworkCredentialAvailability.MISSING
                SftpCredentialStore.Availability.KEY_UNAVAILABLE -> NetworkCredentialAvailability.KEY_UNAVAILABLE
            }
        }

        is NetworkLocation.WebDav -> webDavCredentialStore.availability(location.location).map {
            when (it) {
                WebDavCredentialStore.Availability.AVAILABLE -> NetworkCredentialAvailability.AVAILABLE
                WebDavCredentialStore.Availability.MISSING -> NetworkCredentialAvailability.MISSING
                WebDavCredentialStore.Availability.KEY_UNAVAILABLE -> NetworkCredentialAvailability.KEY_UNAVAILABLE
            }
        }
    }

    /**
     * Forgets the location's session-only credential and retires its sessions, the location itself
     * stays. Sessions still leased by running operations close once those finish.
     */
    suspend fun disconnect(location: NetworkLocation) {
        log(TAG) { "disconnect(${location.id})" }
        when (location) {
            is NetworkLocation.Smb -> {
                smbCredentialStore.endSession(location.id)
                smbConnectionPool.evict(location.id)
            }

            is NetworkLocation.Sftp -> {
                sftpCredentialStore.endSession(location.id)
                sftpConnectionPool.evict(location.id)
            }

            is NetworkLocation.WebDav -> {
                webDavCredentialStore.endSession(location.id)
                webDavClientPool.evict(location.id)
            }
        }
    }

    companion object {
        private val TAG = logTag("Network", "LocationRepo")
    }
}
