package eu.darken.butler.explorer.core.engine

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.extensions.getFileSystemInfo
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.permissions.PathPermissionCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

class DirectoryLocationLoader @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    private val gatewaySwitch: GatewaySwitch,
    private val pathPermissionCheck: PathPermissionCheck,
) {

    private val tag = logTag("Explorer", "Workspace", workspaceId.shortTag, "DirectoryLoader")

    fun loadDirectory(path: APath): Flow<ExplorerLocation> {
        return pathPermissionCheck.monitor(path).flatMapLatest { permissionState ->
            flow {
                log(tag, INFO) { "loadDirectory(): Loading directory with permission state: $permissionState" }

                var currentState = ExplorerLocation.Directory(path = path)

                currentState = currentState.copy(
                    progress = currentState.progress?.copy(
                        primary = caString { "Checking permissions..." }
                    )
                )
                emit(currentState)

                if (!permissionState.hasSufficientPermissions) {
                    log(tag, WARN) { "Insufficient permission for $path" }
                    emit(
                        ExplorerLocation.Directory(
                            path = path,
                            permissionState = permissionState,
                        )
                    )
                    return@flow
                }

                currentState = currentState.copy(
                    permissionState = permissionState,
                    progress = currentState.progress?.copy(
                        primary = caString { "Loading filesystem infos..." }
                    )
                )
                emit(currentState)

                val fsInfo = path.getFileSystemInfo(gatewaySwitch)

                currentState = currentState.copy(
                    info = ExplorerLocation.Directory.Info(
                        volumeFreeSpace = fsInfo.freeSpace,
                        volumeTotalSpace = fsInfo.totalSpace,
                    ),
                    progress = currentState.progress?.copy(
                        primary = caString { "Loading folder content..." }
                    )
                )
                emit(currentState)

                currentState = loadPeek(currentState)
                currentState = currentState.copy(
                    progress = currentState.progress?.copy(
                        primary = caString { "Loading content details..." }
                    )
                )
                emit(currentState)

                currentState = loadContent(currentState)
                currentState = currentState.copy(
                    progress = currentState.progress?.copy(
                        primary = caString { "Loading extended details..." }
                    )
                )
                emit(currentState)

                currentState = loadContentExtended(currentState)
                currentState = currentState.copy(
                    progress = currentState.progress?.copy(
                        primary = caString { "Finished :)" }
                    )
                )
                emit(currentState)
            }
        }
    }

    private suspend fun loadPeek(
        current: ExplorerLocation.Directory
    ): ExplorerLocation.Directory = withContext(Dispatchers.IO) {
        log(tag) { "loadPeek(): Loading peek for ${current.path}" }

        val items = gatewaySwitch.listFiles(current.path).map {
            ExplorerItem.Peek(it)
        }
        log(tag) { "loadPeek(): Peeked ${items.size} items" }

        current.copy(
            path = current.path,
            parent = current.parent,
            items = items,
        )
    }

    private suspend fun loadContent(
        current: ExplorerLocation.Directory
    ): ExplorerLocation.Directory = withContext(Dispatchers.IO) {
        log(tag) { "loadContent(): Loading content: ${current.path}" }

        val basicLookups = gatewaySwitch.lookupFiles(current.path)
        log(tag) { "loadContent(): ${basicLookups.size} lookups" }

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
        log(tag) { "loadContent(): Directory info: $fileCount files, $directoryCount directories, $totalSize bytes" }

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

    private suspend fun loadContentExtended(
        current: ExplorerLocation.Directory
    ): ExplorerLocation.Directory = withContext(Dispatchers.IO) {
        log(tag) { "loadContentExtended(): Loading content extended: ${current.path}" }

        val extendedLookups = gatewaySwitch.lookupFilesExtended(current.path).associateBy { it.path }
        val fileClassifier = FileTypeClassifier()

        current.copy(
            items = current.items?.map { item ->
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

    @AssistedFactory
    interface Factory {
        fun create(workspaceId: Workspace.Id): DirectoryLocationLoader
    }
}