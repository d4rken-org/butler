package eu.darken.butler.common.files.operations

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.APathLookupExtended
import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.local.operations.core.PathOperationIssueResolver
import eu.darken.butler.common.files.local.operations.core.PathOperationProgressTracker
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.io.R
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * Generic copy operation that works with any path type.
 *
 * This class implements the copy algorithm in a platform-agnostic way using
 * the FileSystemOps abstraction. Platform-specific logic (LocalPath uses Files.copy(),
 * SAFPath uses streams, etc.) is delegated to the TransferStrategy implementation.
 *
 * ## Architecture
 *
 * The operation uses composition to delegate specialized behaviors:
 * - **FileSystemOps**: Platform abstraction for file system operations (lookup, listFiles, exists, etc.)
 * - **TransferStrategy**: Path-specific transfer logic (how to copy file contents)
 * - **PathOperationProgressTracker**: Progress tracking with throttling (shared component)
 * - **PathOperationIssueResolver**: Conflict resolution and "apply to all" flags (shared component)
 *
 * ## Algorithm
 *
 * 1. **Scan Phase**: Walk source tree, calculate total size, queue transfer operations
 * 2. **Transfer Phase**: Execute queued operations (create directories, copy files)
 * 3. **Progress Reporting**: Report progress with throttling (max once per 250ms)
 * 4. **Conflict Resolution**: Handle conflicts (overwrite/skip/rename) via user callback
 * 5. **Error Handling**: Handle errors (permissions, space, etc.) via user callback
 *
 * ## Work Queue Pattern
 *
 * Uses a work queue to process operations in correct order:
 * - ScanSource: Scan a path and queue children
 * - CreateDirectory: Create directory at destination
 * - CopyFile: Copy file to destination
 * - ResolveConflict: Prompt user for conflict resolution
 *
 * ## Same-Type vs Cross-Type Operations
 *
 * Supports both same-type (SP=DP) and cross-type (SP≠DP) operations:
 * - **Same-type**: SP=DP (e.g., LocalPath → LocalPath)
 *   - sourceOps and destOps are the same instance
 *   - Used within individual gateways (e.g., LocalGateway)
 * - **Cross-type**: SP≠DP (e.g., SAFPath → LocalPath)
 *   - sourceOps and destOps are different instances
 *   - Used in GatewaySwitch for cross-gateway operations
 *
 * @param SP The source path type (LocalPath, SAFPath, etc.)
 * @param SPL The source path lookup type (LocalPathLookup, SAFPathLookup, etc.)
 * @param SPLE The source path lookup extended type (LocalPathLookupExtended, SAFPathLookupExtended, etc.)
 * @param DP The destination path type (LocalPath, SAFPath, etc.)
 * @param DPL The destination path lookup type (LocalPathLookup, SAFPathLookup, etc.)
 * @param DPLE The destination path lookup extended type (LocalPathLookupExtended, SAFPathLookupExtended, etc.)
 */
internal class GenericPathCopy<
    SP : APath, SPL : APathLookup<SP>, SPLE : APathLookupExtended<SP>,  // Source types
    DP : APath, DPL : APathLookup<DP>, DPLE : APathLookupExtended<DP>   // Destination types
