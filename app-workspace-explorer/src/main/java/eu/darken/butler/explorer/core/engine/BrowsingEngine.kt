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
import eu.darken.butler.common.files.FileType
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.hasApiLevel
import eu.darken.butler.workspace.core.permissions.PathPermissionChecker
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.operations.OperationHint
import eu.darken.butler.explorer.core.watcher.FileSystemEvent
import eu.darken.butler.explorer.core.watcher.FileSystemWatcher
import eu.darken.butler.workspace.core.permissions.PermissionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.time.Instant

/**
 * Handles directory browsing, navigation, and file listing operations.
 * Maintains a cache of directory states and provides real-time updates
 * through file system watching (to be implemented).
 */
class BrowsingEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gatewaySwitch: GatewaySwitch,
    private val fileWatcher: FileSystemWatcher,
    private val pathPermissionChecker: PathPermissionChecker,
) {

    internal var subTag: String = ""
    private val tag by lazy { logTag("Explorer", "Engine", subTag) }

    // Directory state cache
    private val directoryCache = ConcurrentHashMap<APath, DirectoryState>()
    private val cacheMutex = Mutex()

    /**
     * Represents the cached state of a directory.
     */
    private data class DirectoryState(
        val path: APath,
        val baseItems: List<ExplorerItem>,
        val optimisticItems: List<ExplorerItem>? = null,
        val lastRefresh: Instant = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
        val version: Long = 0,
        val isWatching: Boolean = false,
        val pendingHints: List<OperationHint> = emptyList(),
    ) {
        val displayItems: List<ExplorerItem>
            get() = optimisticItems ?: baseItems
    }

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
    ): Flow<ExplorerLocation> = when (target) {
        is ExplorerNavigation.Target.Home -> flowOf(getHomeEntry())
        is ExplorerNavigation.Target.Device -> flowOf(getDevice())
        is ExplorerNavigation.Target.Directory -> {
            // Observe permission state changes for this directory
            pathPermissionChecker.observePermissionState(target.path)
                .flatMapLatest { permissionState ->
                    flow {
                        log(tag, INFO) { "loadLocation(): Loading directory with permission state: ${permissionState.hasSufficientPermissions}" }

                        if (!permissionState.hasSufficientPermissions) {
                            // Emit location with permission issue
                            emit(
                                ExplorerLocation.Directory(
                                    path = target.path,
                                    items = emptyList(),
                                    permissionState = permissionState,
                                )
                            )
                        } else {
                            // Start watching the directory for changes
                            startWatchingDirectory(target.path)

                            val firstPass = getDirectory(target.path)
                            emit(firstPass)

                            // Cache the directory state
                            cacheMutex.withLock {
                                directoryCache[target.path] = DirectoryState(
                                    path = target.path,
                                    baseItems = firstPass.items,
                                    isWatching = true,
                                )
                            }

                            val secondPass = getContentExtended(target.path)
                            emit(
                                firstPass.copy(items = secondPass)
                            )

                            // Update cache with extended info
                            cacheMutex.withLock {
                                directoryCache[target.path] = directoryCache[target.path]?.copy(
                                    baseItems = secondPass
                                ) ?: DirectoryState(
                                    path = target.path,
                                    baseItems = secondPass,
                                    isWatching = true,
                                )
                            }
                        }
                    }
                }
        }
    }

    /**
     * Apply an operation hint to provide optimistic UI updates.
     */
    suspend fun acceptOperationHint(hint: OperationHint) {
        log(tag, DEBUG) { "Accepting operation hint: $hint" }

        cacheMutex.withLock {
            val state = directoryCache[hint.targetPath] ?: return

            val updatedItems = when (hint) {
                is OperationHint.FilesAdded -> {
                    // Add files optimistically
                    val newItems = hint.files.mapNotNull { path ->
                        // Create placeholder items for added files
                        val lookup = gatewaySwitch.lookup(path)
                        ExplorerItem.RegularFile(
                            lookup = lookup,
                            mimeType = "application/octet-stream", // Default mime type
                        )
                    }
                    state.baseItems + newItems
                }

                is OperationHint.FilesRemoved -> {
                    // Remove files optimistically
                    state.baseItems.filter { item ->
                        when (item) {
                            is ExplorerItem.PathItem -> {
                                val itemPath = item.lookup.path
                                !hint.files.any { it.path == itemPath }
                            }
                            else -> true
                        }
                    }
                }

                is OperationHint.FileRenamed -> {
                    // Update renamed file
                    state.baseItems.map { item ->
                        when (item) {
                            is ExplorerItem.PathItem -> {
                                if (item.lookup.name == hint.oldName) {
                                    // Create new item with renamed path
                                    // This is a simplified version - proper implementation would update the path
                                    item
                                } else item
                            }
                            else -> item
                        }
                    }
                }

                else -> state.baseItems
            }

            directoryCache[hint.targetPath] = state.copy(
                optimisticItems = updatedItems,
                pendingHints = state.pendingHints + hint,
                version = state.version + 1,
            )
        }
    }

    /**
     * Handle file system change events.
     */
    private suspend fun handleFileSystemEvent(event: FileSystemEvent, path: APath) {
        log(tag, DEBUG) { "Handling file system event: $event for $path" }

        when (event) {
            is FileSystemEvent.MassiveChange -> {
                // Too many changes, refresh the directory
                refreshDirectory(path)
            }

            is FileSystemEvent.FileCreated,
            is FileSystemEvent.FileDeleted,
            is FileSystemEvent.FileModified,
            is FileSystemEvent.DirectoryChanged -> {
                // Refresh the affected directory
                val dirPath = when (val p = event.path) {
                    is LocalPath -> p.parent() ?: p
                    else -> p
                }
                refreshDirectory(dirPath)
            }

            is FileSystemEvent.WatchError -> {
                log(tag, ERROR) { "File system watch error for $path: ${event.error}" }
                // Stop watching and clear cache
                stopWatchingDirectory(path)
                cacheMutex.withLock {
                    directoryCache.remove(path)
                }
            }

            else -> {
                // Handle other events as needed
            }
        }
    }

    /**
     * Refresh a directory by re-reading from file system.
     */
    suspend fun refreshDirectory(path: APath) {
        log(tag, DEBUG) { "Refreshing directory: $path" }

        val items = getContent(path)

        cacheMutex.withLock {
            val state = directoryCache[path] ?: return
            directoryCache[path] = state.copy(
                baseItems = items,
                optimisticItems = null, // Clear optimistic state
                pendingHints = emptyList(), // Clear pending hints
                lastRefresh = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
                version = state.version + 1,
            )
        }
    }

    /**
     * Start watching a directory for file system changes.
     */
    private suspend fun startWatchingDirectory(path: APath) {
        try {
            fileWatcher.startWatching(path)
            log(tag, DEBUG) { "Started watching directory: $path" }
        } catch (e: Exception) {
            log(tag, WARN) { "Failed to start watching directory $path: $e" }
        }
    }

    /**
     * Stop watching a directory.
     */
    private suspend fun stopWatchingDirectory(path: APath) {
        try {
            fileWatcher.stopWatching(path)
            log(tag, DEBUG) { "Stopped watching directory: $path" }
        } catch (e: Exception) {
            log(tag, WARN) { "Failed to stop watching directory $path: $e" }
        }
    }

    /**
     * Clear all cached directory states and stop watching.
     */
    suspend fun clearCache() {
        cacheMutex.withLock {
            directoryCache.keys.forEach { path ->
                stopWatchingDirectory(path)
            }
            directoryCache.clear()
        }
    }

}