package eu.darken.butler.explorer.core.engine

import android.content.Context
import android.os.Environment
import android.os.StatFs
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Code
import androidx.compose.material.icons.twotone.PhoneAndroid
import androidx.compose.material.icons.twotone.Storage
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.operations.OperationHint
import eu.darken.butler.workspace.core.permissions.PathPermissionCheck
import eu.darken.butler.workspace.core.permissions.PermissionState
import eu.darken.butler.workspace.core.permissions.check
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.time.Instant

class BrowsingEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gatewaySwitch: GatewaySwitch,
    private val pathPermissionCheck: PathPermissionCheck,
) {

    internal var subTag: String = ""
    private val tag by lazy { logTag("Explorer", "Engine", subTag) }

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
                    LocalPath.Companion.build(Environment.getRootDirectory())
                ),
            ),
            ExplorerItem.Shortcut(
                shortcutId = "device-internal-public",
                displayIcon = Icons.TwoTone.Storage,
                displayName = R.string.explorer_nav_internal_storage.toCaString(),
                target = ExplorerNavigation.Target.Directory(
                    LocalPath.Companion.build(Environment.getExternalStorageDirectory())
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

    private suspend fun getContent(path: APath): List<ExplorerItem.PathItem> = withContext(Dispatchers.IO) {
        log(tag) { "getContent(): Loading content: $path" }

        // First stage: Load basic file info quickly
        val basicLookups = gatewaySwitch.lookupFiles(path)
        log(tag) { "getContent(): ${basicLookups.size} lookups" }

        val fileClassifier = FileTypeClassifier()

        // Convert to ExplorerPathItem with basic info
        basicLookups.map { lookup ->
            fileClassifier.classify(lookup).also {
                if (Bugs.isDebug) log(tag, VERBOSE) { "${lookup.path} -> $it" }
            }
        }
    }

    private suspend fun getContentExtended(path: APath): List<ExplorerItem.PathItem> = withContext(Dispatchers.IO) {
        // Second stage: Load extended info with permissions/ownership
        val extendedLookups = gatewaySwitch.lookupFilesExtended(path)
        val fileClassifier = FileTypeClassifier()

        // Convert to ExplorerPathItem with extended info
        extendedLookups.map { extendedLookup ->
            val basicItem = fileClassifier.classify(extendedLookup)
            basicItem.withExtendedData(
                ownership = extendedLookup.ownership,
                permissions = extendedLookup.permissions
            )
        }
    }

    private suspend fun getDirectory(
        path: APath,
        parent: ExplorerNavigation.Target? = null
    ): ExplorerLocation.Directory = withContext(Dispatchers.IO) {
        log(tag) { "getDirectory(): Loading directory: $path - $parent" }
        val items = getContent(path)

        // Calculate directory info
        var fileCount = 0
        var directoryCount = 0
        var totalSize = 0L

        items.forEach { item ->
            when (item) {
                is ExplorerItem.DirectoryItem -> directoryCount++
                is ExplorerItem.FileItem -> {
                    fileCount++
                    totalSize += item.lookup.size
                }
            }
        }
        log(tag) { "getDirectory(): Directory info: $fileCount files, $directoryCount directories, $totalSize bytes" }
        // Get volume info if path is LocalPath
        val volumeInfo = if (path is LocalPath) {
            try {
                val stat = StatFs(path.path)
                Pair(stat.availableBytes, stat.totalBytes)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }

        val info = ExplorerLocation.Directory.Info(
            fileCount = fileCount,
            directoryCount = directoryCount,
            totalSize = if (totalSize > 0) totalSize else null,
            volumeFreeSpace = volumeInfo?.first,
            volumeTotalSpace = volumeInfo?.second,
            isWritable = true,
        )

        ExplorerLocation.Directory(
            path = path,
            parent = parent,
            items = items,
            info = info,
            permissionState = checkLocationPermissions(ExplorerNavigation.Target.Directory(path)),
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

                    val firstPass = getDirectory(target.path)
                    emit(firstPass)

                    val secondPass = getContentExtended(target.path)
                    emit(firstPass.copy(items = secondPass))
                }
            }
        }
    }

}