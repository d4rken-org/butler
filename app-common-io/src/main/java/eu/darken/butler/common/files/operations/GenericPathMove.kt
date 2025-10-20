package eu.darken.butler.common.files.operations

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.local.operations.core.PathOperationIssueResolver
import eu.darken.butler.common.files.local.operations.core.PathOperationProgressTracker
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.io.R
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

/**
 * Generic move operation that works with any path type.
 *
 * Move is similar to copy but with two key differences:
 * 1. Source files are deleted after successful transfer
 * 2. Source directories are cleaned up (deleted) after all children are moved
 *
 * The operation attempts to use platform-specific atomic move operations where
 * possible (e.g., LocalPath uses Files.move() with ATOMIC_MOVE), falling back
 * to copy+delete when atomic move is not available (e.g., across file systems).
 *
 * ## Algorithm
 *
 * 1. **Scan Phase**: Walk source tree, calculate total size
 * 2. **Transfer Phase**: Move files using TransferStrategy (may be atomic or copy+delete)
 * 3. **Cleanup Phase**: Delete source directories in post-order (children first)
 * 4. **Progress Reporting**: Report progress with throttling
 * 5. **Conflict Resolution**: Handle conflicts via user callback
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
 * @param DP The destination path type (LocalPath, SAFPath, etc.)
 * @param DPL The destination path lookup type (LocalPathLookup, SAFPathLookup, etc.)
 */
