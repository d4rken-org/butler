package eu.darken.butler.common.files.network

import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.files.sftp.location.SftpLocationManager
import eu.darken.butler.common.files.smb.location.SmbLocationManager
import eu.darken.butler.common.files.webdav.location.WebDavLocationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/** Feeds [NetworkLocationNames] from the stored locations, so renames and removals reach it too. */
@Singleton
class NetworkLocationNamesUpdater @Inject constructor(
    @AppScope appScope: CoroutineScope,
    smbLocationManager: SmbLocationManager,
    sftpLocationManager: SftpLocationManager,
    webDavLocationManager: WebDavLocationManager,
) {

    init {
        smbLocationManager.locations
            .onEach { NetworkLocationNames.updateSmb(it) }
            .launchIn(appScope)

        sftpLocationManager.locations
            .onEach { NetworkLocationNames.updateSftp(it) }
            .launchIn(appScope)

        webDavLocationManager.locations
            .onEach { NetworkLocationNames.updateWebDav(it) }
            .launchIn(appScope)
    }
}
