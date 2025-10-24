package eu.darken.butler.explorer.core.engine

import androidx.annotation.StringRes
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.extensions.getFileSystemInfo
import eu.darken.butler.common.files.extensions.isAncestorOfOrSelf
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.MetadataRepo
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.common.storage.StorageEnvironment
import eu.darken.butler.explorer.R
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.permissions.PathPermissionCheck
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

class DirectoryLocationLoader @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    private val gatewaySwitch: GatewaySwitch,
    private val pathPermissionCheck: PathPermissionCheck,
    private val storageEnvironment: StorageEnvironment,
    private val metadataRepo: MetadataRepo,
) {

    private val tag = logTag("Explorer", "Workspace", workspaceId.shortTag, "DirectoryLoader")

    private class LoaderContext(
        private val path: APath<*>,
        private val permissionState: eu.darken.butler.workspace.core.permissions.PermissionState,
        private val emit: suspend (ExplorerLocation.Directory) -> Unit,
    ) {
        private var currentState = ExplorerLocation.Directory(
            path = path,
            permissionState = permissionState,
            progress = Progress.Data(
                primary = caString {
                    it.getString(
                        R.string.explorer_loader_progress_directory_loading,
                        path.userReadablePath.get(it)
                    )
                },
            )
        )
        val state: ExplorerLocation.Directory get() = currentState

        suspend fun updateState(transform: ExplorerLocation.Directory.() -> ExplorerLocation.Directory) {
            currentState = currentState.transform()
            emit(currentState)
        }

        suspend fun updateProgressMsg(@StringRes msg: Int) = updateState {
            copy(
                progress = currentState.progress?.copy(
                    secondary = msg.toCaString()
                )
            )
        }

        suspend fun emitState() {
            emit(currentState)
        }

        val targetPath: APath<*> get() = currentState.path
    }

    fun loadDirectory(path: APath<*>): Flow<ExplorerLocation> {
        return pathPermissionCheck.monitor(path).flatMapLatest { permissionState ->
            flow {
                log(tag, INFO) { "loadDirectory(): Loading directory with permission state: $permissionState" }
                val context = LoaderContext(path, permissionState, ::emit)
                context.emitState()

                context.updateProgressMsg(R.string.explorer_loader_progress_directory_permissions)
                if (!permissionState.hasSufficientPermissions) {
                    log(tag, WARN) { "Insufficient permission for $path" }
                    emit(ExplorerLocation.Directory(path = path, permissionState = permissionState, progress = null))
                    return@flow
                }

                context.loadFileSystemInfo()

                context.loadPeek()

                context.loadContent()

                context.loadContentExtended()

                context.updateState { copy(progress = null) }
            }
        }
    }

    private suspend fun LoaderContext.loadFileSystemInfo() {
        log(tag) { "loadFileSystemInfo(): Loading file system info for $targetPath" }

        updateProgressMsg(R.string.explorer_loader_progress_directory_filesystem)
        val fsInfo = targetPath.getFileSystemInfo(gatewaySwitch)

        updateState {
            copy(
                info = ExplorerLocation.Directory.Info(
                    volumeFreeSpace = fsInfo.freeSpace,
                    volumeTotalSpace = fsInfo.totalSpace,
                )
            )
        }
    }

    private suspend fun LoaderContext.loadPeek() {
        log(tag) { "loadPeek(): Loading peek for $targetPath" }
        updateProgressMsg(R.string.explorer_loader_progress_directory_content)

        val items = gatewaySwitch.listFiles(targetPath).map {
            ExplorerItem.Peek(it)
        }
        log(tag) { "loadPeek(): Peeked ${items.size} items" }

        updateState {
            copy(
                info = info?.copy(
                    fileCount = items.size,
                ),
                items = items
            )
        }
    }

    private suspend fun LoaderContext.loadContent() {
        log(tag) { "loadContent(): Loading content: $targetPath" }
        updateProgressMsg(R.string.explorer_loader_progress_directory_content_details)

        val basicLookups = gatewaySwitch.lookupFiles(
            targetPath,
            LookupOptions(
                continueOnError = true,
                fallbackToUnknown = true,
                fetchSize = true,
                fetchModifiedAt = true
            ),
        )
        log(tag) { "loadContent(): ${basicLookups.size} lookups" }

        val fileClassifier = FileTypeClassifier()

        val items = basicLookups.map { lookup ->
            val metadata = metadataRepo.extract(lookup)
            fileClassifier.classify(lookup, metadata).also {
                if (Bugs.isDebug) log(tag, VERBOSE) { "${lookup.path} -> $it" }
            }
        }

        var fileCount = 0
        var directoryCount = 0
        var totalSize = 0L

        items.forEach { item ->
            when (item) {
                is ExplorerItem.Directory -> directoryCount++
                is ExplorerItem.File -> {
                    fileCount++
                    totalSize += item.lookup.size ?: 0L
                }
            }
        }
        log(tag) { "loadContent(): Directory info: $fileCount files, $directoryCount directories, $totalSize bytes" }

        val newInfo = state.info!!.copy(
            fileCount = fileCount,
            directoryCount = directoryCount,
            totalSize = if (totalSize > 0) totalSize else null,
            isWritable = true,
        )

        updateState {
            copy(
                items = items,
                info = newInfo,
            )
        }
    }

    private suspend fun LoaderContext.loadContentExtended() {
        log(tag) { "loadContentExtended(): Loading content extended: $targetPath" }
        updateProgressMsg(R.string.explorer_loader_progress_directory_content_extended)

        val isPublicStorage =
            targetPath is SAFPath || storageEnvironment.publicStorages.any { it.isAncestorOfOrSelf(targetPath) }

        val extendedLookups = gatewaySwitch.lookupFiles(
            targetPath,
            LookupOptions(
                continueOnError = true,
                fallbackToUnknown = true,
                fetchCreatedAt = true,
                fetchOwnership = !isPublicStorage,
                fetchPermissions = !isPublicStorage
            ),
        ).associateBy { it.path }

        // Count children for directories
        val childCounts = mutableMapOf<String, Int>()
        extendedLookups.values.forEach { lookup ->
            if (lookup.fileType == FileType.DIRECTORY) {
                try {
                    val children = gatewaySwitch.listFiles(lookup.lookedUp)
                    childCounts[lookup.path] = children.size
                } catch (e: Exception) {
                    // Permission denied or other error - leave as null
                    log(tag, WARN) { "Failed to count children for ${lookup.path}: ${e.message}" }
                }
            }
        }

        val items = state.items!!.map { item ->
            if (item !is ExplorerItem.Lookup) return@map item

            val extendedLookup = extendedLookups[item.path.path] ?: return@map item

            val updatedItem = item.withExtendedData(
                ownership = extendedLookup.ownership,
                permissions = extendedLookup.permissions,
                createdAt = extendedLookup.createdAt,
            )

            // Add child count for directories
            if (updatedItem is ExplorerItem.RegularDirectory) {
                val childCount = childCounts[item.path.path]
                updatedItem.copy(childCount = childCount)
            } else {
                updatedItem
            }
        }

        updateState { copy(items = items) }
    }

    suspend fun classifyLookups(lookups: Collection<APathLookup<*>>): List<ExplorerItem.Lookup> {
        val fileClassifier = FileTypeClassifier()
        return lookups.map { lookup ->
            val metadata = metadataRepo.extract(lookup)
            fileClassifier.classify(lookup, metadata)
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(workspaceId: Workspace.Id): DirectoryLocationLoader
    }
}