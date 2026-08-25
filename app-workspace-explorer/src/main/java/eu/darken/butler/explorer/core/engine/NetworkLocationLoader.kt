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
import eu.darken.butler.common.files.smb.SmbEndpointProbe
import eu.darken.butler.common.files.smb.SmbEndpointState
import eu.darken.butler.common.files.smb.credentials.SmbCredentialStore
import eu.darken.butler.common.files.smb.location.SmbLocation
import eu.darken.butler.common.files.smb.location.SmbLocationManager
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.permissions.core.PathRequirements
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach

/**
 * Lists the stored network locations and keeps their reachability up to date.
 *
 * What is probed is where a server is (DNS) and whether its port answers (a TCP connect that is
 * closed again). What is not probed is the share itself: no listing, no capacity, no credentials.
 * A connect performs no SMB negotiation and no authentication, so drawing this view costs no login
 * attempt on any server.
 *
 * The returned flow does not complete. Rows are emitted as soon as the locations are known, every
 * one of them still "checking", and each probe result arrives as another emission - nothing in the
 * load path waits for a server to answer.
 */
class NetworkLocationLoader @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    private val locationManager: SmbLocationManager,
    private val credentialStore: SmbCredentialStore,
    private val endpointProbe: SmbEndpointProbe,
) {

    private val tag = logTag("Explorer", "Workspace", workspaceId.shortTag, "NetworkLoader")

    /** @param force re-probes every endpoint instead of reusing a recent result, for a user refresh */
    fun loadNetwork(force: Boolean = false): Flow<ExplorerLocation> = flow {
        log(tag, INFO) { "loadNetwork(force=$force): Loading network locations" }

        emit(
            ExplorerLocation.Network(
                setupRequirements = PathRequirements(),
                progress = Progress.Data(
                    primary = R.string.explorer_loader_progress_network_loading.toCaString(),
                ),
            )
        )

        // Only the pass this load starts with may skip the cache, a later change of the stored
        // locations must not re-probe every server again.
        var isFirstPass = true

        emitAll(
            combine(
                locationManager.locations.onEach { stored ->
                    endpointProbe.probe(stored, force = force && isFirstPass)
                    isFirstPass = false
                },
                endpointProbe.states,
            ) { stored, endpoints ->
                log(tag, INFO) { "loadNetwork(): ${stored.size} network locations" }
                val items = stored.map { it.toItem(endpoints[it.id] ?: SmbEndpointState()) }
                ExplorerLocation.Network(
                    items = items,
                    info = ExplorerLocation.Network.Info(locationCount = items.size),
                    setupRequirements = PathRequirements(),
                    progress = null,
                )
            }
        )
    }

    private suspend fun SmbLocation.toItem(endpoint: SmbEndpointState): ExplorerItem.Storage.Network {
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
            endpoint = endpoint,
        )
    }

    @AssistedFactory
    interface Factory {
        fun create(workspaceId: Workspace.Id): NetworkLocationLoader
    }
}
