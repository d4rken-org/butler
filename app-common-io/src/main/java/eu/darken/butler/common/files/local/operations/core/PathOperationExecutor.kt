package eu.darken.butler.common.files.local.operations.core

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.local.operations.scanning.SpaceValidator
import eu.darken.butler.common.files.local.operations.strategies.TransferStrategy
import eu.darken.butler.common.files.local.performLookup
import eu.darken.butler.common.files.local.relativeSegmentsTo
import eu.darken.butler.common.files.local.toNioPath
import eu.darken.butler.common.files.metadata.FileType
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.io.File
import java.nio.file.Files

/**
 * Generic executor for path operations (copy, move) that coordinates scanning,
 * validation, conflict resolution, error handling, and progress tracking.
 *
 * This executor uses composition to delegate specific behaviors to:
 * - TransferStrategy: How files are transferred (copy vs move)
 * - PathOperationIssueResolver: "Apply to all" flag management
 * - PathOperationErrorHandler: Error handling and user prompts
 * - PathOperationProgressTracker: Progress tracking
 * - SpaceValidator: Disk space validation
 *
 * @param strategy The transfer strategy (copy or move)
 * @param sources Collection of source paths to transfer
 * @param destination The destination directory
 * @param issueResolver Handles issue resolution and "apply to all" flags
 * @param errorHandler Handles errors and user prompts
 * @param progressTracker Tracks operation progress
 * @param spaceValidator Validates available disk space
 * @param transferOptions Options for the transfer strategy
 * @param followSymlinks Whether to follow symlinks to their targets
 */
