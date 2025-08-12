package eu.darken.butler.explorer.core.engine

import android.os.Environment
import android.os.StatFs
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Code
import androidx.compose.material.icons.twotone.PhoneAndroid
import androidx.compose.material.icons.twotone.Storage
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.explorer.core.ExplorerNavigation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import javax.inject.Inject

class ExplorerEngine @Inject constructor(
    private val gatewaySwitch: GatewaySwitch,
) {

    private suspend fun getHomeEntry(): ExplorerLocation = withContext(Dispatchers.IO) {
        val shortcuts = listOf(
            ExplorerItem.Shortcut(
                shortcutId = "device",
                displayIcon = Icons.TwoTone.PhoneAndroid,
                displayName = caString { "Device" }, // TODO localize
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
        )
    }

    private suspend fun getDevice(): ExplorerLocation = withContext(Dispatchers.IO) {
        val storageLocations = listOf(
            ExplorerItem.Shortcut(
                shortcutId = "device-root",
                displayIcon = Icons.TwoTone.Code,
                displayName = caString { "Root" }, // TODO localize
                target = ExplorerNavigation.Target.Directory(
                    LocalPath.Companion.build(Environment.getRootDirectory())
                ),
            ),
            ExplorerItem.Shortcut(
                shortcutId = "device-internal-public",
                displayIcon = Icons.TwoTone.Storage,
                displayName = caString { "Internal public storage" }, // TODO localize
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
            totalCapacity = stat?.let { it.totalBytes },
            usedSpace = stat?.let { it.totalBytes - it.availableBytes },
        )

        ExplorerLocation.Device(
            items = storageLocations,
            info = info,
        )
    }

    private suspend fun getContent(path: APath): List<ExplorerItem.PathItem> = withContext(Dispatchers.IO) {
        // First stage: Load basic file info quickly
        val basicLookups = gatewaySwitch.lookupFiles(path)
        val fileClassifier = FileTypeClassifier()

        // Convert to ExplorerPathItem with basic info
        basicLookups.map { lookup ->
            fileClassifier.classify(lookup)
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
                    totalSize += item.lookup.size ?: 0L
                }
            }
        }

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
            isWritable = try {
                gatewaySwitch.canWrite(path)
            } catch (e: Exception) {
                false
            }
        )

        ExplorerLocation.Directory(
            path = path,
            parent = parent,
            items = items,
            info = info,
        )
    }

    suspend fun loadLocation(
        target: ExplorerNavigation.Target
    ): Flow<ExplorerLocation> = flow {
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

}