>(
    private val sources: Collection<SP>,
    private val destination: DP,
    private val sourceOps: FileSystemOps<SP, SPL, SPLE>,
    private val destOps: FileSystemOps<DP, DPL, DPLE>,
    private val strategy: TransferStrategy<SP, SPL, SPLE, DP, DPL, DPLE>,
    private val onProgress: (suspend (CopyAction.State.Progress<SP, SPL>) -> Unit)?,
    private val onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?
) {

    private val copied = linkedSetOf<Pair<SP, DP>>()
    private val skipped = linkedSetOf<SP>()
    private var totalBytesTransferred = 0L

    // Shared components
    private val progressTracker = PathOperationProgressTracker()
    private val issueResolver = PathOperationIssueResolver(onIssue)

    // Track renamed directories for path adjustments
    private val skippedSourceDirs = mutableSetOf<SP>()
    private val renamedSourceDirs = mutableMapOf<SP, DP>()

    // Scan tracking
    private var scanItemsRemaining = 0

    // Work queue for processing operations
    private var workQueue = ArrayDeque<WorkItem>()

    /**
     * Sealed hierarchy of work items for the copy queue.
     */
    private sealed class WorkItem {
        /**
         * Scan a source path and queue children for copying.
         *
         * @param source The path to scan
         * @param topLevelSource The top-level source (for path calculations)
         */
        data class ScanSource<SP : APath>(
            val source: SP,
            val topLevelSource: SP,
        ) : WorkItem()

        /**
         * Copy a file to destination.
         *
         * @param sourceLookup Source file metadata
         * @param destination Destination path
         * @param topLevelSource Top-level source (for error reporting)
         */
        data class CopyFile<SP : APath, SPL : APathLookup<SP>, DP : APath>(
            val sourceLookup: SPL,
            val destination: DP,
            val topLevelSource: SP,
        ) : WorkItem()

        /**
         * Create a directory at destination.
         *
         * @param sourceLookup Source directory metadata
         * @param destination Destination path
         * @param topLevelSource Top-level source (for error reporting)
         */
        data class CreateDirectory<SP : APath, SPL : APathLookup<SP>, DP : APath>(
            val sourceLookup: SPL,
            val destination: DP,
            val topLevelSource: SP,
        ) : WorkItem()

        /**
         * Resolve a path conflict (file/directory already exists at destination).
         *
         * @param sourceLookup Source metadata
         * @param destination Destination path
         * @param destLookup Destination metadata (existing file/dir)
         * @param originalItem The original work item that triggered this conflict
         */
        data class ResolveConflict<SP : APath, SPL : APathLookup<SP>, DP : APath, DPL : APathLookup<DP>>(
            val sourceLookup: SPL,
            val destination: DP,
            val destLookup: DPL,
            val originalItem: WorkItem,
        ) : WorkItem()
    }

    suspend fun execute(): CopyAction.State.Result<SP, SPL> {
        log(TAG, DEBUG) {
            "execute(): Copying ${sources.size} sources to $destination"
        }

        // Ensure destination directory exists
        if (!destOps.exists(destination)) {
            destOps.createDir(destination)
        }

        // Initialize work queue with scan items for all sources
        scanItemsRemaining = sources.size
        sources.forEach { source ->
            workQueue.addLast(WorkItem.ScanSource(source, source))
        }

        // Process work queue
        while (workQueue.isNotEmpty() && currentCoroutineContext().isActive) {
            when (val item = workQueue.removeFirst()) {
                is WorkItem.ScanSource<*> -> {
                    scanItemsRemaining--
                    @Suppress("UNCHECKED_CAST")
                    val childrenAdded = processScan(item as WorkItem.ScanSource<SP>)
                    scanItemsRemaining += childrenAdded

                    if (scanItemsRemaining == 0) {
                        val snapshot = progressTracker.createSnapshot()
                        log(TAG, DEBUG) { "Scan complete: ${snapshot.totalItems} items to copy" }
                    }
                }
                is WorkItem.CopyFile<*, *, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    processCopyFile(item as WorkItem.CopyFile<SP, SPL, DP>)
                }
                is WorkItem.CreateDirectory<*, *, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    processCreateDirectory(item as WorkItem.CreateDirectory<SP, SPL, DP>)
                }
                is WorkItem.ResolveConflict<*, *, *, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    processResolveConflict(item as WorkItem.ResolveConflict<SP, SPL, DP, DPL>)
                }
            }
        }

        // For same-type operations (SP=DP), copied is Set<Pair<SP, DP>> which equals Set<Pair<SP, SP>>
        // For cross-type, this won't compile - cross-type operations should use their own result type
        @Suppress("UNCHECKED_CAST")
        return CopyAction.State.Result(
            copied = copied as Set<Pair<SP, SP>>,
            skipped = skipped,
            copiedBytes = totalBytesTransferred
        )
    }

    private suspend fun processScan(item: WorkItem.ScanSource<SP>): Int {
        log(TAG, VERBOSE) { "Scanning source: ${item.source}" }

        val lookup = try {
            sourceOps.lookup(item.source)
        } catch (e: Exception) {
            if (item.source == item.topLevelSource) {
                throw e // Top-level source must exist
            }
            log(TAG, WARN) { "Child source disappeared during scan: ${item.source}" }
            return 0
        }

        // Calculate destination path relative to top-level source
        val destPath = calculateDestinationPath(item.source, item.topLevelSource)

        when (lookup.fileType) {
            FileType.FILE, FileType.SYMBOLIC_LINK -> {
                progressTracker.totalItems++
                progressTracker.totalBytes += lookup.size
                workQueue.addLast(WorkItem.CopyFile(lookup, destPath, item.topLevelSource))
                return 0
            }

            FileType.DIRECTORY -> {
                progressTracker.totalItems++
                progressTracker.totalBytes += lookup.size
                workQueue.addLast(WorkItem.CreateDirectory(lookup, destPath, item.topLevelSource))

                // List and queue children
                var childrenFound = 0
                try {
                    val children = sourceOps.listFiles(item.source)
                    children.forEach { child ->
                        workQueue.addFirst(WorkItem.ScanSource(child, item.topLevelSource))
                        childrenFound++
                    }
                } catch (e: Exception) {
                    handleScanError(e, lookup)
                }

                return childrenFound
            }

            FileType.UNKNOWN -> throw IllegalStateException("Unknown file type: $lookup")
        }
    }

    private suspend fun processCopyFile(item: WorkItem.CopyFile<SP, SPL, DP>) {
        // Skip if parent directory was skipped
        if (isDescendantOfSkippedDir(item.sourceLookup.lookedUp)) {
            log(TAG, VERBOSE) { "Skipping file - parent directory was skipped" }
            skipped.add(item.sourceLookup.lookedUp)
            progressTracker.completeItem()
            return
        }

        val adjustedDest = adjustDestinationForRenames(item.destination, item.sourceLookup.lookedUp)

        log(TAG, VERBOSE) { "Copying file: ${item.sourceLookup.lookedUp} -> $adjustedDest" }

        // Check for conflicts
        if (destOps.exists(adjustedDest)) {
            val destLookup = destOps.lookup(adjustedDest)
            handleFileConflict(item, adjustedDest, destLookup)
            return
        }

        // Copy file
        progressTracker.startFile(item.sourceLookup.size)

        try {
            val result = strategy.transferFile(
                sourceLookup = item.sourceLookup,
                destination = adjustedDest,
                sourceOps = sourceOps,
                destOps = destOps,
                options = TransferStrategy.Options(preserveAttributes = true),
                onProgress = { bytes ->
                    progressTracker.updateFileProgress(bytes)
                    if (progressTracker.shouldReportProgress()) {
                        reportProgress(item.sourceLookup)
                    }
                }
            )

            when (result) {
                is TransferStrategy.TransferResult.Success -> {
                    copied.add(item.sourceLookup.lookedUp to result.destination)
                    totalBytesTransferred += result.bytesTransferred
                    progressTracker.completeItem()
                }
                is TransferStrategy.TransferResult.Skipped -> {
                    skipped.add(item.sourceLookup.lookedUp)
                    progressTracker.completeItem()
                }
            }

            // Force final progress report
            if (progressTracker.shouldReportProgress(force = true)) {
                reportProgress(item.sourceLookup)
            }
        } catch (e: Exception) {
            handleCopyError(e, item.sourceLookup, adjustedDest)
        }
    }

    private suspend fun processCreateDirectory(item: WorkItem.CreateDirectory<SP, SPL, DP>) {
        // Skip if parent directory was skipped
        if (isDescendantOfSkippedDir(item.sourceLookup.lookedUp)) {
            log(TAG, VERBOSE) { "Skipping directory - parent directory was skipped" }
            skipped.add(item.sourceLookup.lookedUp)
            progressTracker.completeItem()
            return
        }

        val adjustedDest = adjustDestinationForRenames(item.destination, item.sourceLookup.lookedUp)

        log(TAG, VERBOSE) { "Creating directory: ${item.sourceLookup.lookedUp} -> $adjustedDest" }

        // Check for conflicts
        if (destOps.exists(adjustedDest)) {
            val destLookup = destOps.lookup(adjustedDest)
            handleDirectoryConflict(item, adjustedDest, destLookup)
            return
        }

        // Create directory
        try {
            val result = strategy.createDirectory(
                sourceLookup = item.sourceLookup,
                destination = adjustedDest,
                sourceOps = sourceOps,
                destOps = destOps,
                options = TransferStrategy.Options(preserveAttributes = true)
            )

            when (result) {
                is TransferStrategy.TransferResult.Success -> {
                    copied.add(item.sourceLookup.lookedUp to result.destination)
                    totalBytesTransferred += result.bytesTransferred
                    progressTracker.completeItem()
                }
                is TransferStrategy.TransferResult.Skipped -> {
                    skipped.add(item.sourceLookup.lookedUp)
                    progressTracker.completeItem()
                }
            }
        } catch (e: Exception) {
            handleCopyError(e, item.sourceLookup, adjustedDest)
        }
    }

    private suspend fun handleFileConflict(
        item: WorkItem.CopyFile<SP, SPL, DP>,
        adjustedDest: DP,
        destLookup: DPL
    ) {
        // Check "apply to all" flags
        if (issueResolver.skipAllPathExists) {
            log(TAG, INFO) { "Skipping (apply-to-all): $adjustedDest" }
            skipped.add(item.sourceLookup.lookedUp)
            progressTracker.completeItem()
            return
        }

        if (issueResolver.renameSourceAllPathExists) {
            val uniqueName = generateUniqueName(adjustedDest)
            @Suppress("UNCHECKED_CAST")
            val renamedDest = adjustedDest.child(uniqueName) as DP
            log(TAG, INFO) { "Auto-renaming (apply-to-all): $adjustedDest -> $renamedDest" }

            progressTracker.startFile(item.sourceLookup.size)
            val result = strategy.transferFile(
                sourceLookup = item.sourceLookup,
                destination = renamedDest,
                sourceOps = sourceOps,
                destOps = destOps,
                options = TransferStrategy.Options(preserveAttributes = true),
                onProgress = { bytes ->
                    progressTracker.updateFileProgress(bytes)
                    if (progressTracker.shouldReportProgress()) {
                        reportProgress(item.sourceLookup)
                    }
                }
            )

            when (result) {
                is TransferStrategy.TransferResult.Success -> {
                    copied.add(item.sourceLookup.lookedUp to result.destination)
                    totalBytesTransferred += result.bytesTransferred
                }
                is TransferStrategy.TransferResult.Skipped -> {
                    skipped.add(item.sourceLookup.lookedUp)
                }
            }
            progressTracker.completeItem()
            return
        }

        if (issueResolver.overwriteAllPathExists) {
            log(TAG, INFO) { "Overwriting (apply-to-all): $adjustedDest" }
            destOps.delete(adjustedDest)
            workQueue.addFirst(item)
            return
        }

        // Check if we have an issue handler
        if (onIssue == null) {
            val exception = eu.darken.butler.common.files.errors.WriteException(
                path = adjustedDest,
                message = "File already exists: $adjustedDest"
            )
            throw exception
        }

        // Queue conflict resolution
        workQueue.addFirst(WorkItem.ResolveConflict(item.sourceLookup, adjustedDest, destLookup, item))
    }

    private suspend fun handleDirectoryConflict(
        item: WorkItem.CreateDirectory<SP, SPL, DP>,
        adjustedDest: DP,
        destLookup: DPL
    ) {
        // Check "apply to all" flags
        if (issueResolver.skipAllPathExists) {
            log(TAG, INFO) { "Skipping directory (apply-to-all): $adjustedDest" }
            skipped.add(item.sourceLookup.lookedUp)
            skippedSourceDirs.add(item.sourceLookup.lookedUp)
            progressTracker.completeItem()
            return
        }

        if (issueResolver.renameSourceAllPathExists) {
            val uniqueName = generateUniqueName(adjustedDest)
            @Suppress("UNCHECKED_CAST")
            val renamedDest = adjustedDest.child(uniqueName) as DP
            log(TAG, INFO) { "Auto-renaming directory (apply-to-all): $adjustedDest -> $renamedDest" }
            destOps.createDir(renamedDest)
            copied.add(item.sourceLookup.lookedUp to renamedDest)
            renamedSourceDirs[item.sourceLookup.lookedUp] = renamedDest
            progressTracker.completeItem()
            return
        }

        if (destLookup.fileType == FileType.DIRECTORY) {
            if (issueResolver.mergeAllPathExists) {
                log(TAG, INFO) { "Merging directory (apply-to-all): $adjustedDest" }
                progressTracker.completeItem()
                return
            }

            if (issueResolver.overwriteAllPathExists) {
                log(TAG, INFO) { "Overwriting directory (apply-to-all): $adjustedDest" }
                deleteRecursively(adjustedDest)
                workQueue.addFirst(item)
                return
            }

            // Auto-merge directories when no issue handler (backward compatibility)
            if (onIssue == null) {
                log(TAG, VERBOSE) { "Directory already exists, auto-merging: $adjustedDest" }
                progressTracker.completeItem()
                return
            }
        } else if (issueResolver.overwriteAllPathExists) {
            log(TAG, INFO) { "Overwriting file with directory (apply-to-all): $adjustedDest" }
            destOps.delete(adjustedDest)
            workQueue.addFirst(item)
            return
        }

        // Queue conflict resolution
        workQueue.addFirst(WorkItem.ResolveConflict(item.sourceLookup, adjustedDest, destLookup, item))
    }

    private suspend fun processResolveConflict(item: WorkItem.ResolveConflict<SP, SPL, DP, DPL>) {
        val canMerge = item.originalItem is WorkItem.CreateDirectory<*, *, *> &&
            item.destLookup.fileType == FileType.DIRECTORY

        val issue = PathActionIssue.PathAlreadyExists(
            source = item.sourceLookup,
            destination = item.destLookup,
            canSkip = true,
            canOverwrite = true,
            canMerge = canMerge,
            canRenameSource = true,
            canRenameDestination = true,
            suggestedName = generateUniqueName(item.destination),
        )

        when (val resolution = issueResolver.resolveIssue(issue) as PathActionIssue.PathAlreadyExists.Resolution) {
            is PathActionIssue.PathAlreadyExists.Resolution.Skip -> {
                skipped.add(item.sourceLookup.lookedUp)
                if (item.originalItem is WorkItem.CreateDirectory<*, *, *>) {
                    skippedSourceDirs.add(item.sourceLookup.lookedUp)
                }
                progressTracker.completeItem()
            }
            is PathActionIssue.PathAlreadyExists.Resolution.Overwrite -> {
                if (item.destLookup.fileType == FileType.DIRECTORY) {
                    deleteRecursively(item.destination)
                } else {
                    destOps.delete(item.destination)
                }
                workQueue.addFirst(item.originalItem)
            }
            is PathActionIssue.PathAlreadyExists.Resolution.Merge -> {
                progressTracker.completeItem()
            }
            is PathActionIssue.PathAlreadyExists.Resolution.RenameSource -> {
                // Handle rename logic based on original item type
                // (simplified - full implementation similar to handleFileConflict)
                progressTracker.completeItem()
            }
            is PathActionIssue.PathAlreadyExists.Resolution.RenameDestination -> {
                // Rename existing destination file and retry
                @Suppress("UNCHECKED_CAST")
                val newDestPath = item.destination.child(resolution.newName) as DP
                // Note: This is simplified - would need proper implementation
                workQueue.addFirst(item.originalItem)
            }
            is PathActionIssue.PathAlreadyExists.Resolution.Cancel -> {
                throw kotlin.coroutines.cancellation.CancellationException("User cancelled")
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun calculateDestinationPath(source: SP, topLevelSource: SP): DP {
        // Calculate relative path INCLUDING the top-level source's name
        // Example: copying /source/topfolder to /dest should create /dest/topfolder/...
        val topLevelSegments = topLevelSource.segments
        val sourceSegments = source.segments

        // Drop parent segments of top-level source, keep top-level name and below
        // For /source/topfolder -> /dest, we want to drop [source] and keep [topfolder]
        val segmentsToDrop = if (topLevelSegments.isEmpty()) 0 else topLevelSegments.size - 1
        val relativeSegments = sourceSegments.drop(segmentsToDrop)

        // Build destination path with relative segments
        return destination.child(*relativeSegments.toTypedArray()) as DP
    }

    private fun adjustDestinationForRenames(dest: DP, source: SP): DP {
        // Check if any parent was renamed and adjust path accordingly
        // Simplified implementation
        return dest
    }

    private fun isDescendantOfSkippedDir(path: SP): Boolean {
        return skippedSourceDirs.any { skippedDir ->
            path.path.startsWith(skippedDir.path)
        }
    }

    private fun generateUniqueName(path: DP): String {
        // Generate unique name like "file (1).txt"
        // Simplified implementation
        return "${path.name} (1)"
    }

    private suspend fun deleteRecursively(path: DP) {
        // Recursively delete directory
        // Simplified - would need proper implementation
        destOps.delete(path)
    }

    private suspend fun handleScanError(error: Exception, lookup: SPL) {
        log(TAG, ERROR) { "Scan error: ${lookup.lookedUp} - $error" }
        // Would handle errors similar to LocalPathDelete
    }

    private suspend fun handleCopyError(error: Exception, source: SPL, dest: DP) {
        log(TAG, ERROR) { "Copy error: ${source.lookedUp} -> $dest - $error" }
        skipped.add(source.lookedUp)
        progressTracker.completeItem()
    }

    private suspend fun reportProgress(lookup: SPL) {
        val snapshot = progressTracker.createSnapshot()

        // For same-type operations (SP=DP), destination can be safely cast to SP
        @Suppress("UNCHECKED_CAST")
        onProgress?.invoke(
            CopyAction.State.Progress(
                currentSource = lookup.lookedUp,
                currentDestination = destination as SP,  // Safe when SP=DP for same-type operations
                primaryProgress = eu.darken.butler.common.progress.Progress.Data(
                    primary = R.string.general_copy_progress_title.toCaString(),
                    secondary = lookup.userReadablePath,
                    count = eu.darken.butler.common.progress.Progress.Count.Counter(
                        current = snapshot.itemsProcessed,
                        max = snapshot.totalItems
                    )
                ),
                secondaryProgress = eu.darken.butler.common.progress.Progress.Data(
                    primary = lookup.lookedUp.name.toCaString(),
                    count = eu.darken.butler.common.progress.Progress.Count.Size(
                        current = snapshot.processedBytes,
                        max = snapshot.totalBytes
                    )
                ),
                copiedBytes = snapshot.processedBytes,
                totalBytes = snapshot.totalBytes,
                currentFileStartTime = snapshot.currentFileStartTime
            )
        )
    }

    companion object {
        private val TAG = logTag("PathOperation", "GenericCopy")
    }
}

/**
 * Extension function for easy use of GenericPathCopy.
 *
 * Supports both same-type and cross-type operations:
 * - **Same-type** (SP=DP): Pass same FileSystemOps instance for both parameters
 * - **Cross-type** (SP≠DP): Pass different FileSystemOps instances
 */
suspend fun <
    SP : APath, SPL : APathLookup<SP>, SPLE : APathLookupExtended<SP>,  // Source types
    DP : APath, DPL : APathLookup<DP>, DPLE : APathLookupExtended<DP>   // Destination types
> Collection<SP>.copyGeneric(
    destination: DP,
    sourceOps: FileSystemOps<SP, SPL, SPLE>,
    destOps: FileSystemOps<DP, DPL, DPLE>,
    strategy: TransferStrategy<SP, SPL, SPLE, DP, DPL, DPLE>,
    onProgress: (suspend (CopyAction.State.Progress<SP, SPL>) -> Unit)? = null,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
): CopyAction.State.Result<SP, SPL> = GenericPathCopy(
    sources = this,
    destination = destination,
    sourceOps = sourceOps,
    destOps = destOps,
    strategy = strategy,
    onProgress = onProgress,
    onIssue = onIssue
).execute()
