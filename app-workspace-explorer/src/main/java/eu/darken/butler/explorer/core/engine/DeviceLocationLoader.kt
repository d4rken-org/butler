package eu.darken.butler.explorer.core.engine

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Code
import androidx.compose.material.icons.twotone.FolderShared
import androidx.compose.material.icons.twotone.SdCard
import androidx.compose.material.icons.twotone.Storage
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.saf.location.SAFLocationManager
import eu.darken.butler.common.storage.StorageEnvironment
import eu.darken.butler.common.storage.StorageManager2
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.workspace.core.permissions.PermissionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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

    private suspend fun checkLocationPermissions(): PermissionState {
        log(tag) { "checkLocationPermissions(): Checking permissions for Device" }

        return PermissionState(
            requirements = emptyList(),
            hasSufficientPermissions = true,
            missingCritical = emptyList(),
        )
    }

    fun loadDevice(): Flow<ExplorerLocation> = safLocationManager.locations
        .map { safLocations ->
            log(tag) { "loadDevice(): Loading device location with ${safLocations.size} SAF locations" }

            val forStorageCals = mutableListOf<APath<*>>()

            val localStorage = mutableListOf(
                ExplorerItem.Storage.Local(
                    localId = "root",
                    displayIcon = Icons.TwoTone.Code,
                    displayName = R.string.explorer_navigation_root.toCaString(),
                    target = ExplorerNavigation.Target.Directory(
                        LocalPath.build("/")
                    ),
                ),
            )

            storageManager2.storageVolumes
                .mapIndexedNotNull { index, volume ->
                    log(TAG) { "Loading volume: $volume" }
                    val path = volume.directory?.let { LocalPath.build(it) }
                        ?: volume.path?.let { LocalPath.build(it) }
                        ?: return@mapIndexedNotNull null

                    forStorageCals.add(path)

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
                    )
                }
                .forEach { localStorage.add(it) }

            // Convert SAF locations to storage items
            val safStorage = safLocations.map { location ->
                ExplorerItem.Storage.SAF(
                    location = location,
                    displayIcon = Icons.TwoTone.FolderShared,
                    displayName = location.displayName,
                    target = ExplorerNavigation.Target.Directory(location.path),
                )
            }

            val allLocations = localStorage + safStorage

            val fileSystemInfos = forStorageCals
                .map { gatewaySwitch.getFileSystem(it) }

            val total = fileSystemInfos.mapNotNull { it.totalSpace }.sum()
            val free = fileSystemInfos.mapNotNull { it.freeSpace }.sum()
            val info = ExplorerLocation.Device.Info(
                locationCount = allLocations.size,
                totalCapacity = total,
                usedSpace = total - free,
            )

            log(tag) { "loadDevice(): Created device with ${allLocations.size} storage locations" }

            ExplorerLocation.Device(
                items = allLocations,
                info = info,
                permissionState = checkLocationPermissions(),
                progress = null,
            )
        }

    companion object {
        val TAG = logTag("Explorer", "DeviceLocationLoader")
    }
}
