package eu.darken.butler.common.files.operations

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.APathLookupExtended
import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.errors.PathAlreadyExistsException
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
    SP : APath<SP>, SPL : APathLookup<SP>, SPLE : APathLookupExtended<SP>,  // Source types
    DP : APath<DP>, DPL : APathLookup<DP>, DPLE : APathLookupExtended<DP>   // Destination types
    >(
    private val sources: Collection<SP>,
    private val destination: DP,
    private val sourceOps: FileSystemOps<SP, SPL, SPLE>,
    private val destOps: FileSystemOps<DP, DPL, DPLE>,
    private val strategy: TransferStrategy<SP, SPL, SPLE, DP, DPL, DPLE>,
    private val options: TransferStrategy.Options,
    private val onProgress: (suspend (CopyAction.State.Progress<SP, SPL>) -> Unit)?,
    private val onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?
) {

    private val copied = linkedSetOf<Pair<SP, DP>>()
    private val skipped = linkedSetOf<SP>()
    private var totalBytesTransferred = 0L

    // Shared components
    private val progressTracker = PathOperationProgressTracker()
    private val issueResolver = PathOperationIssueResolver(onIssue)

    init {
        log(TAG, INFO) {
            "GenericPathCopy init: sources=${sources.size}, options=$options, onProgress=${onProgress != null}, onIssue=${onIssue != null}"
        }
    }

    // Track renamed directories for path adjustments
    private val skippedSourceDirs = mutableSetOf<SP>()
    private val renamedSourceDirs = mutableMapOf<SP, DP>()

    // Scan tracking
    private var scanItemsRemaining = 0

    // Destination state
    private var destinationExistedAsDirectory = false

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
        data class ScanSource<SP : APath<SP>>(
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
        data class CopyFile<SP : APath<SP>, SPL : APathLookup<SP>, DP : APath<DP>>(
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

    suspend fun execute(): CopyAction.State.Result<SP, SPL> {
        log(TAG, DEBUG) { "execute(): Copying ${sources.size} sources to $destination" }

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

        // If followSymlinks is enabled and this is a symlink, resolve it to determine actual type
        val effectiveLookup = if (options.followSymlinks && lookup.fileType == FileType.SYMBOLIC_LINK) {
            resolveSymlinkForScanning(item.source, lookup)
        } else {
            lookup
        }

        // Calculate destination path relative to top-level source
        val destPath = calculateDestinationPath(item.source, item.topLevelSource)

        when (effectiveLookup.fileType) {
            FileType.FILE, FileType.SYMBOLIC_LINK -> {
                // SYMBOLIC_LINK only when followSymlinks=false (otherwise resolved above)
                progressTracker.totalItems++
                progressTracker.totalBytes += effectiveLookup.size
                workQueue.addLast(WorkItem.CopyFile(effectiveLookup, destPath, item.topLevelSource))

                // Report scan progress with throttling
                if (progressTracker.shouldReportProgress()) {
                    reportScanProgress(effectiveLookup)
                }

                return 0
            }

            FileType.DIRECTORY -> {
                progressTracker.totalItems++
                progressTracker.totalBytes += effectiveLookup.size
                workQueue.addLast(WorkItem.CreateDirectory(effectiveLookup, destPath, item.topLevelSource))

                // Report scan progress with throttling
                if (progressTracker.shouldReportProgress()) {
                    reportScanProgress(effectiveLookup)
                }

                // List and queue children
                var childrenFound = 0
                try {
                    if (options.followSymlinks && lookup.fileType == FileType.SYMBOLIC_LINK) {
                        // This was a symlink-to-directory - list the target's children
                        // but re-parent them under the symlink path
                        val linkTarget = sourceOps.readSymbolicLink(item.source)
                        @Suppress("UNCHECKED_CAST")
                        val resolvedPath = if (linkTarget.path.startsWith("/")) {
                            linkTarget
                        } else {
                            val parent = item.source.parent
                                ?: throw IllegalStateException("Symlink has no parent: ${item.source}")
                            parent.child(linkTarget.path) as SP
                        }

                        // List children of the resolved directory
                        val targetChildren = sourceOps.listFiles(resolvedPath)

                        // Re-parent children under the symlink path (not the target path)
                        targetChildren.forEach { targetChild ->
                            // Get just the child name and create a path under the symlink
                            val childName = targetChild.name
                            @Suppress("UNCHECKED_CAST")
                            val symlinkChild = item.source.child(childName) as SP
                            workQueue.addFirst(WorkItem.ScanSource(symlinkChild, item.topLevelSource))
                            childrenFound++
                        }
                    } else {
                        // Regular directory - list and queue children normally
                        val children = sourceOps.listFiles(item.source)
                        children.forEach { child ->
                            workQueue.addFirst(WorkItem.ScanSource(child, item.topLevelSource))
                            childrenFound++
                        }
                    }
                } catch (e: Exception) {
                    handleScanError(e, effectiveLookup)
                }

                return childrenFound
            }

            FileType.UNKNOWN -> throw IllegalStateException("Unknown file type: $effectiveLookup")
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

        // Copy file - detect conflicts via exception
        progressTracker.startFile(item.sourceLookup.size)

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
        } catch (e: PathAlreadyExistsException) {
            log(TAG, VERBOSE) { "File collision detected: $adjustedDest" }
            val destLookup = destOps.lookup(adjustedDest)
            handleFileConflict(item, adjustedDest, destLookup)
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

        // Check for conflicts before creating
        if (destOps.exists(adjustedDest)) {
            log(TAG, VERBOSE) { "Directory collision detected: $adjustedDest" }
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
            val parentPath = adjustedDest.parent!!
            val renamedDest = parentPath.child(uniqueName)
            log(TAG, INFO) { "Auto-renaming (apply-to-all): $adjustedDest -> $renamedDest" }

            // Create new work item with renamed destination and requeue
            val renamedItem = WorkItem.CopyFile(
                sourceLookup = item.sourceLookup,
                destination = renamedDest,
                topLevelSource = item.topLevelSource
            )
            workQueue.addFirst(renamedItem)
            return
        }

        if (issueResolver.overwriteAllPathExists) {
            log(TAG, INFO) { "Overwriting (apply-to-all): $adjustedDest" }
            destOps.delete(adjustedDest, recursive = false)
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
                log(TAG, VERBOSE) { "Directory already exists, auto-merging: $adjustedDest" }
                progressTracker.completeItem()
                return
            }
        } else if (issueResolver.overwriteAllPathExists) {
            log(TAG, INFO) { "Overwriting file with directory (apply-to-all): $adjustedDest" }
            destOps.delete(adjustedDest, recursive = false)
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
                val recursive = item.destLookup.fileType == FileType.DIRECTORY
                destOps.delete(item.destination, recursive = recursive)
                workQueue.addFirst(item.originalItem)
            }
            is PathActionIssue.PathAlreadyExists.Resolution.Merge -> {
                // Add the merged directory to copied set (directory exists, we're merging contents)
                copied.add(item.sourceLookup.lookedUp to item.destination)
                progressTracker.completeItem()
            }
            is PathActionIssue.PathAlreadyExists.Resolution.RenameSource -> {
                val parentPath = item.destination.parent!!
                val renamedDest = parentPath.child(resolution.newName)

                log(TAG, INFO) { "Renaming destination: ${item.destination} -> $renamedDest" }

                // Create new work item with renamed destination and re-queue
                when (val originalItem = item.originalItem) {
                    is WorkItem.CopyFile<*, *, *> -> {
                        @Suppress("UNCHECKED_CAST")
                        val copyItem = originalItem as WorkItem.CopyFile<SP, SPL, DP>
                        val updatedItem = WorkItem.CopyFile(
                            sourceLookup = copyItem.sourceLookup,
                            destination = renamedDest,
                            topLevelSource = copyItem.topLevelSource
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
        // - Single source + destination is existing directory: copy INTO it (append name)
        // - Single source + destination doesn't exist: use as final path (rename)
        // - Multiple sources: always copy INTO destination directory

        if (sources.size == 1 && source == topLevelSource && !destinationExistedAsDirectory) {
            // Single source + destination didn't exist as directory: use as final path (rename)
            return destination
        }

        // Multiple sources or processing children: append relative path to destination
        // Example: copying /source/topfolder to /dest should create /dest/topfolder/...
        val topLevelSegments = topLevelSource.segments
        val sourceSegments = source.segments

        // Drop parent segments of top-level source
        // - For rename semantics (single source, non-existent dest): drop ALL segments including name
        //   Example: cp /source/origdir dest/renamed -> drop [source,origdir] -> child file.txt -> dest/renamed/file.txt
        // - For copy INTO semantics: drop parent segments, keep top-level name
        //   Example: cp /source/topfolder dest/ -> drop [source] -> keep [topfolder] -> dest/topfolder/...
        val segmentsToDrop = if (topLevelSegments.isEmpty()) {
            0
        } else if (sources.size == 1 && !destinationExistedAsDirectory) {
            // Rename semantics: drop ALL top-level segments (including the name itself)
            topLevelSegments.size
        } else {
            // Copy INTO semantics: drop parent segments, keep top-level name
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
        return skippedSourceDirs.any { skippedDir ->
            path.path.startsWith(skippedDir.path)
        }
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
        // Would handle errors similar to LocalPathDelete
    }

    private suspend fun handleCopyError(error: Exception, source: SPL, dest: DP) {
        log(TAG, ERROR) { "Copy error: ${source.lookedUp} -> $dest - $error" }

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
                canRetry = false,  // Can't retry file operations easily
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
                // Retry not implemented for now - just skip
                log(TAG, WARN) { "Retry not implemented, skipping: $dest" }
                skipped.add(source.lookedUp)
                progressTracker.completeItem()
            }
            else -> {
                // Cancel is handled by issueResolver.resolveIssue() throwing CancellationException
            }
        }
    }

    private suspend fun reportScanProgress(lookup: SPL) {
        val snapshot = progressTracker.createSnapshot()

        // For same-type operations (SP=DP), destination can be safely cast to SP
        @Suppress("UNCHECKED_CAST")
        onProgress?.invoke(
            CopyAction.State.Progress(
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
                        current = snapshot.currentFileBytes,
                        max = snapshot.currentFileSize
                    )
                ),
                copiedBytes = snapshot.processedBytes,
                totalBytes = snapshot.totalBytes,
                currentFileSize = snapshot.currentFileSize,
                currentFileBytes = snapshot.currentFileBytes,
                currentFileStartTime = snapshot.currentFileStartTime
            )
        )
    }

    /**
     * Resolve a symlink to get the lookup of its target.
     * Used during scanning to determine if we should treat a symlink as a file or directory.
     *
     * When `followSymlinks=true`, this method resolves the symlink and returns a lookup
     * with the target's actual file type, allowing directories to be scanned recursively.
     */
    private suspend fun resolveSymlinkForScanning(symlinkPath: SP, symlinkLookup: SPL): SPL {
        try {
            val linkTarget = sourceOps.readSymbolicLink(symlinkPath)

            // Resolve relative paths to absolute
            @Suppress("UNCHECKED_CAST")
            val resolvedPath = if (linkTarget.path.startsWith("/")) {
                // Absolute path - use as-is
                linkTarget
            } else {
                // Relative path - resolve relative to symlink's parent
                val parent = symlinkPath.parent
                    ?: throw IllegalStateException("Symlink has no parent: $symlinkPath")
                parent.child(linkTarget.path) as SP
            }

            // Lookup the target to get its actual file type
            val targetLookup = sourceOps.lookup(resolvedPath)

            log(TAG, VERBOSE) {
                "Resolved symlink for scanning: $symlinkPath -> $resolvedPath (${targetLookup.fileType})"
            }

            return targetLookup
        } catch (e: Exception) {
            log(TAG, WARN) { "Failed to resolve symlink $symlinkPath: $e - treating as file" }
            // Fall back to treating it as a file (will be copied as symlink)
            return symlinkLookup
        }
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
    SP : APath<SP>, SPL : APathLookup<SP>, SPLE : APathLookupExtended<SP>,  // Source types
    DP : APath<DP>, DPL : APathLookup<DP>, DPLE : APathLookupExtended<DP>   // Destination types
    > Collection<SP>.copyGeneric(
    destination: DP,
    sourceOps: FileSystemOps<SP, SPL, SPLE>,
    destOps: FileSystemOps<DP, DPL, DPLE>,
    strategy: TransferStrategy<SP, SPL, SPLE, DP, DPL, DPLE>,
    options: TransferStrategy.Options = TransferStrategy.Options(),
    onProgress: (suspend (CopyAction.State.Progress<SP, SPL>) -> Unit)? = null,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
): CopyAction.State.Result<SP, SPL> = GenericPathCopy(
    sources = this,
    destination = destination,
    sourceOps = sourceOps,
    destOps = destOps,
    strategy = strategy,
    options = options,
    onProgress = onProgress,
    onIssue = onIssue
).execute()
