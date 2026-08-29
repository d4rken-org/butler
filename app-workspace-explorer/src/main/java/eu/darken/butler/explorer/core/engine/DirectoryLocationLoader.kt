package eu.darken.butler.explorer.core.engine

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.adb.AdbManager
import eu.darken.butler.common.adb.canUseAdbNow
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.Existence
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.SmbPath
import eu.darken.butler.common.files.errors.PathNotFoundException
import eu.darken.butler.common.files.extensions.getFileSystemInfo
import eu.darken.butler.common.files.extensions.isAncestorOfOrSelf
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.MetadataRepo
import eu.darken.butler.common.files.saf.location.SAFLocationManager
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.common.root.RootManager
import eu.darken.butler.common.root.canUseRootNow
import eu.darken.butler.common.storage.StorageEnvironment
import eu.darken.butler.explorer.R
import eu.darken.butler.workspace.core.preview.FolderPreviewResolver
import eu.darken.butler.permissions.core.PathPermissionCheck
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

class DirectoryLocationLoader @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    private val gatewaySwitch: GatewaySwitch,
    private val pathPermissionCheck: PathPermissionCheck,
    private val storageEnvironment: StorageEnvironment,
    private val metadataRepo: MetadataRepo,
    private val rootManager: RootManager,
    private val adbManager: AdbManager,
    private val safLocationManager: SAFLocationManager,
    private val writabilityEvaluator: WritabilityEvaluator,
    private val folderPreviewResolver: FolderPreviewResolver,
) {

    private val tag = logTag("Explorer", "Workspace", workspaceId.shortTag, "DirectoryLoader")

    private val LocationLoaderContext<ExplorerLocation.Directory>.targetPath: APath<*>
        get() = state.path

    fun loadDirectory(path: APath<*>): Flow<ExplorerLocation> {
        return pathPermissionCheck.monitor(path).flatMapLatest { setupRequirements ->
            flow {
                log(tag, INFO) { "loadDirectory(): Loading directory with setup requirements: $setupRequirements" }

                // If alternative path available, redirect to it
                val altPath = setupRequirements.alternativePath
                if (altPath != null) {
                    log(tag) { "Redirecting from $path to $altPath" }
                    loadDirectory(altPath).collect { emit(it) }
                    return@flow
                }

                val context = LocationLoaderContext(
                    initialState = ExplorerLocation.Directory(
                        path = path,
                        setupRequirements = setupRequirements,
                        progress = Progress.Data(
                            primary = caString {
                                it.getString(
                                    R.string.explorer_loader_progress_directory_loading,
                                    path.userReadablePath.get(it)
                                )
                            },
                        )
                    ),
                    emit = ::emit
                )
                context.emitState()

                context.updateProgressMsg(R.string.explorer_loader_progress_directory_permissions)
                if (setupRequirements.needsAction) {
                    log(tag, WARN) { "Action required for $path: $setupRequirements" }
                    emit(
                        ExplorerLocation.Directory(
                            path = path,
                            setupRequirements = setupRequirements,
                            progress = null
                        )
                    )
                    return@flow
                }

                // Every actual load produces fresh folder preview collages, matching how the
                // child-count badge refreshes on navigation and manual refresh.
                folderPreviewResolver.invalidateFor(path)

                gatewaySwitch.useRes {
                    currentCoroutineContext().ensureActive()
                    context.loadFileSystemInfo()

                    // Covers every pass, not just the first: the directory can be deleted after the
                    // peek listing succeeded and before either lookup call.
                    context.asNotFoundIfGone {
                        currentCoroutineContext().ensureActive()
                        context.loadPeek()

                        currentCoroutineContext().ensureActive()
                        context.loadContent()

                        currentCoroutineContext().ensureActive()
                        // A second pass over the network would mean one more round trip per item for
                        // ownership and permissions an SMB share does not report anyway.
                        when (context.targetPath) {
                            is SmbPath -> context.loadNetworkWritability()
                            else -> context.loadContentExtended()
                        }
                    }
                }

                currentCoroutineContext().ensureActive()
                context.updateState { copy(progress = null) }
            }
        }
    }

    /**
     * Turns "the target is gone" into a state the page can render instead of a failure card.
     *
     * Only a probe that positively reports the target as absent qualifies. An unreachable host, an
     * unresponsive document provider or an unreadable container answer [Existence.UNKNOWN], and
     * there the original error carries the signal the user needs, see `DirectoryLocationLoaderTest`.
     */
    private suspend fun <T> LocationLoaderContext<ExplorerLocation.Directory>.asNotFoundIfGone(
        block: suspend () -> T,
    ): T {
        val target = targetPath
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            val existence = try {
                gatewaySwitch.existsStrict(target)
            } catch (probeError: CancellationException) {
                throw probeError
            } catch (probeError: Exception) {
                log(tag, WARN) { "asNotFoundIfGone(): Probe failed for $target: ${probeError.asLog()}" }
                Existence.UNKNOWN
            }
            // The probe can outlive its coroutine, and a cancelled load must not turn into a
            // "folder gone" state for a target the user has already navigated away from.
            currentCoroutineContext().ensureActive()
            if (existence != Existence.ABSENT) throw e

            log(tag, INFO) { "asNotFoundIfGone(): $target is gone, original error was ${e.asLog()}" }
            throw PathNotFoundException(target)
        }
    }

    private suspend fun LocationLoaderContext<ExplorerLocation.Directory>.loadFileSystemInfo() {
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

    private suspend fun LocationLoaderContext<ExplorerLocation.Directory>.loadPeek() {
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

    private suspend fun LocationLoaderContext<ExplorerLocation.Directory>.loadContent() {
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
            // isWritable computed in loadContentExtended()
        )

        updateState {
            copy(
                items = items,
                info = newInfo,
            )
        }
    }

    /**
     * The skipped extended pass is what normally resolves the directory's own writability, which the
     * action bar gates Cut/Delete on, so evaluate it here without any lookup.
     */
    private suspend fun LocationLoaderContext<ExplorerLocation.Directory>.loadNetworkWritability() {
        val writable = writabilityEvaluator.evaluate(
            path = targetPath,
            permissions = null,
            ownership = null,
            // Root/ADB/SAF/Unix bits have no meaning on a share, the server decides access.
            context = WritabilityContext(
                hasRoot = false,
                hasAdb = false,
                appUid = 0,
                safLocation = null,
            ),
        )
        log(tag) { "loadNetworkWritability(): $targetPath is writable: $writable" }

        updateState {
            copy(info = info?.copy(isWritable = writable != false))
        }
    }

    private suspend fun LocationLoaderContext<ExplorerLocation.Directory>.loadContentExtended() {
        currentCoroutineContext().ensureActive()

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

        currentCoroutineContext().ensureActive()

        // Create writability context once for all items
        val safLocation = (targetPath as? SAFPath)?.let { safLocationManager.findPermissionFor(it)?.location }
        val writabilityContext = WritabilityContext(
            hasRoot = rootManager.canUseRootNow(),
            hasAdb = adbManager.canUseAdbNow(),
            appUid = android.os.Process.myUid(),
            safLocation = safLocation,
        )

        // Evaluate writability for the directory itself
        val directoryLookup = try {
            gatewaySwitch.lookup(
                targetPath,
                LookupOptions(
                    fetchOwnership = !isPublicStorage,
                    fetchPermissions = !isPublicStorage,
                ),
            )
        } catch (e: Exception) {
            log(tag, WARN) { "Failed to lookup directory for writability: ${e.message}" }
            null
        }

        val directoryWritable = writabilityEvaluator.evaluate(
            path = targetPath,
            permissions = directoryLookup?.permissions,
            ownership = directoryLookup?.ownership,
            context = writabilityContext,
        )

        updateState {
            copy(info = info?.copy(isWritable = directoryWritable != false))
        }

        // Count children for directories
        val childCounts = mutableMapOf<String, Int>()
        extendedLookups.values.forEach { lookup ->
            currentCoroutineContext().ensureActive()

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

        // Check for cancellation before final item processing
        currentCoroutineContext().ensureActive()

        val items = state.items!!.map { item ->
            if (item !is ExplorerItem.Lookup) return@map item

            val extendedLookup = extendedLookups[item.path.path] ?: return@map item

            // Evaluate writability for this item
            val canWrite = writabilityEvaluator.evaluate(
                path = item.path,
                permissions = extendedLookup.permissions,
                ownership = extendedLookup.ownership,
                context = writabilityContext,
            )

            val updatedItem = item.withExtendedData(
                ownership = extendedLookup.ownership,
                permissions = extendedLookup.permissions,
                createdAt = extendedLookup.createdAt,
                canWrite = canWrite,
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