internal class GenericPathMove<
    SP : APath<SP>, SPL : APathLookup<SP>,  // Source types
    DP : APath<DP>, DPL : APathLookup<DP>   // Destination types
    >(
    private val sources: Collection<SP>,
    private val destination: DP,
    private val sourceOps: FileSystemOps<SP, SPL>,
    private val destOps: FileSystemOps<DP, DPL>,
    private val strategy: TransferStrategy<SP, SPL, DP, DPL>,
    private val options: TransferStrategy.Options,
    private val onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?
) {

    private val moved = linkedSetOf<Pair<SPL, APathLookup<DP>>>()
    private val skipped = linkedSetOf<SPL>()
    private var totalBytesTransferred = 0L

    // Shared components
    private val progressTracker = PathOperationProgressTracker()
    private val issueResolver = PathOperationIssueResolver(onIssue)
    private val errorHandler = TransferErrorHandler()
    private val pathCalculator = TransferPathCalculator()
    private val conflictResolver = TransferConflictResolver<SP, SPL, DP, DPL>(
        destOps = destOps,
        issueResolver = issueResolver,
        progressTracker = progressTracker,
        tag = TAG
    )

    init {
        log(TAG, INFO) {
            "GenericPathMove init: sources=${sources.size}, options=$options, onIssue=${onIssue != null}"
        }
    }

    // Track directories for cleanup
    private val sourceDirectories = ArrayDeque<SP>() // Post-order for deletion
    private val skippedSourceDirs = mutableSetOf<SP>()
    private val renamedSourceDirs = mutableMapOf<SP, DP>()

    // Scan tracking
    private var scanItemsRemaining = 0

    // Destination state
    private var destinationExistedAsDirectory = false

    // Work queue for processing operations
    private var workQueue = ArrayDeque<WorkItem>()

    /**
     * Sealed hierarchy of work items for the move queue.
     */
    private sealed class WorkItem {
        /**
         * Scan a source path and queue children for moving.
         *
         * @param source The path to scan
         * @param topLevelSource The top-level source (for path calculations)
         */
        data class ScanSource<SP : APath<SP>>(
            val source: SP,
            val topLevelSource: SP,
        ) : WorkItem()

        /**
         * Move a file to destination.
         *
         * @param sourceLookup Source file metadata
         * @param destination Destination path
         * @param topLevelSource Top-level source (for error reporting)
         */
        data class MoveFile<SP : APath<SP>, SPL : APathLookup<SP>, DP : APath<DP>>(
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
        data class CreateDirectory<SP : APath<SP>, SPL : APathLookup<SP>, DP : APath<DP>>(
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
        data class ResolveConflict<SP : APath<SP>, SPL : APathLookup<SP>, DP : APath<DP>, DPL : APathLookup<DP>>(
            val sourceLookup: SPL,
            val destination: DP,
            val destLookup: DPL,
            val originalItem: WorkItem,
        ) : WorkItem()
    }

    fun execute(): Flow<MoveAction.State<SP, SPL, DP, DPL>> = flow {
        log(TAG, DEBUG) { "execute(): Moving ${sources.size} sources to $destination" }

        // Check if destination exists and is a directory (for path calculation logic)
        val destLookup = destOps.lookup(destination, LookupOptions(fallbackToUnknown = true))
        destinationExistedAsDirectory = destLookup.fileType == FileType.DIRECTORY

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
                    val childrenAdded = processScan(item as WorkItem.ScanSource<SP>, ::emit)
                    scanItemsRemaining += childrenAdded

                    if (scanItemsRemaining == 0) {
                        val snapshot = progressTracker.createSnapshot()
                        log(TAG, DEBUG) { "Scan complete: ${snapshot.totalItems} items to move" }
                    }
                }

                is WorkItem.MoveFile<*, *, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    processMoveFile(item as WorkItem.MoveFile<SP, SPL, DP>, ::emit)
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

        // Cleanup phase: Delete empty source directories
        cleanupSourceDirectories()

        emit(
            MoveAction.State.Result(
                movedFiles = moved,
                skippedFiles = skipped,
                bytesMoved = progressTracker.processedBytes
            )
        )
    }

    private suspend fun processScan(
        item: WorkItem.ScanSource<SP>,
        emit: suspend (MoveAction.State<SP, SPL, DP, DPL>) -> Unit
    ): Int {
        log(TAG, VERBOSE) { "Scanning source: ${item.source}" }

        val lookup = try {
            sourceOps.lookup(item.source, LookupOptions.BASE)
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
                progressTracker.totalBytes += lookup.size ?: 0L
                workQueue.addLast(WorkItem.MoveFile(lookup, destPath, item.topLevelSource))

                // Report scan progress with throttling
                if (progressTracker.shouldReportProgress()) {
                    reportScanProgress(lookup, emit)
                }

                return 0
            }

            FileType.DIRECTORY -> {
                progressTracker.totalItems++
                progressTracker.totalBytes += lookup.size ?: 0L

                // Add directory to cleanup queue (post-order)
                sourceDirectories.addFirst(item.source)

                // Report scan progress with throttling
                if (progressTracker.shouldReportProgress()) {
                    reportScanProgress(lookup, emit)
                }

                // List and queue children
                var childrenFound = 0
                try {
                    val children = sourceOps.listFiles(item.source)
                    children.forEach { child ->
                        workQueue.addFirst(WorkItem.ScanSource(child, item.topLevelSource))
                        childrenFound++
                    }
                } catch (e: Exception) {
                    handleScanError(e, lookup, item)
                    return 0
                }

                // Only queue CreateDirectory AFTER successfully scanning children
                // This prevents duplicate directory creation when scan errors are retried
                workQueue.addLast(WorkItem.CreateDirectory(lookup, destPath, item.topLevelSource))

                return childrenFound
            }

            FileType.UNKNOWN -> throw IllegalStateException("Unknown file type: $lookup")
        }
    }

    private suspend fun processMoveFile(
        item: WorkItem.MoveFile<SP, SPL, DP>,
        emit: suspend (MoveAction.State<SP, SPL, DP, DPL>) -> Unit
    ) {
        // Skip if parent directory was skipped
        if (isDescendantOfSkippedDir(item.sourceLookup.lookedUp)) {
            log(TAG, VERBOSE) { "Skipping file - parent directory was skipped" }
            skipped.add(item.sourceLookup)
            progressTracker.completeItem()
            return
        }

        val adjustedDest = adjustDestinationForRenames(item.destination, item.sourceLookup.lookedUp)

        log(TAG, VERBOSE) { "Moving file: ${item.sourceLookup.lookedUp} -> $adjustedDest" }

        // Check for conflicts
        val destLookup = destOps.lookup(adjustedDest, LookupOptions.BASE.copy(fallbackToUnknown = true))
        if (destLookup.fileType != FileType.UNKNOWN) {
            handleFileConflict(item, adjustedDest, destLookup)
            return
        }

        // Move file (strategy handles whether it's atomic or copy+delete)
        // Only start tracking if not already started (handles retry case)
        if (progressTracker.currentFileSize == 0L) {
            progressTracker.startFile(item.sourceLookup.size ?: 0L)
        }

        try {
            val result = strategy.transferFile(
                sourceLookup = item.sourceLookup,
                destination = adjustedDest,
                sourceOps = sourceOps,
                destOps = destOps,
                options = options,
                onProgress = { bytes ->
                    progressTracker.updateFileProgress(bytes)
                    if (progressTracker.shouldReportProgress()) {
                        reportProgress(item.sourceLookup, adjustedDest, emit)
                    }
                }
            )

            when (result) {
                is TransferStrategy.TransferResult.Success -> {
                    // Use destinationLookup from result if available, otherwise lookup
                    val destLookup = result.destinationLookup
                        ?: destOps.lookup(result.destination, LookupOptions.BASE)
                    moved.add(item.sourceLookup to destLookup)
                    totalBytesTransferred += result.bytesTransferred
                    progressTracker.completeFile()
                    progressTracker.completeItem()
                }

                is TransferStrategy.TransferResult.Skipped -> {
                    skipped.add(item.sourceLookup)
                    progressTracker.completeItem()
                }
            }

            // Force final progress report
            if (progressTracker.shouldReportProgress(force = true)) {
                reportProgress(item.sourceLookup, adjustedDest, emit)
            }
        } catch (e: Exception) {
            handleMoveError(e, item)
        }
    }

    private suspend fun processCreateDirectory(item: WorkItem.CreateDirectory<SP, SPL, DP>) {
        if (isDescendantOfSkippedDir(item.sourceLookup.lookedUp)) {
            log(TAG, VERBOSE) { "Skipping directory - parent was skipped" }
            skipped.add(item.sourceLookup)
            skippedSourceDirs.add(item.sourceLookup.lookedUp)
            progressTracker.completeItem()
            return
        }

        val adjustedDest = adjustDestinationForRenames(item.destination, item.sourceLookup.lookedUp)

        log(TAG, VERBOSE) { "Creating directory: ${item.sourceLookup.lookedUp} -> $adjustedDest" }

        // Check for conflicts
        val destLookup = destOps.lookup(adjustedDest, LookupOptions.BASE.copy(fallbackToUnknown = true))
        if (destLookup.fileType != FileType.UNKNOWN) {
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
                options = options
            )

            when (result) {
                is TransferStrategy.TransferResult.Success -> {
                    // Use destinationLookup from result if available, otherwise lookup
                    val destLookup = result.destinationLookup
                        ?: destOps.lookup(result.destination, LookupOptions.BASE)
                    moved.add(item.sourceLookup to destLookup)
                    totalBytesTransferred += result.bytesTransferred
                    progressTracker.completeItem()
                }

                is TransferStrategy.TransferResult.Skipped -> {
                    skipped.add(item.sourceLookup)
                    progressTracker.completeItem()
                }
            }
        } catch (e: Exception) {
            handleDirectoryError(e, item)
        }
    }

    private suspend fun cleanupSourceDirectories() {
        log(TAG, DEBUG) { "Cleaning up ${sourceDirectories.size} source directories" }

        // Delete in post-order (children deleted before parents)
        while (sourceDirectories.isNotEmpty()) {
            val dir = sourceDirectories.removeFirst()

            // Skip if directory was skipped during move
            if (dir in skippedSourceDirs) {
                log(TAG, VERBOSE) { "Skipping cleanup of skipped directory: $dir" }
                continue
            }

            try {
                // Check if directory still exists and is empty
                val dirLookup = sourceOps.lookup(dir, LookupOptions(fallbackToUnknown = true))
                if (dirLookup.fileType == FileType.UNKNOWN) {
                    log(TAG, VERBOSE) { "Directory already deleted: $dir" }
                    continue
                }

                // Check if empty (should be, since we moved all children)
                val children = sourceOps.listFiles(dir)
                if (children.isNotEmpty()) {
                    log(TAG, WARN) { "Directory not empty, skipping cleanup: $dir (${children.size} children)" }
                    continue
                }

                // Delete empty directory
                sourceOps.delete(dir)
                log(TAG, VERBOSE) { "Deleted source directory: $dir" }
            } catch (e: Exception) {
                log(TAG, ERROR) { "Failed to cleanup source directory $dir: $e" }
                // Continue with other directories
            }
        }
    }

    private suspend fun handleFileConflict(
        item: WorkItem.MoveFile<SP, SPL, DP>,
        adjustedDest: DP,
        destLookup: DPL
    ) {
        conflictResolver.handleFileConflict(
            sourceLookup = item.sourceLookup,
            destination = adjustedDest,
            destLookup = destLookup,
            onSkip = { skipped.add(it) },
            onRename = { renamedDest ->
                val renamedItem = WorkItem.MoveFile(
                    sourceLookup = item.sourceLookup,
                    destination = renamedDest,
                    topLevelSource = item.topLevelSource
                )
                workQueue.addFirst(renamedItem)
            },
            onOverwrite = { workQueue.addFirst(item) },
            onResolveConflict = {
                workQueue.addFirst(
                    WorkItem.ResolveConflict(
                        item.sourceLookup,
                        adjustedDest,
                        destLookup,
                        item
                    )
                )
            },
            onIssue = onIssue
        )
    }

    private suspend fun handleDirectoryConflict(
        item: WorkItem.CreateDirectory<SP, SPL, DP>,
        adjustedDest: DP,
        destLookup: DPL
    ) {
        conflictResolver.handleDirectoryConflict(
            sourceLookup = item.sourceLookup,
            destination = adjustedDest,
            destLookup = destLookup,
            onSkip = { sourceLookup, markAsSkippedDir ->
                skipped.add(sourceLookup)
                if (markAsSkippedDir) skippedSourceDirs.add(sourceLookup.lookedUp)
            },
            onRename = { renamedDest ->
                renamedSourceDirs[item.sourceLookup.lookedUp] = renamedDest
                val updatedItem = WorkItem.CreateDirectory(
                    sourceLookup = item.sourceLookup,
                    destination = renamedDest,
                    topLevelSource = item.topLevelSource
                )
                workQueue.addFirst(updatedItem)
            },
            onMerge = { moved.add(item.sourceLookup to destLookup) },
            onOverwrite = { recursive -> workQueue.addFirst(item) },
            onResolveConflict = {
                workQueue.addFirst(
                    WorkItem.ResolveConflict(
                        item.sourceLookup,
                        adjustedDest,
                        destLookup,
                        item
                    )
                )
            },
            onIssue = onIssue
        )
    }

    private suspend fun processResolveConflict(item: WorkItem.ResolveConflict<SP, SPL, DP, DPL>) {
        val canMerge = item.originalItem is WorkItem.CreateDirectory<*, *, *> &&
            item.destLookup.fileType == FileType.DIRECTORY

        conflictResolver.processResolveConflict(
            sourceLookup = item.sourceLookup,
            destination = item.destination,
            destLookup = item.destLookup,
            canMerge = canMerge,
            onSkip = { sourceLookup, markAsSkippedDir ->
                skipped.add(sourceLookup)
                if (markAsSkippedDir) skippedSourceDirs.add(sourceLookup.lookedUp)
            },
            onOverwrite = { recursive -> workQueue.addFirst(item.originalItem) },
            onMerge = { moved.add(item.sourceLookup to item.destLookup) },
            onRenameSource = { renamedDest ->
                when (val originalItem = item.originalItem) {
                    is WorkItem.MoveFile<*, *, *> -> {
                        @Suppress("UNCHECKED_CAST")
                        val moveItem = originalItem as WorkItem.MoveFile<SP, SPL, DP>
                        val updatedItem = WorkItem.MoveFile(
                            sourceLookup = moveItem.sourceLookup,
                            destination = renamedDest,
                            topLevelSource = moveItem.topLevelSource
                        )
                        workQueue.addFirst(updatedItem)
                    }

                    is WorkItem.CreateDirectory<*, *, *> -> {
                        @Suppress("UNCHECKED_CAST")
                        val dirItem = originalItem as WorkItem.CreateDirectory<SP, SPL, DP>
                        val updatedItem = WorkItem.CreateDirectory(
                            sourceLookup = dirItem.sourceLookup,
                            destination = renamedDest,
                            topLevelSource = dirItem.topLevelSource
                        )
                        renamedSourceDirs[dirItem.sourceLookup.lookedUp] = renamedDest
                        workQueue.addFirst(updatedItem)
                    }

                    else -> {
                        log(TAG, ERROR) { "Unexpected original item type: $originalItem" }
                    }
                }
            },
            onRenameDestination = { workQueue.addFirst(item.originalItem) }
        )
    }

    private fun calculateDestinationPath(source: SP, topLevelSource: SP): DP {
        return pathCalculator.calculateDestinationPath(
            source = source,
            topLevelSource = topLevelSource,
            destination = destination,
            sources = sources,
            destinationExistedAsDirectory = destinationExistedAsDirectory
        )
    }

    private fun adjustDestinationForRenames(dest: DP, source: SP): DP {
        return pathCalculator.adjustDestinationForRenames(
            dest = dest,
            source = source,
            renamedSourceDirs = renamedSourceDirs
        )
    }

    private fun isDescendantOfSkippedDir(path: SP): Boolean {
        return pathCalculator.isDescendantOfSkippedDir(
            path = path,
            skippedSourceDirs = skippedSourceDirs
        )
    }

    private suspend fun handleScanError(error: Exception, lookup: SPL, originalItem: WorkItem.ScanSource<SP>) {
        errorHandler.handleScanError(
            error = error,
            lookup = lookup,
            issueResolver = issueResolver,
            onSkip = {
                skipped.add(it)
                skippedSourceDirs.add(it.lookedUp)
            },
            onRetry = { workQueue.addFirst(originalItem) },
            onIssue = onIssue,
            tag = TAG
        )
    }

    private suspend fun handleMoveError(error: Exception, originalItem: WorkItem.MoveFile<SP, SPL, DP>) {
        errorHandler.handleError(
            error = error,
            lookup = originalItem.sourceLookup,
            issueResolver = issueResolver,
            progressTracker = progressTracker,
            onSkip = { skipped.add(it) },
            onRetry = { workQueue.addFirst(originalItem) },
            canRetry = true,
            onIssue = onIssue,
            tag = TAG
        )
    }

    private suspend fun handleDirectoryError(error: Exception, originalItem: WorkItem.CreateDirectory<SP, SPL, DP>) {
        errorHandler.handleError(
            error = error,
            lookup = originalItem.sourceLookup,
            issueResolver = issueResolver,
            progressTracker = progressTracker,
            onSkip = {
                skipped.add(it)
                skippedSourceDirs.add(it.lookedUp)
            },
            onRetry = { workQueue.addFirst(originalItem) },
            canRetry = true,
            onIssue = onIssue,
            tag = TAG
        )
    }

    private suspend fun reportScanProgress(lookup: SPL, emit: suspend (MoveAction.State<SP, SPL, DP, DPL>) -> Unit) {
        val snapshot = progressTracker.createSnapshot()

        emit(
            MoveAction.State.Progress(
                currentSource = lookup,
                currentDestination = null,
                primaryProgress = eu.darken.butler.common.progress.Progress.Data(
                    primary = R.string.general_scan_progress_title.toCaString(),
                    secondary = lookup.userReadablePath,
                    count = eu.darken.butler.common.progress.Progress.Count.Counter(
                        max = snapshot.totalItems
                    )
                ),
                totalBytes = snapshot.totalBytes,
            )
        )
    }

    private suspend fun reportProgress(
        lookup: SPL,
        destination: DP,
        emit: suspend (MoveAction.State<SP, SPL, DP, DPL>) -> Unit
    ) {
        val snapshot = progressTracker.createSnapshot()

        emit(
            MoveAction.State.Progress(
                currentSource = lookup,
                currentDestination = destination,
                primaryProgress = eu.darken.butler.common.progress.Progress.Data(
                    primary = R.string.general_move_progress_title.toCaString(),
                    secondary = lookup.userReadablePath,
                    count = eu.darken.butler.common.progress.Progress.Count.Counter(
                        current = snapshot.itemsProcessed,
                        max = snapshot.totalItems
                    )
                ),
                secondaryProgress = eu.darken.butler.common.progress.Progress.Data(
                    primary = lookup.lookedUp.name.toCaString(),
                    count = eu.darken.butler.common.progress.Progress.Count.Size(
                        current = snapshot.currentFileBytes,
                        max = snapshot.currentFileSize
                    )
                ),
                movedBytes = snapshot.processedBytes,
                totalBytes = snapshot.totalBytes,
                currentFileSize = snapshot.currentFileSize,
                currentFileBytes = snapshot.currentFileBytes,
                currentFileStartTime = snapshot.currentFileStartTime
            )
        )
    }

    companion object {
        private val TAG = logTag("PathOperation", "GenericMove")
    }
}

/**
 * Extension function for easy use of GenericPathMove.
 *
 * Supports both same-type and cross-type operations:
 * - **Same-type** (SP=DP): Pass same FileSystemOps instance for both parameters
 * - **Cross-type** (SP≠DP): Pass different FileSystemOps instances
 */
fun <
    SP : APath<SP>, SPL : APathLookup<SP>,  // Source types
    DP : APath<DP>, DPL : APathLookup<DP>   // Destination types
    > Collection<SP>.moveGeneric(
    destination: DP,
    sourceOps: FileSystemOps<SP, SPL>,
    destOps: FileSystemOps<DP, DPL>,
    strategy: TransferStrategy<SP, SPL, DP, DPL>,
    options: TransferStrategy.Options = TransferStrategy.Options(),
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
): Flow<MoveAction.State<SP, SPL, DP, DPL>> = GenericPathMove(
    sources = this,
    destination = destination,
    sourceOps = sourceOps,
    destOps = destOps,
    strategy = strategy,
    options = options,
    onIssue = onIssue
).execute()
