package eu.darken.butler.explorer.core.engine

import android.os.Environment
import android.os.StatFs
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Code
import androidx.compose.material.icons.twotone.FolderShared
import androidx.compose.material.icons.twotone.Storage
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.saf.location.SAFLocationManager
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.workspace.core.permissions.PermissionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceLocationLoader @Inject constructor(
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

    fun loadDevice(): Flow<ExplorerLocation> = combine(
        flow { emit(Unit) },
        safLocationManager.getGrantedLocations(),
    ) { _, safLocations ->
        log(tag) { "loadDevice(): Loading device location with ${safLocations.size} SAF locations" }

        val staticLocations = listOf(
            ExplorerItem.Storage.Local(
                localId = "root",
                displayIcon = Icons.TwoTone.Code,
                displayName = R.string.explorer_navigation_root.toCaString(),
                target = ExplorerNavigation.Target.Directory(
                    LocalPath.build(Environment.getRootDirectory())
                ),
            ),
            ExplorerItem.Storage.Local(
                localId = "internal-public",
                displayIcon = Icons.TwoTone.Storage,
                displayName = R.string.explorer_navigation_internal_storage.toCaString(),
                target = ExplorerNavigation.Target.Directory(
                    LocalPath.build(Environment.getExternalStorageDirectory())
                ),
            ),
        )

        // Convert SAF locations to storage items
        val safStorage = safLocations.map { location ->
            ExplorerItem.Storage.SAF(
                location = location,
                displayIcon = Icons.TwoTone.FolderShared,
                displayName = location.displayName,
                target = ExplorerNavigation.Target.Directory(location.path),
            )
        }

        val allLocations = staticLocations + safStorage

        // Calculate combined storage info
        val stat = try {
            StatFs(Environment.getDataDirectory().path)
        } catch (e: Exception) {
            log(tag) { "loadDevice(): Failed to get storage info: ${e.message}" }
            null
        }

        val info = ExplorerLocation.Device.Info(
            locationCount = allLocations.size,
            totalCapacity = stat?.totalBytes,
            usedSpace = stat?.let { it.totalBytes - it.availableBytes },
        )

        log(tag) { "loadDevice(): Created device with ${allLocations.size} storage locations" }

        ExplorerLocation.Device(
            items = allLocations,
            info = info,
            permissionState = checkLocationPermissions(),
            progress = null,
        )
    }
}