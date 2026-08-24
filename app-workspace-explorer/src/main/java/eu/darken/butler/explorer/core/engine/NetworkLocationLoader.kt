package eu.darken.butler.explorer.core.engine

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Lan
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.smb.credentials.SmbCredentialStore
import eu.darken.butler.common.files.smb.location.SmbLocation
import eu.darken.butler.common.files.smb.location.SmbLocationManager
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.permissions.core.PathRequirements
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

/**
 * Lists the stored network locations.
 *
 * Deliberately does not probe reachability or capacity: opening the Network view would otherwise
 * connect to every stored server, which is slow, wakes NAS devices, and would burn a login attempt
 * per view. The row status comes from stored state and the credential vault alone, an unreachable
 * server is only reported once the user actually opens it.
 */
class NetworkLocationLoader @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    private val locationManager: SmbLocationManager,
    private val credentialStore: SmbCredentialStore,
) {

    private val tag = logTag("Explorer", "Workspace", workspaceId.shortTag, "NetworkLoader")

    fun loadNetwork(): Flow<ExplorerLocation> = flow {
        log(tag, INFO) { "loadNetwork(): Loading network locations" }

        val context = LocationLoaderContext(
            initialState = ExplorerLocation.Network(
                setupRequirements = PathRequirements(),
                progress = Progress.Data(
                    primary = R.string.explorer_loader_progress_network_loading.toCaString(),
                ),
            ),
            emit = ::emit,
        )
        context.emitState()

        val items = locationManager.locations.first().map { it.toItem() }
        log(tag, INFO) { "loadNetwork(): Found ${items.size} network locations" }

        context.updateState {
            copy(
                items = items,
                info = ExplorerLocation.Network.Info(locationCount = items.size),
                progress = null,
            )
        }
    }

    private suspend fun SmbLocation.toItem(): ExplorerItem.Storage.Network {
        val availability = credentialStore.availability(this).first()
        return ExplorerItem.Storage.Network(
            location = this,
            displayName = displayName,
            displayIcon = Icons.TwoTone.Lan,
            target = ExplorerNavigation.Target.Directory(rootPath),
            subtitle = endpointLabel.toCaString(),
            status = when (availability) {
                SmbCredentialStore.Availability.AVAILABLE -> ExplorerItem.Storage.Network.Status.AVAILABLE
                else -> ExplorerItem.Storage.Network.Status.SIGN_IN_REQUIRED
            },
        )
    }

    @AssistedFactory
    interface Factory {
        fun create(workspaceId: Workspace.Id): NetworkLocationLoader
    }
}
