package eu.darken.butler.explorer.core.engine

import android.content.Context
import android.os.Environment
import android.os.StatFs
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Code
import androidx.compose.material.icons.twotone.PhoneAndroid
import androidx.compose.material.icons.twotone.Storage
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.permissions.PathPermissionCheck
import eu.darken.butler.workspace.core.permissions.PermissionState
import eu.darken.butler.workspace.core.permissions.check
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

class BrowsingEngine @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    @ApplicationContext private val context: Context,
    private val gatewaySwitch: GatewaySwitch,
    private val pathPermissionCheck: PathPermissionCheck,
) {

    private val tag = logTag("Explorer", "Workspace", workspaceId.shortTag, "BrowsingEngine")

    private suspend fun checkLocationPermissions(target: ExplorerNavigation.Target): PermissionState {
        log(tag) { "checkLocationPermissions(): Checking permissions for $target" }

        return when (target) {
            is ExplorerNavigation.Target.Home,
            is ExplorerNavigation.Target.Device -> {
                // Home and Device views don't require permissions
                PermissionState(
                    requirements = emptyList(),
                    hasSufficientPermissions = true,
                    missingCritical = emptyList(),
                )
            }
            is ExplorerNavigation.Target.Directory -> {
                pathPermissionCheck.check(target.path)
            }
        }
    }


    private suspend fun getHomeEntry(): ExplorerLocation = withContext(Dispatchers.IO) {
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
            null
        }

        val info = ExplorerLocation.Home.Info(
            shortcutCount = shortcuts.size,
            totalDeviceStorage = stat?.totalBytes,
            usedStorage = stat?.let { it.totalBytes - it.availableBytes },
        )

        ExplorerLocation.Home(
            items = shortcuts,
            info = info,
            permissionState = checkLocationPermissions(ExplorerNavigation.Target.Home),
        )
    }

    private suspend fun getDevice(): ExplorerLocation = withContext(Dispatchers.IO) {
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
            null
        }

        val info = ExplorerLocation.Device.Info(
            storageCount = storageLocations.size,
            totalCapacity = stat?.totalBytes,
            usedSpace = stat?.let { it.totalBytes - it.availableBytes },
        )

        ExplorerLocation.Device(
            items = storageLocations,
            info = info,
            permissionState = checkLocationPermissions(ExplorerNavigation.Target.Device),
        )
    }

    private suspend fun getPeek(
        current: ExplorerLocation.Directory
    ): ExplorerLocation.Directory = withContext(Dispatchers.IO) {
        log(tag) { "getPeek(): Loading peek for  ${current.path}" }

        val volumeInfo = if (current.path is LocalPath) {
            try {
                val stat = StatFs(current.path.path)
                Pair(stat.availableBytes, stat.totalBytes)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }

        val info = ExplorerLocation.Directory.Info(
            volumeFreeSpace = volumeInfo?.first,
            volumeTotalSpace = volumeInfo?.second,
        )

        val items = gatewaySwitch.listFiles(current.path).map {
            ExplorerItem.Peek(it)
        }
        log(tag) { "getPeek(): Peeked ${items.size} items" }

        ExplorerLocation.Directory(
            path = current.path,
            parent = current.parent,
            items = items,
            info = info,
            permissionState = checkLocationPermissions(ExplorerNavigation.Target.Directory(current.path)),
        )
    }

    private suspend fun getDirectory(
        current: ExplorerLocation.Directory
    ): ExplorerLocation.Directory = withContext(Dispatchers.IO) {
        log(tag) { "getDirectory(): Loading directory: ${current.path}" }

        val basicLookups = gatewaySwitch.lookupFiles(current.path)
        log(tag) { "getContent(): ${basicLookups.size} lookups" }

        val fileClassifier = FileTypeClassifier()

        // Convert to ExplorerPathItem with basic info
        val items = basicLookups.map { lookup ->
            fileClassifier.classify(lookup).also {
                if (Bugs.isDebug) log(tag, VERBOSE) { "${lookup.path} -> $it" }
            }
        }

        // Calculate directory info
        var fileCount = 0
        var directoryCount = 0
        var totalSize = 0L

        items.forEach { item ->
            when (item) {
                is ExplorerItem.Directory -> directoryCount++
                is ExplorerItem.File -> {
                    fileCount++
                    totalSize += item.lookup.size
                }
            }
        }
        log(tag) { "getDirectory(): Directory info: $fileCount files, $directoryCount directories, $totalSize bytes" }

        current.copy(
            items = items,
            info = current.info!!.copy(
                fileCount = fileCount,
                directoryCount = directoryCount,
                totalSize = if (totalSize > 0) totalSize else null,
                isWritable = true,
            ),
        )
    }


    private suspend fun getContentExtended(
        current: ExplorerLocation.Directory
    ): ExplorerLocation.Directory = withContext(Dispatchers.IO) {
        // Second stage: Load extended info with permissions/ownership
        val extendedLookups = gatewaySwitch.lookupFilesExtended(current.path).associateBy { it.path }
        val fileClassifier = FileTypeClassifier()

        current.copy(
            items = current.items.map { item ->
                if (item is ExplorerItem.Lookup) {
                    val extendedLookup = extendedLookups[item.path.path]
                    if (extendedLookup != null) {
                        val basicItem = fileClassifier.classify(extendedLookup)
                        basicItem.withExtendedData(
                            ownership = extendedLookup.ownership,
                            permissions = extendedLookup.permissions,
                            createdAt = extendedLookup.createdAt,
                        )
                    } else {
                        item
                    }

                } else {
                    item
                }

            }
        )
    }

    suspend fun loadLocation(
        target: ExplorerNavigation.Target
    ): Flow<ExplorerLocation> = when (target) {
        is ExplorerNavigation.Target.Home -> flowOf(getHomeEntry())
        is ExplorerNavigation.Target.Device -> flowOf(getDevice())
        is ExplorerNavigation.Target.Directory -> {
            // Observe permission state changes for this directory
            pathPermissionCheck.monitor(target.path).flatMapLatest { permissionState ->
                flow {
                    log(tag, INFO) { "loadLocation(): Loading directory with permission state: $permissionState" }

                    if (!permissionState.hasSufficientPermissions) {
                        log(tag, WARN) { "Insufficient permission for $target" }
                        emit(
                            ExplorerLocation.Directory(
                                path = target.path,
                                permissionState = permissionState,
                            )
                        )
                        return@flow
                    }

                    var currentState = ExplorerLocation.Directory(
                        path = target.path,
                        permissionState = checkLocationPermissions(ExplorerNavigation.Target.Directory(target.path)),
                    )

                    currentState = getPeek(currentState)
                    emit(currentState)

                    currentState = getDirectory(currentState)
                    emit(currentState)

                    currentState = getContentExtended(currentState)
                    emit(currentState)
                }
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(workspaceId: Workspace.Id): BrowsingEngine
    }
}