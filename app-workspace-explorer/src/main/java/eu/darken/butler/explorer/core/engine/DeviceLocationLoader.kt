package eu.darken.butler.explorer.core.engine

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Code
import androidx.compose.material.icons.twotone.DeveloperMode
import androidx.compose.material.icons.twotone.FolderShared
import androidx.compose.material.icons.twotone.PrivacyTip
import androidx.compose.material.icons.twotone.Public
import androidx.compose.material.icons.twotone.SdCard
import androidx.compose.material.icons.twotone.SdStorage
import androidx.compose.material.icons.twotone.Storage
import androidx.compose.material.icons.twotone.Usb
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.adb.AdbManager
import eu.darken.butler.common.adb.canUseAdbNow
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.extensions.getFileSystemInfo
import eu.darken.butler.common.files.saf.location.SAFLocationManager
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.common.root.RootManager
import eu.darken.butler.common.root.canUseRootNow
import eu.darken.butler.common.storage.StorageEnvironment
import eu.darken.butler.common.storage.StorageManager2
import eu.darken.butler.common.storage.saf.StorageProviderSuggester
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.permissions.core.PathRequirements
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

class DeviceLocationLoader @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    @ApplicationContext private val context: Context,
    private val storageEnvironment: StorageEnvironment,
    private val gatewaySwitch: GatewaySwitch,
    private val storageManager2: StorageManager2,
    private val safLocationManager: SAFLocationManager,
    private val storageProviderSuggester: StorageProviderSuggester,
    private val rootManager: RootManager,
    private val adbManager: AdbManager,
) {

    private val tag = logTag("Explorer", "Workspace", workspaceId.shortTag, "DeviceLoader")

    private suspend fun checkLocationRequirements(): PathRequirements {
        log(tag) { "checkLocationRequirements(): Checking requirements for Device" }
        return PathRequirements()
    }

    fun loadDevice(): Flow<ExplorerLocation> = flow {
        log(tag, INFO) { "loadDevice(): Loading device location with multi-stage loading" }

        val setupRequirements = checkLocationRequirements()
        val context = LocationLoaderContext(
            initialState = ExplorerLocation.Device(
                setupRequirements = setupRequirements,
                progress = Progress.Data(
                    primary = R.string.explorer_loader_progress_device_loading.toCaString(),
                ),
            ),
            emit = ::emit
        )
        context.emitState()

        gatewaySwitch.useRes {
            context.loadQuickList()
            context.updateState { copy(progress = null) }
            log(tag, INFO) { "loadDevice(): Stage 1 complete with ${context.state.items?.size} storage locations" }

            context.loadFilesystemInfo()
        }

        log(tag, INFO) { "loadDevice(): Stage 2 complete with filesystem info" }
    }

    private suspend fun LocationLoaderContext<ExplorerLocation.Device>.loadQuickList() {
        log(tag) { "loadQuickList(): Loading storage list without filesystem info" }
        updateProgressMsg(R.string.explorer_loader_progress_device_locations)

        // Get current SAF locations
        val safLocations = safLocationManager.locations.first()
        log(tag) { "loadQuickList(): Found ${safLocations.size} SAF locations" }

        // Build local storage list (root + volumes)
        val deviceItems = mutableListOf<ExplorerItem>()

        val hasRoot = rootManager.canUseRootNow()
        val hasAdb = adbManager.canUseAdbNow()

        if (hasRoot || hasAdb) {
            ExplorerItem.Storage.Local(
                localId = "root",
                displayIcon = Icons.TwoTone.Code,
                displayName = R.string.explorer_navigation_root.toCaString(),
                target = ExplorerNavigation.Target.Directory(LocalPath.build("/")),
                canWrite = true, // Has elevated access
            ).run { deviceItems.add(this) }
        }

        ExplorerItem.Storage.Local(
            localId = "rom",
            displayIcon = Icons.TwoTone.DeveloperMode,
            displayName = R.string.explorer_navigation_rom.toCaString(),
            target = ExplorerNavigation.Target.Directory(LocalPath.build("/system")),
            canWrite = false,
        ).run { deviceItems.add(this) }

        // Get internal volume info for disk descriptions (manufacturer names)
        val volumeInfos = storageManager2.volumes

        storageManager2.storageVolumes
            .mapIndexedNotNull { index, volume ->
                log(tag) { "loadQuickList(): Adding volume: $volume" }
                val path = volume.directory?.let { LocalPath.build(it) }
                    ?: volume.path?.let { LocalPath.build(it) }
                    ?: return@mapIndexedNotNull null

                // Find matching VolumeInfoX to get disk label (manufacturer name)
                val volumeInfo = volumeInfos?.firstOrNull { info ->
                    info.fsUuid == volume.uuid || info.path?.path == volume.path
                }

                val disk = volumeInfo?.disk

                ExplorerItem.Storage.Local(
                    localId = "volume-${volume.uuid}",
                    displayIcon = when {
                        index == 0 -> Icons.TwoTone.Storage
                        disk?.isUsb == true -> Icons.TwoTone.Usb
                        disk?.isSd == true -> Icons.TwoTone.SdCard
                        else -> Icons.TwoTone.SdStorage
                    },
                    displayName = caString { c ->
                        val volumeLabel = volume.userLabel?.takeIf { it.isNotBlank() }
                        val volumeDescription = volume.getDescription(c)?.takeIf { it.isNotBlank() }
                        val diskLabel = volumeInfo?.disk?.label?.takeIf { it.isNotBlank() }
                        val fallbackDesc = when (index) {
                            0 -> R.string.explorer_navigation_internal_storage
                            else -> R.string.explorer_navigation_external_storage
                        }.let { c.getString(it) }

                        val label = when {
                            volumeLabel != null -> volumeLabel
                            volumeDescription != null -> volumeDescription
                            diskLabel != null -> diskLabel
                            else -> fallbackDesc
                        }

                        val extra = when {
                            label != diskLabel -> diskLabel
                            label != fallbackDesc -> fallbackDesc
                            else -> null
                        }

                        "$label${if (extra != null) " ($extra)" else ""}"
                    },
                    target = ExplorerNavigation.Target.Directory(path),
                    canWrite = true, // User storage is writable
                )
            }
            .forEach { deviceItems.add(it) }

        // Convert SAF locations to storage items (without filesystem info)
        val safStorage = safLocations.map { location ->
            val providerApp = location.treeUri.authority?.let { storageProviderSuggester.appForAuthority(it) }
            ExplorerItem.Storage.SAF(
                location = location,
                displayIcon = Icons.TwoTone.FolderShared,
                displayName = location.displayName,
                target = ExplorerNavigation.Target.Directory(location.path),
                totalBytes = null,
                availableBytes = null,
                providerApp = providerApp,
            )
        }

        val devItems = buildList {
            if (BuildConfigWrap.BUILD_TYPE == BuildConfigWrap.BuildType.DEV) {
                storageEnvironment.ourPrivateDirs.forEachIndexed { index, path ->
                    ExplorerItem.Storage.Local(
                        localId = "butler-${path}",
                        displayIcon = Icons.TwoTone.PrivacyTip,
                        displayName = "Butler-Private #$index".toCaString(),
                        target = ExplorerNavigation.Target.Directory(path),
                        canWrite = true, // App's own directories
                    ).run { add(this) }
                }
                storageEnvironment.ourPublicDirs.forEachIndexed { index, path ->
                    ExplorerItem.Storage.Local(
                        localId = "butler-${path}",
                        displayIcon = Icons.TwoTone.Public,
                        displayName = "Butler-Public #$index".toCaString(),
                        target = ExplorerNavigation.Target.Directory(path),
                        canWrite = true, // App's own directories
                    ).run { add(this) }
                }
            }
        }

        val allLocations = deviceItems + safStorage + devItems
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

    private suspend fun LocationLoaderContext<ExplorerLocation.Device>.loadFilesystemInfo() {
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

    @AssistedFactory
    interface Factory {
        fun create(workspaceId: Workspace.Id): DeviceLocationLoader
    }
}