class PathOperationExecutor(
    private val strategy: TransferStrategy,
    private val sources: Collection<LocalPath>,
    private val destination: LocalPath,
    private val issueResolver: PathOperationIssueResolver,
    private val errorHandler: PathOperationErrorHandler,
    private val progressTracker: PathOperationProgressTracker,
    private val spaceValidator: SpaceValidator,
    private val transferOptions: TransferStrategy.Options = TransferStrategy.Options(),
    private val followSymlinks: Boolean = false,
    private val onProgress: (suspend (LocalPath, LocalPath, LocalPathLookup) -> Unit)? = null,
) {

    private val transferred = linkedSetOf<Pair<LocalPath, LocalPath>>()
    private val skipped = linkedSetOf<LocalPath>()

    // Detect rename operation: single source with same parent directory as destination
    // For rename, destination IS the final path, not a parent directory to append to
    private val isRename = sources.size == 1 &&
        sources.first().file.parentFile?.absolutePath == destination.file.parentFile?.absolutePath

    // Track renamed and skipped directories
    private val skippedSourceDirs = mutableSetOf<LocalPath>()
    private val renamedSourceDirs = mutableMapOf<LocalPath, LocalPath>()

    // Work queue for processing operations
    private val workQueue = ArrayDeque<WorkItem>()

    private sealed class WorkItem {
        data class ScanSource(
            val source: LocalPath,
            val displayPath: LocalPath = source,
            val topLevelSource: LocalPath = source,
        ) : WorkItem()

        data object CheckSpace : WorkItem()

        data class CreateDirectory(
            val sourceLookup: LocalPathLookup,
            val dest: LocalPath,
            val topLevelSource: LocalPath,
        ) : WorkItem()

        data class TransferFile(
            val sourceLookup: LocalPathLookup,
            val dest: LocalPath,
            val topLevelSource: LocalPath,
        ) : WorkItem()

        data class ResolveConflict(
            val sourceLookup: LocalPathLookup,
            val dest: LocalPath,
            val destLookup: LocalPathLookup,
            val originalItem: WorkItem,
        ) : WorkItem()
    }

    /**
     * Result of executing the operation.
     */
    data class Result(
        val transferred: Set<Pair<LocalPath, LocalPath>>,
        val skipped: Set<LocalPath>,
        val bytesTransferred: Long,
    )

    suspend fun execute(): Result {
        // Initialize queue with scan items for each source
        workQueue.addAll(sources.map { WorkItem.ScanSource(it) })
        // After all sources are scanned, check disk space
        workQueue.add(WorkItem.CheckSpace)

        // Process work queue
        while (workQueue.isNotEmpty() && currentCoroutineContext().isActive) {
            when (val item = workQueue.removeFirst()) {
                is WorkItem.ScanSource -> processScan(item)
                is WorkItem.CheckSpace -> processSpaceCheck()
                is WorkItem.CreateDirectory -> processCreateDirectory(item)
                is WorkItem.TransferFile -> processTransferFile(item)
                is WorkItem.ResolveConflict -> processResolveConflict(item)
            }
        }

        return Result(
            transferred = transferred,
            skipped = skipped,
            bytesTransferred = progressTracker.processedBytes,
        )
    }

    private suspend fun processScan(item: WorkItem.ScanSource) {
        log(TAG, VERBOSE) { "Scanning source: ${item.source}" }

        val lookup = try {
            item.source.performLookup()
        } catch (e: Exception) {
            if (item.source == item.topLevelSource) {
                throw e // Top-level source must exist
            }
            log(TAG, WARN) { "Child source disappeared during scan: ${item.source}" }
            return
        }

        // Calculate destination path
        val destinationPath = if (isRename && item.displayPath == item.topLevelSource) {
            // For rename of top-level source, destination IS the final path - don't append source name
            destination
        } else {
            // For move/copy (or children of renamed directories), append source path relative to top-level source
            val relativePath = if (item.displayPath == item.topLevelSource) {
                item.topLevelSource.name
            } else {
                val segments = item.topLevelSource.relativeSegmentsTo(item.displayPath)
                // For renamed top-level, use destination name as base; otherwise use source name
                val baseName = if (isRename) destination.name else item.topLevelSource.name
                baseName + File.separator + segments.joinToString(File.separator)
            }
            val baseDir = if (isRename) destination.file.parentFile else destination.file
            LocalPath.build(File(baseDir ?: destination.file, relativePath.trimStart('/')))
        }

        when (lookup.fileType) {
            FileType.DIRECTORY -> {
                workQueue.addLast(WorkItem.CreateDirectory(lookup, destinationPath, item.topLevelSource))
                progressTracker.totalBytes += lookup.size
                progressTracker.totalItems++

                // List and queue children
                val context = PathOperationErrorHandler.ErrorContext.Read(lookup, "List directory contents")
                val result = errorHandler.handleErrors("List directory", context) {
                    val children = mutableListOf<WorkItem.ScanSource>()
                    Files.newDirectoryStream(item.source.toNioPath()).use { ds ->
                        for (child in ds) {
                            if (!currentCoroutineContext().isActive) return@handleErrors children
                            val childPath = LocalPath.build(child.toFile())
                            val childDisplayPath =
                                LocalPath.build(File(item.displayPath.file, child.fileName.toString()))
                            children.add(
                                WorkItem.ScanSource(
                                    source = childPath,
                                    displayPath = childDisplayPath,
                                    topLevelSource = item.topLevelSource
                                )
                            )
                        }
                    }
                    children
                }

                result.getOrNull()?.forEach { child ->
                    workQueue.addFirst(child)
                }
            }

            FileType.FILE -> {
                workQueue.addLast(WorkItem.TransferFile(lookup, destinationPath, item.topLevelSource))
                progressTracker.totalBytes += lookup.size
                progressTracker.totalItems++
            }

            FileType.SYMBOLIC_LINK -> {
                if (followSymlinks) {
                    // Resolve symlink to its target
                    try {
                        val targetPath = Files.readSymbolicLink(item.source.toNioPath())
                        val resolvedPath = item.source.toNioPath().parent.resolve(targetPath).normalize()

                        if (Files.isDirectory(resolvedPath)) {
                            // Symlink points to directory - create directory and scan contents
                            workQueue.addLast(WorkItem.CreateDirectory(lookup, destinationPath, item.topLevelSource))
                            progressTracker.totalBytes += lookup.size
                            progressTracker.totalItems++

                            // Re-queue to scan the resolved directory's contents
                            workQueue.addFirst(
                                WorkItem.ScanSource(
                                    source = LocalPath.build(resolvedPath.toFile()),
                                    displayPath = item.displayPath,
                                    topLevelSource = item.topLevelSource
                                )
                            )
                        } else {
                            // Symlink points to file - transfer as file
                            workQueue.addLast(WorkItem.TransferFile(lookup, destinationPath, item.topLevelSource))
                            progressTracker.totalBytes += lookup.size
                            progressTracker.totalItems++
                        }
                    } catch (e: Exception) {
                        log(TAG, WARN) { "Failed to resolve symlink: ${item.source} - ${e.message}" }
                        // Treat as regular file if resolution fails
                        workQueue.addLast(WorkItem.TransferFile(lookup, destinationPath, item.topLevelSource))
                        progressTracker.totalBytes += lookup.size
                        progressTracker.totalItems++
                    }
                } else {
                    // Don't follow symlinks - transfer as-is
                    workQueue.addLast(WorkItem.TransferFile(lookup, destinationPath, item.topLevelSource))
                    progressTracker.totalBytes += lookup.size
                    progressTracker.totalItems++
                }
            }

            FileType.UNKNOWN -> throw IllegalStateException("Unknown file type: $lookup")
        }
    }

    private suspend fun processSpaceCheck() {
        spaceValidator.validateSpace(destination, progressTracker.totalBytes, sources)
        log(TAG, DEBUG) { "Space check passed" }
    }

    private suspend fun processCreateDirectory(item: WorkItem.CreateDirectory) {
        val adjustedDest = PathOperationUtils.adjustDestinationForRenames(
            item.dest,
            item.sourceLookup.lookedUp,
            renamedSourceDirs
        )

        log(TAG, VERBOSE) { "Creating directory: ${item.sourceLookup.lookedUp} -> $adjustedDest" }

        // Skip if source and destination are the same
        if (item.sourceLookup.lookedUp.path == adjustedDest.path) {
            log(TAG, INFO) { "Skipping - source and destination are identical" }
            progressTracker.completeItem()
            return
        }

        // Check for conflicts
        if (Files.exists(adjustedDest.toNioPath())) {
            val destLookup = adjustedDest.performLookup()
            handleDirectoryConflict(item, adjustedDest, destLookup)
            return
        }

        // Create directory
        val context = PathOperationErrorHandler.ErrorContext.Write(
            item.sourceLookup,
            adjustedDest,
            "Create directory"
        )
        val result = errorHandler.handleErrors("Create directory", context) {
            strategy.createDirectory(item.sourceLookup, adjustedDest, transferOptions)
        }

        result.onSuccess { transferResult ->
            when (transferResult) {
                is TransferStrategy.TransferResult.Success -> {
                    transferred.add(item.sourceLookup.lookedUp to transferResult.destination)
                    progressTracker.completeItem()
                }
                is TransferStrategy.TransferResult.Skipped -> {
                    skipped.add(item.sourceLookup.lookedUp)
                    progressTracker.completeItem()
                }
            }
        }
    }

    private suspend fun handleDirectoryConflict(
        item: WorkItem.CreateDirectory,
        adjustedDest: LocalPath,
        destLookup: LocalPathLookup
    ) {
        // Check "apply to all" flags
        if (issueResolver.skipAllPathExists) {
            log(TAG, INFO) { "Skipping (skip apply-to-all): $adjustedDest" }
            skipped.add(item.sourceLookup.lookedUp)
            skippedSourceDirs.add(item.sourceLookup.lookedUp)
            progressTracker.completeItem()
            return
        }

        if (issueResolver.renameSourceAllPathExists) {
            val uniqueName = PathOperationUtils.generateUniqueName(adjustedDest.name, adjustedDest.file.parentFile!!)
            val renamedDest = LocalPath.build(File(adjustedDest.file.parentFile!!, uniqueName))
            log(TAG, INFO) { "Auto-renaming (rename apply-to-all): $adjustedDest -> $renamedDest" }
            Files.createDirectories(renamedDest.toNioPath())
            transferred.add(item.sourceLookup.lookedUp to renamedDest)
            renamedSourceDirs[item.sourceLookup.lookedUp] = renamedDest
            progressTracker.completeItem()
            return
        }

        if (destLookup.fileType == FileType.DIRECTORY) {
            if (issueResolver.mergeAllPathExists) {
                log(TAG, INFO) { "Merging directory (merge apply-to-all): $adjustedDest" }
                progressTracker.completeItem()
                return
            }

            if (issueResolver.overwriteAllPathExists) {
                log(TAG, INFO) { "Overwriting directory (overwrite apply-to-all): $adjustedDest" }
                PathOperationUtils.deleteRecursively(adjustedDest)
                workQueue.addFirst(item)
                return
            }

            // Auto-merge directories when no issue handler (backward compatibility)
            if (issueResolver.onIssue == null) {
                log(TAG, VERBOSE) { "Directory already exists, auto-merging (no issue handler): $adjustedDest" }
                progressTracker.completeItem()
                return
            }
        } else if (issueResolver.overwriteAllPathExists) {
            log(TAG, INFO) { "Overwriting file with directory (overwrite apply-to-all): $adjustedDest" }
            Files.delete(adjustedDest.toNioPath())
            workQueue.addFirst(item)
            return
        }

        // Check if we have an issue handler
        if (issueResolver.onIssue == null) {
            val exception = eu.darken.butler.common.files.errors.WriteException(
                path = adjustedDest,
                cause = java.nio.file.FileAlreadyExistsException(adjustedDest.path)
            )
            throw exception
        }

        // Queue conflict resolution
        workQueue.addFirst(WorkItem.ResolveConflict(item.sourceLookup, adjustedDest, destLookup, item))
    }

    private suspend fun processTransferFile(item: WorkItem.TransferFile) {
        // Skip if parent directory was skipped
        if (PathOperationUtils.isDescendantOfSkippedDir(item.sourceLookup.lookedUp, skippedSourceDirs)) {
            log(TAG, VERBOSE) { "Skipping file - parent directory was skipped" }
            skipped.add(item.sourceLookup.lookedUp)
            progressTracker.completeItem()
            return
        }

        val adjustedDest = PathOperationUtils.adjustDestinationForRenames(
            item.dest,
            item.sourceLookup.lookedUp,
            renamedSourceDirs
        )

        log(TAG, VERBOSE) { "Transferring file: ${item.sourceLookup.lookedUp} -> $adjustedDest" }

        // Skip if source and destination are the same
        if (item.sourceLookup.lookedUp.path == adjustedDest.path) {
            log(TAG, INFO) { "Skipping - source and destination are identical" }
            progressTracker.completeItem()
            return
        }

        // Ensure parent directory exists
        adjustedDest.file.parentFile?.let { parent ->
            val parentPath = LocalPath.build(parent)
            if (!Files.exists(parentPath.toNioPath())) {
                Files.createDirectories(parentPath.toNioPath())
            }
        }

        // Check for conflicts
        if (Files.exists(adjustedDest.toNioPath())) {
            handleFileConflict(item, adjustedDest)
            return
        }

        // Transfer file
        progressTracker.startFile(item.sourceLookup.size)

        val context = PathOperationErrorHandler.ErrorContext.Write(
            item.sourceLookup,
            adjustedDest,
            "Transfer file"
        )
        val result = errorHandler.handleErrors("Transfer file", context) {
            strategy.transferFile(
                sourceLookup = item.sourceLookup,
                destination = adjustedDest,
                options = transferOptions,
                onProgress = { bytes ->
                    progressTracker.updateFileProgress(bytes)
                    if (progressTracker.shouldReportProgress()) {
                        onProgress?.invoke(item.sourceLookup.lookedUp, adjustedDest, item.sourceLookup)
                    }
                }
            )
        }

        result.onSuccess { transferResult ->
            when (transferResult) {
                is TransferStrategy.TransferResult.Success -> {
                    transferred.add(item.sourceLookup.lookedUp to transferResult.destination)
                    progressTracker.completeFile()
                    progressTracker.completeItem()
                    // Force final progress report for this file
                    if (progressTracker.shouldReportProgress(force = true)) {
                        onProgress?.invoke(item.sourceLookup.lookedUp, transferResult.destination, item.sourceLookup)
                    }
                }
                is TransferStrategy.TransferResult.Skipped -> {
                    skipped.add(item.sourceLookup.lookedUp)
                    progressTracker.completeFile()
                    progressTracker.completeItem()
                    if (progressTracker.shouldReportProgress(force = true)) {
                        onProgress?.invoke(item.sourceLookup.lookedUp, adjustedDest, item.sourceLookup)
                    }
                }
            }
        }
    }

    private suspend fun handleFileConflict(item: WorkItem.TransferFile, adjustedDest: LocalPath) {
        // Check "apply to all" flags
        if (issueResolver.skipAllPathExists) {
            log(TAG, INFO) { "Skipping (skip apply-to-all): $adjustedDest" }
            skipped.add(item.sourceLookup.lookedUp)
            progressTracker.completeItem()
            return
        }

        if (issueResolver.renameSourceAllPathExists) {
            val uniqueName = PathOperationUtils.generateUniqueName(adjustedDest.name, adjustedDest.file.parentFile!!)
            val renamedDest = LocalPath.build(File(adjustedDest.file.parentFile!!, uniqueName))
            log(TAG, INFO) { "Auto-renaming (rename apply-to-all): $adjustedDest -> $renamedDest" }

            progressTracker.startFile(item.sourceLookup.size)
            val result = strategy.transferFile(
                sourceLookup = item.sourceLookup,
                destination = renamedDest,
                options = transferOptions,
                onProgress = { bytes ->
                    progressTracker.updateFileProgress(bytes)
                    if (progressTracker.shouldReportProgress()) {
                        onProgress?.invoke(item.sourceLookup.lookedUp, renamedDest, item.sourceLookup)
                    }
                }
            )
            when (result) {
                is TransferStrategy.TransferResult.Success -> {
                    transferred.add(item.sourceLookup.lookedUp to result.destination)
                }
                is TransferStrategy.TransferResult.Skipped -> {
                    skipped.add(item.sourceLookup.lookedUp)
                }
            }
            progressTracker.completeFile()
            progressTracker.completeItem()
            // Force final progress report
            if (progressTracker.shouldReportProgress(force = true)) {
                onProgress?.invoke(item.sourceLookup.lookedUp, renamedDest, item.sourceLookup)
            }
            return
        }

        if (issueResolver.overwriteAllPathExists) {
            log(TAG, INFO) { "Overwriting (overwrite apply-to-all): $adjustedDest" }
            Files.delete(adjustedDest.toNioPath())
            workQueue.addFirst(item)
            return
        }

        // Check if we have an issue handler
        if (issueResolver.onIssue == null) {
            val exception = eu.darken.butler.common.files.errors.WriteException(
                path = adjustedDest,
                cause = java.nio.file.FileAlreadyExistsException(adjustedDest.path)
            )
            throw exception
        }

        // Queue conflict resolution
        val destLookup = adjustedDest.performLookup()
        workQueue.addFirst(WorkItem.ResolveConflict(item.sourceLookup, adjustedDest, destLookup, item))
    }

    private suspend fun processResolveConflict(item: WorkItem.ResolveConflict) {
        val canMerge = item.originalItem is WorkItem.CreateDirectory &&
            item.destLookup.fileType == FileType.DIRECTORY

        val issue = PathActionIssue.PathAlreadyExists(
            source = item.sourceLookup,
            destination = item.destLookup,
            canSkip = true,
            canOverwrite = true,
            canMerge = canMerge,
            canRenameSource = true,
            canRenameDestination = true,
            suggestedName = PathOperationUtils.generateUniqueName(
                item.dest.name,
                item.dest.file.parentFile!!
            ),
        )

        when (val resolution = issueResolver.resolveIssue(issue) as PathActionIssue.PathAlreadyExists.Resolution) {
            is PathActionIssue.PathAlreadyExists.Resolution.Skip -> {
                skipped.add(item.sourceLookup.lookedUp)
                if (item.originalItem is WorkItem.CreateDirectory) {
                    skippedSourceDirs.add(item.sourceLookup.lookedUp)
                }
                progressTracker.completeItem()
            }
            is PathActionIssue.PathAlreadyExists.Resolution.Overwrite -> {
                if (Files.isDirectory(item.dest.toNioPath())) {
                    PathOperationUtils.deleteRecursively(item.dest)
                } else {
                    Files.delete(item.dest.toNioPath())
                }
                workQueue.addFirst(item.originalItem)
            }
            is PathActionIssue.PathAlreadyExists.Resolution.Merge -> {
                progressTracker.completeItem()
            }
            is PathActionIssue.PathAlreadyExists.Resolution.RenameSource -> {
                when (val orig = item.originalItem) {
                    is WorkItem.CreateDirectory -> {
                        val newDestPath = LocalPath.build(File(item.dest.file.parentFile!!, resolution.newName))
                        Files.createDirectories(newDestPath.toNioPath())
                        transferred.add(item.sourceLookup.lookedUp to newDestPath)
                        renamedSourceDirs[item.sourceLookup.lookedUp] = newDestPath
                        progressTracker.completeItem()
                    }
                    is WorkItem.TransferFile -> {
                        val newDestPath = LocalPath.build(File(item.dest.file.parentFile!!, resolution.newName))
                        progressTracker.startFile(item.sourceLookup.size)
                        val result = strategy.transferFile(
                            sourceLookup = item.sourceLookup,
                            destination = newDestPath,
                            options = transferOptions,
                            onProgress = { bytes ->
                                progressTracker.updateFileProgress(bytes)
                                if (progressTracker.shouldReportProgress()) {
                                    onProgress?.invoke(item.sourceLookup.lookedUp, newDestPath, item.sourceLookup)
                                }
                            }
                        )
                        when (result) {
                            is TransferStrategy.TransferResult.Success -> {
                                transferred.add(item.sourceLookup.lookedUp to result.destination)
                            }
                            is TransferStrategy.TransferResult.Skipped -> {
                                skipped.add(item.sourceLookup.lookedUp)
                            }
                        }
                        progressTracker.completeFile()
                        progressTracker.completeItem()
                        // Force final progress report
                        if (progressTracker.shouldReportProgress(force = true)) {
                            onProgress?.invoke(item.sourceLookup.lookedUp, newDestPath, item.sourceLookup)
                        }
                    }
                    else -> error("Unexpected item type")
                }
            }
            is PathActionIssue.PathAlreadyExists.Resolution.RenameDestination -> {
                val newDestPath = LocalPath.build(File(item.dest.file.parentFile!!, resolution.newName))
                Files.move(item.dest.toNioPath(), newDestPath.toNioPath())
                workQueue.addFirst(item.originalItem)
            }
            is PathActionIssue.PathAlreadyExists.Resolution.Cancel -> {
                throw kotlin.coroutines.cancellation.CancellationException("User cancelled")
            }
        }
    }

    companion object {
        private val TAG = logTag("PathOperation", "Executor")
    }
}
