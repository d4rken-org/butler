package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.common.debug.logging.Logging.Priority.ERROR
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.files.network.NetworkLocation
import eu.darken.butler.common.files.sftp.location.SftpLocationManager
import eu.darken.butler.common.files.smb.location.SmbLocationManager
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.ExplorerWorkspace
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState
import kotlinx.coroutines.CoroutineScope

/**
 * What network locations of any protocol share: choosing the protocol of a new one, and removing
 * them with one confirmation for a mixed selection.
 */
class ExplorerNetworkLocationController(
    private val smbLocationManager: SmbLocationManager,
    private val sftpLocationManager: SftpLocationManager,
    private val dialogs: ExplorerDialogController,
    private val workspace: suspend () -> ExplorerWorkspace,
    private val currentLocation: () -> ExplorerLocation?,
    private val clearSelection: () -> Unit,
    private val onError: (Throwable) -> Unit,
    private val doLaunch: (suspend CoroutineScope.() -> Unit) -> Unit,
    private val tag: String,
) {

    fun showAddChooser() {
        log(tag) { "showAddChooser()" }
        dialogs.show(ExplorerDialogState.NetworkProtocolChooser)
    }

    fun showRemoveConfirmation(items: List<ExplorerItem.Storage.Network>) {
        log(tag) { "showRemoveConfirmation(${items.size} items)" }
        dialogs.show(ExplorerDialogState.RemoveLocationConfirmation(items))
    }

    fun onRemoveConfirmed(items: List<ExplorerItem.Storage.Network>) = doLaunch {
        log(tag) { "onRemoveConfirmed(${items.size} items)" }
        dialogs.dismiss()
        try {
            items.forEach { item ->
                when (val location = item.location) {
                    is NetworkLocation.Smb -> smbLocationManager.delete(location.id)
                    is NetworkLocation.Sftp -> sftpLocationManager.delete(location.id)
                }
            }
        } catch (e: Exception) {
            log(tag, ERROR) { "onRemoveConfirmed(): Failed: ${e.asLog()}" }
            onError(e)
        }
        clearSelection()
        refreshUnlessLive()
    }

    /** The Network list re-reads itself from the location stores, a reload would only re-probe. */
    private suspend fun refreshUnlessLive() {
        if (currentLocation() is ExplorerLocation.Network) {
            log(tag) { "Network list is live, no refresh needed" }
            return
        }
        workspace().navigate(ExplorerNavigation.Refresh)
    }
}
