package eu.darken.butler.explorer.core.engine

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Code
import androidx.compose.material.icons.twotone.FolderShared
import androidx.compose.material.icons.twotone.SdCard
import androidx.compose.material.icons.twotone.Storage
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.extensions.getFileSystemInfo
import eu.darken.butler.common.files.saf.location.SAFLocationManager
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.common.storage.StorageEnvironment
import eu.darken.butler.common.storage.StorageManager2
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.workspace.core.permissions.PermissionState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceLocationLoader @Inject constructor(
    private val gatewaySwitch: GatewaySwitch,
    private val storageEnvironment: StorageEnvironment,
    private val storageManager2: StorageManager2,
    private val safLocationManager: SAFLocationManager,
) {

    private val tag = logTag("Explorer", "DeviceLocationLoader")

    private class LoaderContext(
        private val permissionState: PermissionState,
        private val emit: suspend (ExplorerLocation.Device) -> Unit,
    ) {
        private var currentState = ExplorerLocation.Device(
            permissionState = permissionState,
            progress = Progress.Data(
                primary = R.string.explorer_loader_progress_device_loading.toCaString(),
            ),
        )
        val state: ExplorerLocation.Device get() = currentState

        suspend fun updateState(transform: ExplorerLocation.Device.() -> ExplorerLocation.Device) {
            currentState = currentState.transform()
            emit(currentState)
        }

        suspend fun updateProgressMsg(@StringRes msg: Int) = updateState {
            copy(
                progress = currentState.progress!!.copy(
                    secondary = msg.toCaString(),
                ),
            )
        }

        suspend fun emitState() {
            emit(currentState)
        }
    }

    private suspend fun checkLocationPermissions(): PermissionState {
        log(tag) { "checkLocationPermissions(): Checking permissions for Device" }

        return PermissionState(
            requirements = emptyList(),
            hasSufficientPermissions = true,
            missingCritical = emptyList(),
        )
    }

    fun loadDevice(): Flow<ExplorerLocation> = flow {
        log(tag, INFO) { "loadDevice(): Loading device location with multi-stage loading" }

        val permissionState = checkLocationPermissions()
        val context = LoaderContext(permissionState, ::emit)
        context.emitState()

        gatewaySwitch.useRes {
            context.loadQuickList()
            context.updateState { copy(progress = null) }
            log(tag, INFO) { "loadDevice(): Stage 1 complete with ${context.state.items?.size} storage locations" }

            context.loadFilesystemInfo()
        }

        log(tag, INFO) { "loadDevice(): Stage 2 complete with filesystem info" }
    }

    private suspend fun LoaderContext.loadQuickList() {
        log(tag) { "loadQuickList(): Loading storage list without filesystem info" }
        updateProgressMsg(R.string.explorer_loader_progress_device_locations)

        // Get current SAF locations
        val safLocations = safLocationManager.locations.first()
        log(tag) { "loadQuickList(): Found ${safLocations.size} SAF locations" }

        // Build local storage list (root + volumes)
        val localStorage = mutableListOf(
            ExplorerItem.Storage.Local(
                localId = "root",
                displayIcon = Icons.TwoTone.Code,
                displayName = R.string.explorer_navigation_root.toCaString(),
                target = ExplorerNavigation.Target.Directory(LocalPath.build("/")),
                totalBytes = null,
                availableBytes = null,
            ),
        )

        storageManager2.storageVolumes
            .mapIndexedNotNull { index, volume ->
                log(tag) { "loadQuickList(): Adding volume: $volume" }
                val path = volume.directory?.let { LocalPath.build(it) }
                    ?: volume.path?.let { LocalPath.build(it) }
                    ?: return@mapIndexedNotNull null

                ExplorerItem.Storage.Local(
                    localId = "volume-${volume.uuid}",
                    displayIcon = when (index) {
                        0 -> Icons.TwoTone.Storage
                        else -> Icons.TwoTone.SdCard
                    },
                    displayName = volume.userLabel
                        ?.takeIf { it.isNotBlank() }
                        ?.toCaString()
                        ?: when (index) {
                            0 -> R.string.explorer_navigation_internal_storage.toCaString()
                            else -> R.string.explorer_navigation_external_storage.toCaString()
                        },
                    target = ExplorerNavigation.Target.Directory(path),
                    totalBytes = null,
                    availableBytes = null,
                )
            }
            .forEach { localStorage.add(it) }

        // Convert SAF locations to storage items (without filesystem info)
        val safStorage = safLocations.map { location ->
            ExplorerItem.Storage.SAF(
                location = location,
                displayIcon = Icons.TwoTone.FolderShared,
                displayName = location.displayName,
                target = ExplorerNavigation.Target.Directory(location.path),
                totalBytes = null,
                availableBytes = null,
            )
        }

        val allLocations = localStorage + safStorage
        log(tag) { "loadQuickList(): Created quick list with ${allLocations.size} storage locations" }

        updateState {
            copy(
                items = allLocations,
                info = ExplorerLocation.Device.Info(
                    locationCount = allLocations.size,
                    totalCapacity = null,
                    usedSpace = null,
                ),
            )
        }
    }

    private suspend fun fetchFilesystemInfo(item: ExplorerItem.Storage): Pair<Long?, Long?>? = try {
        val fsInfo = item.target.path.getFileSystemInfo(gatewaySwitch)
        fsInfo.totalSpace to fsInfo.freeSpace
    } catch (e: Exception) {
        log(tag, WARN) { "Failed to get filesystem info for ${item.displayName}: ${e.message}" }
        null
    }

    private suspend fun LoaderContext.loadFilesystemInfo() {
        log(tag) { "loadFilesystemInfo(): Loading filesystem info sequentially with incremental updates" }

        val currentItems = state.items ?: return

        // Process each storage item sequentially with cancellation checks
        currentItems.forEachIndexed { index, item ->
            // Check if cancelled before processing next item
            currentCoroutineContext().ensureActive()

            log(tag) { "loadFilesystemInfo(): Processing item ${index + 1}/${currentItems.size}: ${item.javaClass.simpleName}" }

            val updatedItem = when (item) {
                is ExplorerItem.Storage.Local -> {
                    val (totalBytes, availableBytes) = fetchFilesystemInfo(item) ?: (null to null)
                    item.copy(
                        totalBytes = totalBytes,
                        availableBytes = availableBytes,
                    )
                }
                is ExplorerItem.Storage.SAF -> {
                    val (totalBytes, availableBytes) = fetchFilesystemInfo(item) ?: (null to null)
                    item.copy(
                        totalBytes = totalBytes,
                        availableBytes = availableBytes,
                    )
                }
                else -> item
            }

            // Update state immediately after each item completes
            updateState {
                val updatedItems = items?.mapIndexed { idx, it ->
                    if (idx == index) updatedItem else it
                }

                // Recalculate device-level totals with current data
                val totalCapacity = updatedItems
                    ?.filterIsInstance<ExplorerItem.Storage>()
                    ?.mapNotNull { it.totalBytes }
                    ?.takeIf { it.isNotEmpty() }
                    ?.sum()

                val availableSpace = updatedItems
                    ?.filterIsInstance<ExplorerItem.Storage>()
                    ?.mapNotNull { it.availableBytes }
                    ?.takeIf { it.isNotEmpty() }
                    ?.sum()

                val usedSpace = if (totalCapacity != null && availableSpace != null) {
                    totalCapacity - availableSpace
                } else null

                copy(
                    items = updatedItems,
                    info = ExplorerLocation.Device.Info(
                        locationCount = updatedItems?.size ?: 0,
                        totalCapacity = totalCapacity,
                        usedSpace = usedSpace,
                    ),
                )
            }

            log(tag) { "loadFilesystemInfo(): Updated item ${index + 1}/${currentItems.size}" }
        }

        log(tag) { "loadFilesystemInfo(): Completed updating ${currentItems.size} items with filesystem info" }
    }
}
