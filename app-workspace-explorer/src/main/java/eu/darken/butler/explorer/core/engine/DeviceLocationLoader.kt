package eu.darken.butler.explorer.core.engine

import android.content.Context
import android.os.Environment
import android.os.StatFs
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Code
import androidx.compose.material.icons.twotone.Storage
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.workspace.core.permissions.PermissionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceLocationLoader @Inject constructor(
    @ApplicationContext private val context: Context,
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

    fun loadDevice(): Flow<ExplorerLocation> = flow {
        log(tag) { "loadDevice(): Loading device location" }

        var result = ExplorerLocation.Device()
        emit(result)


        val storageLocations = listOf(
            ExplorerItem.Shortcut(
                shortcutId = "device-root",
                displayIcon = Icons.TwoTone.Code,
                displayName = R.string.explorer_nav_root.toCaString(),
                target = ExplorerNavigation.Target.Directory(
                    LocalPath.build(Environment.getRootDirectory())
                ),
            ),
            ExplorerItem.Shortcut(
                shortcutId = "device-internal-public",
                displayIcon = Icons.TwoTone.Storage,
                displayName = R.string.explorer_nav_internal_storage.toCaString(),
                target = ExplorerNavigation.Target.Directory(
                    LocalPath.build(Environment.getExternalStorageDirectory())
                ),
            ),
        )

        // Calculate combined storage info
        val stat = try {
            StatFs(Environment.getDataDirectory().path)
        } catch (e: Exception) {
            log(tag) { "loadDevice(): Failed to get storage info: ${e.message}" }
            null
        }

        val info = ExplorerLocation.Device.Info(
            locationCount = storageLocations.size,
            totalCapacity = stat?.totalBytes,
            usedSpace = stat?.let { it.totalBytes - it.availableBytes },
        )

        log(tag) { "loadDevice(): Created device with ${storageLocations.size} storage locations" }

        result = ExplorerLocation.Device(
            items = storageLocations,
            info = info,
            permissionState = checkLocationPermissions(),
            progress = null,
        )

        emit(result)
    }
}