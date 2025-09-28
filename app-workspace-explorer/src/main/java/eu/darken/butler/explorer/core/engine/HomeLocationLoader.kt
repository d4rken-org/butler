package eu.darken.butler.explorer.core.engine

import android.content.Context
import android.os.Environment
import android.os.StatFs
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.PhoneAndroid
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.workspace.core.permissions.PermissionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HomeLocationLoader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val tag = logTag("Explorer", "HomeLocationLoader")

    private suspend fun checkLocationPermissions(): PermissionState {
        log(tag) { "checkLocationPermissions(): Checking permissions for Home" }

        return PermissionState(
            requirements = emptyList(),
            hasSufficientPermissions = true,
            missingCritical = emptyList(),
        )
    }

    fun loadHome(): Flow<ExplorerLocation> = flow {
        log(tag) { "loadHome(): Loading home location" }

        var result = ExplorerLocation.Home()
        emit(result)

        val shortcuts = listOf(
            ExplorerItem.Shortcut(
                shortcutId = "device",
                displayIcon = Icons.TwoTone.PhoneAndroid,
                displayName = R.string.explorer_nav_device.toCaString(),
                target = ExplorerNavigation.Target.Device,
            ),
        )

        // Calculate device storage info
        val stat = try {
            StatFs(Environment.getDataDirectory().path)
        } catch (e: Exception) {
            log(tag) { "loadHome(): Failed to get storage info: ${e.message}" }
            null
        }

        val info = ExplorerLocation.Home.Info(
            shortcutCount = shortcuts.size,
            totalDeviceStorage = stat?.totalBytes,
            usedStorage = stat?.let { it.totalBytes - it.availableBytes },
        )

        log(tag) { "loadHome(): Created home with ${shortcuts.size} shortcuts" }

        result = ExplorerLocation.Home(
            items = shortcuts,
            info = info,
            permissionState = checkLocationPermissions(),
            progress = null,
        )
        emit(result)

    }
}