package eu.darken.butler.common.files.network

import eu.darken.butler.common.files.sftp.credentials.SftpCredentialStore
import eu.darken.butler.common.files.sftp.location.SftpLocationManager
import eu.darken.butler.common.files.smb.credentials.SmbCredentialStore
import eu.darken.butler.common.files.smb.location.SmbLocationManager
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
    private val smbCredentialStore: SmbCredentialStore,
    private val sftpCredentialStore: SftpCredentialStore,
) {

    val locations: Flow<List<NetworkLocation>> = combine(
        smbLocationManager.locations,
        sftpLocationManager.locations,
    ) { smb, sftp ->
        (smb.map { NetworkLocation.Smb(it) } + sftp.map { NetworkLocation.Sftp(it) }).sortedBy { it.createdAt }
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
    }
}
