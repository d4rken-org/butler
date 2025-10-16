package eu.darken.butler.common.files.operations

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.APathLookupExtended
import eu.darken.butler.common.files.FileSystemOps
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
 * @param SPLE The source path lookup extended type (LocalPathLookupExtended, SAFPathLookupExtended, etc.)
 * @param DP The destination path type (LocalPath, SAFPath, etc.)
 * @param DPL The destination path lookup type (LocalPathLookup, SAFPathLookup, etc.)
 * @param DPLE The destination path lookup extended type (LocalPathLookupExtended, SAFPathLookupExtended, etc.)
 */
internal class GenericPathMove<
    SP : APath<SP>, SPL : APathLookup<SP>, SPLE : APathLookupExtended<SP>,  // Source types
    DP : APath<DP>, DPL : APathLookup<DP>, DPLE : APathLookupExtended<DP>   // Destination types
>(
    private val sources: Collection<SP>,
    private val destination: DP,
    private val sourceOps: FileSystemOps<SP, SPL, SPLE>,
    private val destOps: FileSystemOps<DP, DPL, DPLE>,
    private val strategy: TransferStrategy<SP, SPL, SPLE, DP, DPL, DPLE>,
    private val options: TransferStrategy.Options,
    private val onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?
) {

    private val moved = linkedSetOf<Pair<SP, DP>>()
    private val skipped = linkedSetOf<SP>()
    private var totalBytesTransferred = 0L

    // Shared components
    private val progressTracker = PathOperationProgressTracker()
    private val issueResolver = PathOperationIssueResolver(onIssue)

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

    fun execute(): Flow<MoveAction.State<SP, SPL>> = flow {
        log(TAG, DEBUG) { "execute(): Moving ${sources.size} sources to $destination" }

        // Check if destination exists and is a directory (for path calculation logic)
        if (destOps.exists(destination)) {
            val destLookup = destOps.lookup(destination)
            destinationExistedAsDirectory = destLookup.fileType == FileType.DIRECTORY
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

        // For same-type operations (SP=DP), moved is Set<Pair<SP, DP>> which equals Set<Pair<SP, SP>>
        @Suppress("UNCHECKED_CAST")
        emit(MoveAction.State.Result(
            movedFiles = moved as Set<Pair<SP, SP>>,
            skippedFiles = skipped,
            bytesMoved = progressTracker.processedBytes
        ))
    }

    private suspend fun processScan(item: WorkItem.ScanSource<SP>, emit: suspend (MoveAction.State<SP, SPL>) -> Unit): Int {
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
                workQueue.addLast(WorkItem.MoveFile(lookup, destPath, item.topLevelSource))

                // Report scan progress with throttling
                if (progressTracker.shouldReportProgress()) {
                    reportScanProgress(lookup, emit)
                }

                return 0
            }

            FileType.DIRECTORY -> {
                progressTracker.totalItems++
                progressTracker.totalBytes += lookup.size

                // Add directory to cleanup queue (post-order)
                sourceDirectories.addFirst(item.source)

                workQueue.addLast(WorkItem.CreateDirectory(lookup, destPath, item.topLevelSource))

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
                    handleScanError(e, lookup)
                }

                return childrenFound
            }

            FileType.UNKNOWN -> throw IllegalStateException("Unknown file type: $lookup")
        }
    }

    private suspend fun processMoveFile(item: WorkItem.MoveFile<SP, SPL, DP>, emit: suspend (MoveAction.State<SP, SPL>) -> Unit) {
        // Skip if parent directory was skipped
        if (isDescendantOfSkippedDir(item.sourceLookup.lookedUp)) {
            log(TAG, VERBOSE) { "Skipping file - parent directory was skipped" }
            skipped.add(item.sourceLookup.lookedUp)
            progressTracker.completeItem()
            return
        }

        val adjustedDest = adjustDestinationForRenames(item.destination, item.sourceLookup.lookedUp)

        log(TAG, VERBOSE) { "Moving file: ${item.sourceLookup.lookedUp} -> $adjustedDest" }

        // Check for conflicts
        if (destOps.exists(adjustedDest)) {
            val destLookup = destOps.lookup(adjustedDest)
            handleFileConflict(item, adjustedDest, destLookup)
            return
        }

        // Move file (strategy handles whether it's atomic or copy+delete)
        // Only start tracking if not already started (handles retry case)
        if (progressTracker.currentFileSize == 0L) {
            progressTracker.startFile(item.sourceLookup.size)
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
                        reportProgress(item.sourceLookup, emit)
                    }
                }
            )

            when (result) {
                is TransferStrategy.TransferResult.Success -> {
                    moved.add(item.sourceLookup.lookedUp to result.destination)
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
                reportProgress(item.sourceLookup, emit)
            }
        } catch (e: Exception) {
            handleMoveError(e, item)
        }
    }

    private suspend fun processCreateDirectory(item: WorkItem.CreateDirectory<SP, SPL, DP>) {
        if (isDescendantOfSkippedDir(item.sourceLookup.lookedUp)) {
            log(TAG, VERBOSE) { "Skipping directory - parent was skipped" }
            skipped.add(item.sourceLookup.lookedUp)
            skippedSourceDirs.add(item.sourceLookup.lookedUp)
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
                options = options
            )

            when (result) {
                is TransferStrategy.TransferResult.Success -> {
                    moved.add(item.sourceLookup.lookedUp to result.destination)
                    totalBytesTransferred += result.bytesTransferred
                    progressTracker.completeItem()
                }
                is TransferStrategy.TransferResult.Skipped -> {
                    skipped.add(item.sourceLookup.lookedUp)
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
                if (!sourceOps.exists(dir)) {
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
        // Similar to GenericPathCopy but for move
        if (issueResolver.skipAllPathExists) {
            log(TAG, INFO) { "Skipping (apply-to-all): $adjustedDest" }
            skipped.add(item.sourceLookup.lookedUp)
            progressTracker.completeItem()
            return
        }

        if (issueResolver.renameSourceAllPathExists) {
            val uniqueName = generateUniqueName(adjustedDest)
            val parentPath = adjustedDest.parent!!
            val renamedDest = parentPath.child(uniqueName)
            log(TAG, INFO) { "Auto-renaming (apply-to-all): $adjustedDest -> $renamedDest" }

            // Create new work item with renamed destination and requeue
            val renamedItem = WorkItem.MoveFile(
                sourceLookup = item.sourceLookup,
                destination = renamedDest,
                topLevelSource = item.topLevelSource
            )
            workQueue.addFirst(renamedItem)
            return
        }

        if (issueResolver.overwriteAllPathExists) {
            log(TAG, INFO) { "Overwriting (apply-to-all): $adjustedDest" }
            destOps.delete(adjustedDest)
            workQueue.addFirst(item)
            return
        }

        if (onIssue == null) {
            throw eu.darken.butler.common.files.errors.WriteException(
                path = adjustedDest,
                message = "File already exists: $adjustedDest"
            )
        }

        workQueue.addFirst(WorkItem.ResolveConflict(item.sourceLookup, adjustedDest, destLookup, item))
    }

    private suspend fun handleDirectoryConflict(
        item: WorkItem.CreateDirectory<SP, SPL, DP>,
        adjustedDest: DP,
        destLookup: DPL
    ) {
        if (issueResolver.skipAllPathExists) {
            log(TAG, INFO) { "Skipping directory (apply-to-all): $adjustedDest" }
            skipped.add(item.sourceLookup.lookedUp)
            skippedSourceDirs.add(item.sourceLookup.lookedUp)
            progressTracker.completeItem()
            return
        }

        if (issueResolver.renameSourceAllPathExists) {
            val uniqueName = generateUniqueName(adjustedDest)
            val parentPath = adjustedDest.parent!!
            val renamedDest = parentPath.child(uniqueName)
            log(TAG, INFO) { "Auto-renaming directory (apply-to-all): $adjustedDest -> $renamedDest" }

            // Track renamed directory for child path adjustments
            renamedSourceDirs[item.sourceLookup.lookedUp] = renamedDest

            // Create new work item with renamed destination and requeue
            val updatedItem = WorkItem.CreateDirectory(
                sourceLookup = item.sourceLookup,
                destination = renamedDest,
                topLevelSource = item.topLevelSource
            )
            workQueue.addFirst(updatedItem)
            return
        }

        if (destLookup.fileType == FileType.DIRECTORY) {
            if (issueResolver.mergeAllPathExists) {
                log(TAG, INFO) { "Merging directory (apply-to-all): $adjustedDest" }
                moved.add(item.sourceLookup.lookedUp to adjustedDest)
                progressTracker.completeItem()
                return
            }

            if (issueResolver.overwriteAllPathExists) {
                log(TAG, INFO) { "Overwriting directory (apply-to-all): $adjustedDest" }
                destOps.delete(adjustedDest, recursive = true)
                workQueue.addFirst(item)
                return
            }

            // Auto-merge directories when no issue handler (backward compatibility)
            if (onIssue == null) {
                log(TAG, VERBOSE) { "Directory exists, auto-merging: $adjustedDest" }
                moved.add(item.sourceLookup.lookedUp to adjustedDest)
                progressTracker.completeItem()
                return
            }
        } else if (issueResolver.overwriteAllPathExists) {
            log(TAG, INFO) { "Overwriting file with directory (apply-to-all): $adjustedDest" }
            destOps.delete(adjustedDest, recursive = false)
            workQueue.addFirst(item)
            return
        }

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
                val recursive = item.destLookup.fileType == FileType.DIRECTORY
                destOps.delete(item.destination, recursive = recursive)
                workQueue.addFirst(item.originalItem)
            }
            is PathActionIssue.PathAlreadyExists.Resolution.Merge -> {
                // Add the merged directory to moved set (directory exists, we're merging contents)
                moved.add(item.sourceLookup.lookedUp to item.destination)
                progressTracker.completeItem()
            }
            is PathActionIssue.PathAlreadyExists.Resolution.RenameSource -> {
                val parentPath = item.destination.parent!!
                val renamedDest = parentPath.child(resolution.newName)

                log(TAG, INFO) { "Renaming destination: ${item.destination} -> $renamedDest" }

                // Create new work item with renamed destination and re-queue
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
                        // Track renamed directory for child path adjustments
                        renamedSourceDirs[dirItem.sourceLookup.lookedUp] = renamedDest
                        workQueue.addFirst(updatedItem)
                    }
                    else -> {
                        log(TAG, ERROR) { "Unexpected original item type: $originalItem" }
                    }
                }
            }
            is PathActionIssue.PathAlreadyExists.Resolution.RenameDestination -> {
                val parentPath = item.destination.parent!!
                val newDestPath = parentPath.child(resolution.newName)

                log(TAG, INFO) { "Renaming existing destination: ${item.destination} -> $newDestPath" }

                // Move the existing destination to the new name
                destOps.move(item.destination, newDestPath)

                // Re-queue original operation (destination path now clear)
                workQueue.addFirst(item.originalItem)
            }
            is PathActionIssue.PathAlreadyExists.Resolution.Cancel -> {
                throw kotlin.coroutines.cancellation.CancellationException("User cancelled")
            }
        }
    }

    private fun calculateDestinationPath(source: SP, topLevelSource: SP): DP {
        // Unix cp/mv semantics:
        // - Single source + destination is existing directory: move INTO it (append name)
        // - Single source + destination doesn't exist: use as final path (rename)
        // - Multiple sources: always move INTO destination directory

        if (sources.size == 1 && source == topLevelSource && !destinationExistedAsDirectory) {
            // Single source + destination didn't exist as directory: use as final path (rename)
            return destination
        }

        // Multiple sources or processing children: append relative path to destination
        // Example: moving /source/topfolder to /dest should create /dest/topfolder/...
        val topLevelSegments = topLevelSource.segments
        val sourceSegments = source.segments

        // Drop parent segments of top-level source
        // - For rename semantics (single source, non-existent dest): drop ALL segments including name
        //   Example: mv /source/origdir dest/renamed -> drop [source,origdir] -> child file.txt -> dest/renamed/file.txt
        // - For move INTO semantics: drop parent segments, keep top-level name
        //   Example: mv /source/topfolder dest/ -> drop [source] -> keep [topfolder] -> dest/topfolder/...
        val segmentsToDrop = if (topLevelSegments.isEmpty()) {
            0
        } else if (sources.size == 1 && !destinationExistedAsDirectory) {
            // Rename semantics: drop ALL top-level segments (including the name itself)
            topLevelSegments.size
        } else {
            // Move INTO semantics: drop parent segments, keep top-level name
            topLevelSegments.size - 1
        }
        val relativeSegments = sourceSegments.drop(segmentsToDrop)

        // Build destination path with relative segments
        return destination.child(*relativeSegments.toTypedArray())
    }

    private fun adjustDestinationForRenames(dest: DP, source: SP): DP {
        // Check if any ancestor was renamed and adjust the destination path
        for ((renamedSource, renamedDest) in renamedSourceDirs) {
            // Check if source is a descendant of a renamed directory
            if (source.path.startsWith(renamedSource.path + "/") || source.path == renamedSource.path) {
                // Calculate the relative path from the renamed source
                val relativePath = source.path.removePrefix(renamedSource.path).removePrefix("/")

                if (relativePath.isEmpty()) {
                    // Source is the renamed directory itself
                    return renamedDest
                } else {
                    // Source is a child - append relative path to renamed dest
                    val segments = relativePath.split("/").filter { it.isNotEmpty() }
                    return renamedDest.child(*segments.toTypedArray())
                }
            }
        }
        return dest
    }

    private fun isDescendantOfSkippedDir(path: SP): Boolean {
        return skippedSourceDirs.any { path.path.startsWith(it.path) }
    }

    private suspend fun generateUniqueName(path: DP): String {
        // Generate unique name using smart increment logic
        // If parent path is null, fall back to simple "(1)" appending
        val parentPath = path.parent ?: return "${path.name} (1)"

        return GenericPathNamingUtils.generateUniqueName(
            parentPath = parentPath,
            originalName = path.name,
            ops = destOps
        )
    }

    private suspend fun handleScanError(error: Exception, lookup: SPL) {
        log(TAG, ERROR) { "Scan error: ${lookup.lookedUp} - $error" }
    }

    private suspend fun handleMoveError(error: Exception, originalItem: WorkItem.MoveFile<SP, SPL, DP>) {
        val source = originalItem.sourceLookup
        val dest = originalItem.destination

        log(TAG, ERROR) { "Move error: ${source.lookedUp} -> $dest - $error" }

        // Categorize exception type
        val isPermissionError = error is SecurityException ||
                               error is java.nio.file.AccessDeniedException

        // Check "apply to all" flags first (fast path)
        if (isPermissionError && issueResolver.skipAllPermission) {
            log(TAG, INFO) { "Skipping permission error (apply-to-all): $dest" }
            skipped.add(source.lookedUp)
            progressTracker.completeItem()
            return
        }

        if (!isPermissionError && issueResolver.skipAllUnknown) {
            log(TAG, INFO) { "Skipping unknown error (apply-to-all): $dest" }
            skipped.add(source.lookedUp)
            progressTracker.completeItem()
            return
        }

        // No issue handler configured? Re-throw exception immediately
        if (onIssue == null) {
            throw error
        }

        // Convert exception to appropriate PathActionIssue type
        val issue = if (isPermissionError) {
            PathActionIssue.InsufficientPermission(
                destination = source,
                exception = error,
                canSkip = true
            )
        } else {
            PathActionIssue.UnknownError(
                destination = source,
                exception = error,
                canRetry = true,
                canSkip = true
            )
        }

        // Resolve issue with user callback (may throw CancellationException)
        val resolution = issueResolver.resolveIssue(issue)

        // Handle resolution
        when (resolution) {
            is PathActionIssue.InsufficientPermission.Resolution.Skip,
            is PathActionIssue.UnknownError.Resolution.Skip -> {
                // User chose to skip this file
                skipped.add(source.lookedUp)
                progressTracker.completeItem()
            }
            is PathActionIssue.UnknownError.Resolution.Retry -> {
                log(TAG, INFO) { "Retrying move operation: ${source.lookedUp} -> $dest" }
                // Re-queue the original work item to try again
                // Progress stays in-flight, will be completed on success or skip
                workQueue.addFirst(originalItem)
            }
            else -> {
                // Cancel is handled by issueResolver.resolveIssue() throwing CancellationException
            }
        }
    }

    private suspend fun handleDirectoryError(error: Exception, originalItem: WorkItem.CreateDirectory<SP, SPL, DP>) {
        val source = originalItem.sourceLookup
        val dest = originalItem.destination

        log(TAG, ERROR) { "Directory creation error: ${source.lookedUp} -> $dest - $error" }

        // Categorize exception type
        val isPermissionError = error is SecurityException ||
                               error is java.nio.file.AccessDeniedException

        // Check "apply to all" flags first (fast path)
        if (isPermissionError && issueResolver.skipAllPermission) {
            log(TAG, INFO) { "Skipping permission error (apply-to-all): $dest" }
            skipped.add(source.lookedUp)
            skippedSourceDirs.add(source.lookedUp)
            progressTracker.completeItem()
            return
        }

        if (!isPermissionError && issueResolver.skipAllUnknown) {
            log(TAG, INFO) { "Skipping unknown error (apply-to-all): $dest" }
            skipped.add(source.lookedUp)
            skippedSourceDirs.add(source.lookedUp)
            progressTracker.completeItem()
            return
        }

        // No issue handler configured? Re-throw exception immediately
        if (onIssue == null) {
            throw error
        }

        // Convert exception to appropriate PathActionIssue type
        val issue = if (isPermissionError) {
            PathActionIssue.InsufficientPermission(
                destination = source,
                exception = error,
                canSkip = true
            )
        } else {
            PathActionIssue.UnknownError(
                destination = source,
                exception = error,
                canRetry = true,
                canSkip = true
            )
        }

        // Resolve issue with user callback (may throw CancellationException)
        val resolution = issueResolver.resolveIssue(issue)

        // Handle resolution
        when (resolution) {
            is PathActionIssue.InsufficientPermission.Resolution.Skip,
            is PathActionIssue.UnknownError.Resolution.Skip -> {
                // User chose to skip this directory
                skipped.add(source.lookedUp)
                skippedSourceDirs.add(source.lookedUp)
                progressTracker.completeItem()
            }
            is PathActionIssue.UnknownError.Resolution.Retry -> {
                log(TAG, INFO) { "Retrying directory creation: ${source.lookedUp} -> $dest" }
                // Re-queue the original work item to try again
                workQueue.addFirst(originalItem)
            }
            else -> {
                // Cancel is handled by issueResolver.resolveIssue() throwing CancellationException
            }
        }
    }

    private suspend fun reportScanProgress(lookup: SPL, emit: suspend (MoveAction.State<SP, SPL>) -> Unit) {
        val snapshot = progressTracker.createSnapshot()

        // For same-type operations (SP=DP), destination can be safely cast to SP
        @Suppress("UNCHECKED_CAST")
        emit(
            MoveAction.State.Progress(
                currentSource = lookup.lookedUp,
                currentDestination = destination as SP,
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

    private suspend fun reportProgress(lookup: SPL, emit: suspend (MoveAction.State<SP, SPL>) -> Unit) {
        val snapshot = progressTracker.createSnapshot()

        // For same-type operations (SP=DP), destination can be safely cast to SP
        @Suppress("UNCHECKED_CAST")
        emit(
            MoveAction.State.Progress(
                currentSource = lookup.lookedUp,
                currentDestination = destination as SP,  // Safe when SP=DP for same-type operations
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
    SP : APath<SP>, SPL : APathLookup<SP>, SPLE : APathLookupExtended<SP>,  // Source types
    DP : APath<DP>, DPL : APathLookup<DP>, DPLE : APathLookupExtended<DP>   // Destination types
    > Collection<SP>.moveGeneric(
    destination: DP,
    sourceOps: FileSystemOps<SP, SPL, SPLE>,
    destOps: FileSystemOps<DP, DPL, DPLE>,
    strategy: TransferStrategy<SP, SPL, SPLE, DP, DPL, DPLE>,
    options: TransferStrategy.Options = TransferStrategy.Options(),
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
): Flow<MoveAction.State<SP, SPL>> = GenericPathMove(
    sources = this,
    destination = destination,
    sourceOps = sourceOps,
    destOps = destOps,
    strategy = strategy,
    options = options,
    onIssue = onIssue
).execute()
