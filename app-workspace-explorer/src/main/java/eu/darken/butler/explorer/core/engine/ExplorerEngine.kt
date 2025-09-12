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
import eu.darken.butler.common.hasApiLevel
import eu.darken.butler.workspace.core.permissions.PathPermissionChecker
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.workspace.core.permissions.PermissionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import javax.inject.Inject

class ExplorerEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gatewaySwitch: GatewaySwitch,
    private val pathPermissionChecker: PathPermissionChecker,
) {

    internal var subTag: String = ""
    private val tag by lazy { logTag("Explorer", "Engine", subTag) }

    private fun checkLocationPermissions(target: ExplorerNavigation.Target): PermissionState {
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
                pathPermissionChecker.check(target.path)
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
            totalDeviceStorage = stat?.let { it.totalBytes },
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
    ): Flow<ExplorerLocation> = flow {
        log(tag, INFO) { "loadLocation(): Loading location: $target" }
        when (target) {
            is ExplorerNavigation.Target.Home -> emit(getHomeEntry())
            is ExplorerNavigation.Target.Device -> emit(getDevice())
            is ExplorerNavigation.Target.Directory -> {
                val firstPass = getDirectory(target.path)
                emit(firstPass)
                val secondPass = getContentExtended(target.path)
                emit(
                    firstPass.copy(items = secondPass)
                )
            }
        }
    }

    suspend fun executeOperation(operation: ExplorerOperation): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            when (operation) {
                is ExplorerOperation.FileOp.CreateFolder -> {
                    log(tag, INFO) { "Creating folder: ${operation.name} in ${operation.parentPath}" }
                    val folderPath = operation.parentPath.child(operation.name)
                    gatewaySwitch.createDir(folderPath)
                    Result.success(Unit)
                }

                is ExplorerOperation.FileOp.CreateFile -> {
                    log(tag, INFO) { "Creating file: ${operation.name} in ${operation.parentPath}" }
                    val filePath = operation.parentPath.child(operation.name)
                    gatewaySwitch.createFile(filePath)
                    Result.success(Unit)
                }

                is ExplorerOperation.FileOp.Delete -> {
                    log(tag, INFO) { "Deleting ${operation.paths.size} items" }
                    operation.paths.forEach { path ->
                        gatewaySwitch.delete(path, recursive = operation.recursive)
                    }
                    Result.success(Unit)
                }

                is ExplorerOperation.FileOp.Copy -> {
                    log(tag, INFO) { "Copying ${operation.sources.size} items to ${operation.destination}" }
                    // TODO: Implement copy operation when gateway supports it
                    // For now, just log
                    log(tag, WARN) { "Copy operation not yet implemented in gateway" }
                    Result.failure(UnsupportedOperationException("Copy not yet implemented"))
                }

                is ExplorerOperation.FileOp.Move -> {
                    log(tag, INFO) { "Moving ${operation.sources.size} items to ${operation.destination}" }
                    // TODO: Implement move operation when gateway supports it
                    // For now, we could try delete + copy when available
                    log(tag, WARN) { "Move operation not yet implemented in gateway" }
                    Result.failure(UnsupportedOperationException("Move not yet implemented"))
                }

                is ExplorerOperation.FileOp.Rename -> {
                    log(tag, INFO) { "Renaming ${operation.path} to ${operation.newName}" }
                    // TODO: Implement rename when move is available
                    // For now, just log
                    log(tag, WARN) { "Rename operation not yet implemented in gateway" }
                    Result.failure(UnsupportedOperationException("Rename not yet implemented"))
                }
                
                is ExplorerOperation.FileOp.Compress -> {
                    log(tag, INFO) { "Compressing ${operation.sources.size} items to ${operation.destination}" }
                    // TODO: Implement compress operation
                    log(tag, WARN) { "Compress operation not yet implemented" }
                    Result.failure(UnsupportedOperationException("Compress not yet implemented"))
                }
                
                is ExplorerOperation.FileOp.Extract -> {
                    log(tag, INFO) { "Extracting ${operation.archive} to ${operation.destination}" }
                    // TODO: Implement extract operation
                    log(tag, WARN) { "Extract operation not yet implemented" }
                    Result.failure(UnsupportedOperationException("Extract not yet implemented"))
                }
            }
        } catch (e: Exception) {
            log(tag, ERROR) { "Operation failed: $operation - ${e.message}" }
            Result.failure(e)
        }
    }

}