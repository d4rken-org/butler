package eu.darken.butler.common.files.local

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.io.R
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import kotlin.coroutines.cancellation.CancellationException

internal class LocalPathMove(
    private val sources: Collection<LocalPath>,
    private val destination: LocalPath,
    private val options: MoveAction.Options<LocalPath>,
    private val onProgress: (suspend (MoveAction.State.Progress<LocalPath>) -> Unit)?,
    private val onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?,
) {
    private val moved = linkedSetOf<Pair<LocalPath, LocalPath>>()
    private val skipped = linkedSetOf<LocalPath>()
    private var movedBytes = 0L

    // Current file progress tracking (for copy+delete fallback)
    private var currentFileSize = 0L
    private var currentFileBytes = 0L
    private var currentFileStartTime: kotlin.time.Instant? = null
    private var lastProgressReport = kotlin.time.TimeSource.Monotonic.markNow()

    private var issueSkipAllPathExists = false
    private var issueOverwriteAllPathExists = false
    private var issueMergeAllPathExists = false
    private var issueSkipAllPermission = false
    private var issueSkipAllUnknown = false

    // Track renamed and skipped directories
    private val skippedSourceDirs = mutableSetOf<LocalPath>()
    private val renamedSourceDirs = mutableMapOf<LocalPath, LocalPath>()

    // Progress tracking
    private var totalItems = 0
    private var totalSources = 0
    private var itemsProcessed = 0
    private var sourcesCompleted = 0

    // Total accumulated size from all scans
    private var totalBytes = 0L

    // Work queue for processing move operations
    private var workQueue = ArrayDeque<WorkItem>()

    // Single-use flag
    private var hasExecuted = false

    /**
     * Sealed hierarchy of work items for the move queue
     */
    private sealed class WorkItem {
        /**
         * Scan a source directory tree and queue create/move items
         */
        data class ScanSource(
            val source: LocalPath,
            val displayPath: LocalPath = source,
            val topLevelSource: LocalPath = source,
        ) : WorkItem()

        /**
         * Check if sufficient disk space is available
         */
        data object CheckSpace : WorkItem()

        /**
         * Create a directory at the destination
         */
        data class CreateDirectory(
            val sourceLookup: LocalPathLookup,
            val dest: LocalPath,
            val topLevelSource: LocalPath,
        ) : WorkItem()

        /**
         * Move a file to the destination (tries atomic move, falls back to copy+delete)
         */
        data class MoveFile(
            val sourceLookup: LocalPathLookup,
            val dest: LocalPath,
            val topLevelSource: LocalPath,
        ) : WorkItem()

        /**
         * Resolve an issue that occurred during processing
         */
        data class ResolveIssue(
            val issue: PathActionIssue,
            val originalItem: WorkItem,
            val exception: Exception,
        ) : WorkItem()
    }

    /**
     * Context for error handling operations
     */
    private sealed class ErrorContext {
        abstract val sourceLookup: LocalPathLookup
        abstract val operation: String

        data class Read(
            override val sourceLookup: LocalPathLookup,
            override val operation: String,
        ) : ErrorContext()

        data class Write(
            override val sourceLookup: LocalPathLookup,
            val destPath: LocalPath,
            override val operation: String,
        ) : ErrorContext()
    }

    suspend fun execute(): MoveAction.State.Result<LocalPath> {
        check(!hasExecuted) { "LocalPathMove can only be executed once" }
        hasExecuted = true

        ensureDestinationExists()

        totalSources = sources.size

        // Initialize queue with scan items for each source (matches LocalPathCopy architecture)
        workQueue.addAll(sources.map { WorkItem.ScanSource(it) })
        // After all sources are scanned, check disk space
        workQueue.add(WorkItem.CheckSpace)

        // Process work queue
        while (workQueue.isNotEmpty() && currentCoroutineContext().isActive) {
            when (val item = workQueue.removeFirst()) {
                is WorkItem.ScanSource -> processScan(item)
                is WorkItem.CheckSpace -> processSpaceCheck()
                is WorkItem.CreateDirectory -> processCreateDirectory(item)
                is WorkItem.MoveFile -> processMoveFile(item)
                is WorkItem.ResolveIssue -> processResolveIssue(item)
            }
        }

        // Clean up source directories that were moved file-by-file
        for (source in sources) {
            if (source in skipped) continue

            // Check if source was a directory
            val sourceLookup = try {
                if (Files.exists(source.toNioPath())) {
                    source.performLookup()
                } else {
                    // Source no longer exists - it was a file that was moved atomically
                    null
                }
            } catch (e: Exception) {
                log(TAG, WARN) { "Failed to lookup source for cleanup: $source - $e" }
                null
            }

            if (sourceLookup != null && sourceLookup.fileType == FileType.DIRECTORY) {
                // Delete source directory tree (all contents have been moved individually)
                try {
                    deleteRecursively(source)
                    log(TAG, DEBUG) { "Deleted source directory after move: $source" }
                    sourcesCompleted++
                } catch (e: Exception) {
                    log(TAG, WARN) { "Failed to delete source directory: $source - $e" }
                }
            }
        }

        return MoveAction.State.Result(
            movedFiles = moved,
            skippedFiles = skipped,
            bytesMoved = movedBytes,
        )
    }

    /**
     * Handles permission errors (SecurityException, AccessDeniedException)
     */
    private fun handlePermissionError(
        error: Exception,
        context: ErrorContext,
        workItem: WorkItem,
    ) {
        val path = when (context) {
            is ErrorContext.Read -> context.sourceLookup.lookedUp
            is ErrorContext.Write -> context.destPath
        }

        log(TAG, ERROR) { "${context.operation} - Permission denied: $path - $error" }

        if (issueSkipAllPermission) {
            log(TAG, INFO) { "Skipping permission issue (apply-to-all): $path" }
            skipped.add(context.sourceLookup.lookedUp)
            itemsProcessed++
            return
        }

        val exception = when (context) {
            is ErrorContext.Read -> ReadException(context.operation, context.sourceLookup.lookedUp, error)
            is ErrorContext.Write -> WriteException(path = context.destPath, cause = error)
        }

        if (onIssue == null) throw exception

        val issue = PathActionIssue.InsufficientPermission(
            destination = context.sourceLookup,
            exception = exception,
            canSkip = true,
        )

        workQueue.addFirst(WorkItem.ResolveIssue(issue, workItem, exception))
    }

    /**
     * Handles unknown I/O errors
     */
    private fun handleUnknownError(
        error: Exception,
        context: ErrorContext,
        workItem: WorkItem,
    ) {
        val path = when (context) {
            is ErrorContext.Read -> context.sourceLookup.lookedUp
            is ErrorContext.Write -> context.destPath
        }

        log(TAG, ERROR) { "${context.operation} failed: $path - $error" }

        if (issueSkipAllUnknown) {
            log(TAG, INFO) { "Skipping unknown issue (apply-to-all): $path" }
            skipped.add(context.sourceLookup.lookedUp)
            itemsProcessed++
            return
        }

        val exception = when (context) {
            is ErrorContext.Read -> ReadException(context.operation, context.sourceLookup.lookedUp, error)
            is ErrorContext.Write -> WriteException(path = context.destPath, cause = error)
        }

        if (onIssue == null) throw exception

        val destLookup = when (context) {
            is ErrorContext.Read -> context.sourceLookup
            is ErrorContext.Write -> if (Files.exists(context.destPath.toNioPath())) {
                context.destPath.performLookup()
            } else {
                context.sourceLookup
            }
        }

        val issue = PathActionIssue.UnknownError(
            destination = destLookup,
            exception = exception,
            canRetry = true,
            canSkip = true,
        )

        workQueue.addFirst(WorkItem.ResolveIssue(issue, workItem, exception))
    }

    private fun processScan(item: WorkItem.ScanSource) {
        log(TAG, VERBOSE) { "Scanning source: ${item.source}" }

        val lookup = try {
            item.source.performLookup()
        } catch (e: ReadException) {
            if (item.source == item.topLevelSource) {
                throw e
            }
            log(TAG, WARN) { "Child source disappeared during scan: ${item.source} - ${e.message}" }
            return
        }

        val pathForDestination = item.displayPath
        val relativePath = if (pathForDestination == item.topLevelSource) {
            item.topLevelSource.name
        } else {
            val segments = item.topLevelSource.relativeSegmentsTo(pathForDestination)
            item.topLevelSource.name + File.separator + segments.joinToString(File.separator)
        }
        val cleanRelativePath = relativePath.trimStart('/')
        val destinationPath = LocalPath.build(File(destination.file, cleanRelativePath))

        when (lookup.fileType) {
            FileType.DIRECTORY -> {
                workQueue.addLast(WorkItem.CreateDirectory(lookup, destinationPath, item.topLevelSource))
                totalBytes += lookup.size
                totalItems++

                try {
                    Files.newDirectoryStream(item.source.toNioPath()).use { ds ->
                        for (child in ds) {
                            val childPath = LocalPath.build(child.toFile())
                            val childDisplayPath = LocalPath.build(File(item.displayPath.file, child.fileName.toString()))
                            workQueue.addFirst(
                                WorkItem.ScanSource(
                                    source = childPath,
                                    displayPath = childDisplayPath,
                                    topLevelSource = item.topLevelSource,
                                )
                            )
                        }
                    }
                } catch (_: NoSuchFileException) {
                    log(TAG, WARN) { "Directory disappeared during scan: ${item.source}" }
                } catch (e: AccessDeniedException) {
                    handlePermissionError(
                        error = e,
                        context = ErrorContext.Read(lookup, "List directory contents"),
                        workItem = item,
                    )
                } catch (e: SecurityException) {
                    handlePermissionError(
                        error = e,
                        context = ErrorContext.Read(lookup, "List directory contents"),
                        workItem = item,
                    )
                } catch (e: Exception) {
                    handleUnknownError(
                        error = e,
                        context = ErrorContext.Read(lookup, "List directory contents"),
                        workItem = item,
                    )
                }
            }

            FileType.FILE, FileType.SYMBOLIC_LINK -> {
                workQueue.addLast(WorkItem.MoveFile(lookup, destinationPath, item.topLevelSource))
                totalBytes += lookup.size
                totalItems++
            }

            FileType.UNKNOWN -> throw IllegalStateException("Unknown file type: $lookup")
        }
    }

    private suspend fun processSpaceCheck() {
        while (currentCoroutineContext().isActive) {
            @Suppress("UsableSpace")
            val availableSpace = destination.file.usableSpace
            log(TAG, DEBUG) { "Space check: need $totalBytes bytes, available $availableSpace bytes" }

            if (totalBytes > availableSpace) {
                log(TAG, WARN) { "Insufficient space: need $totalBytes, have $availableSpace" }
                val spaceError = WriteException(
                    path = destination,
                    cause = IOException("Insufficient space: need $totalBytes bytes, available $availableSpace bytes"),
                )
                if (onIssue != null) {
                    val sourceLookup = if (sources.size == 1) {
                        sources.first().performLookup()
                    } else {
                        destination.performLookup()
                    }

                    val issue = PathActionIssue.InsufficientSpace(
                        source = sourceLookup,
                        destination = destination.performLookup(),
                    )
                    when (onIssue.invoke(issue) as PathActionIssue.InsufficientSpace.Resolution) {
                        is PathActionIssue.InsufficientSpace.Resolution.Retry -> {
                            log(TAG, INFO) { "Retrying space check..." }
                            continue
                        }
                        is PathActionIssue.InsufficientSpace.Resolution.Cancel -> throw CancellationException(
                            "Insufficient space",
                            spaceError,
                        )
                    }
                } else {
                    throw spaceError
                }
            } else {
                break
            }
        }
    }

    private suspend fun processCreateDirectory(item: WorkItem.CreateDirectory) {
        val adjustedDest = adjustDestinationForRenames(item.dest, item.sourceLookup.lookedUp)
        val sourceLookup = item.sourceLookup

        log(TAG, VERBOSE) { "Creating directory: $sourceLookup -> $adjustedDest" }

        try {
            onProgress?.invoke(createProgress(sourceLookup, adjustedDest))

            if (Files.exists(adjustedDest.toNioPath())) {
                val destLookup = adjustedDest.performLookup()

                if (destLookup.fileType == FileType.DIRECTORY) {
                    if (issueSkipAllPathExists) {
                        log(TAG, INFO) { "Skipping directory merge (skip apply-to-all): $adjustedDest" }
                        skipped.add(sourceLookup.lookedUp)
                        skippedSourceDirs.add(sourceLookup.lookedUp)
                        itemsProcessed++
                        return
                    }

                    if (issueMergeAllPathExists) {
                        log(TAG, INFO) { "Merging directory (merge apply-to-all): $adjustedDest" }
                        itemsProcessed++
                        return
                    }

                    if (issueOverwriteAllPathExists) {
                        log(TAG, INFO) { "Overwriting directory (overwrite apply-to-all): $adjustedDest" }
                        deleteRecursively(adjustedDest)
                    } else {
                        val existsError = WriteException(path = adjustedDest, cause = FileAlreadyExistsException(adjustedDest.path))
                        if (onIssue == null) {
                            log(TAG, VERBOSE) { "Directory already exists, auto-merging (no issue handler): $adjustedDest" }
                            itemsProcessed++
                            return
                        }

                        val issue = PathActionIssue.PathAlreadyExists(
                            source = sourceLookup,
                            destination = destLookup,
                            canSkip = true,
                            canOverwrite = true,
                            canMerge = true,
                            canRenameSource = true,
                            canRenameDestination = true,
                            suggestedName = generateUniqueName(adjustedDest.name, adjustedDest.file.parentFile!!),
                        )

                        workQueue.addFirst(WorkItem.ResolveIssue(issue, item, existsError))
                        return
                    }
                } else {
                    if (issueSkipAllPathExists) {
                        log(TAG, INFO) { "Skipping file-directory conflict (skip apply-to-all): $adjustedDest" }
                        skipped.add(sourceLookup.lookedUp)
                        skippedSourceDirs.add(sourceLookup.lookedUp)
                        itemsProcessed++
                        return
                    }

                    if (issueOverwriteAllPathExists) {
                        log(TAG, INFO) { "Overwriting file with directory (overwrite apply-to-all): $adjustedDest" }
                        Files.delete(adjustedDest.toNioPath())
                    } else {
                        val existsError = WriteException(path = adjustedDest, cause = FileAlreadyExistsException(adjustedDest.path))
                        if (onIssue == null) throw existsError

                        val issue = PathActionIssue.PathAlreadyExists(
                            source = sourceLookup,
                            destination = destLookup,
                            canSkip = true,
                            canOverwrite = true,
                            canRenameSource = true,
                            canRenameDestination = true,
                            suggestedName = generateUniqueName(adjustedDest.name, adjustedDest.file.parentFile!!),
                        )

                        workQueue.addFirst(WorkItem.ResolveIssue(issue, item, existsError))
                        return
                    }
                }
            }

            Files.createDirectories(adjustedDest.toNioPath())
            moved.add(sourceLookup.lookedUp to adjustedDest)
            itemsProcessed++

        } catch (e: SecurityException) {
            handlePermissionError(
                error = e,
                context = ErrorContext.Write(sourceLookup, adjustedDest, "Create directory"),
                workItem = item,
            )
        } catch (e: Exception) {
            handleUnknownError(
                error = e,
                context = ErrorContext.Write(sourceLookup, adjustedDest, "Create directory"),
                workItem = item,
            )
        } finally {
            onProgress?.invoke(createProgress(sourceLookup, adjustedDest))
        }
    }

    private suspend fun processMoveFile(item: WorkItem.MoveFile) {
        if (isDescendantOfSkippedDir(item.sourceLookup.lookedUp)) {
            log(TAG, VERBOSE) { "Skipping file because parent directory was skipped: ${item.sourceLookup}" }
            skipped.add(item.sourceLookup.lookedUp)
            itemsProcessed++
            return
        }

        val adjustedDest = adjustDestinationForRenames(item.dest, item.sourceLookup.lookedUp)
        val sourceLookup = item.sourceLookup

        log(TAG, VERBOSE) { "Moving file: $sourceLookup -> $adjustedDest" }

        try {
            currentFileSize = sourceLookup.size
            currentFileBytes = 0L
            currentFileStartTime = kotlin.time.Clock.System.now()

            onProgress?.invoke(createProgress(sourceLookup, adjustedDest))

            val parentPath = adjustedDest.file.parentFile?.let { LocalPath.build(it) }
            if (parentPath != null && !Files.exists(parentPath.toNioPath())) {
                Files.createDirectories(parentPath.toNioPath())
            }

            if (Files.exists(adjustedDest.toNioPath())) {
                if (issueSkipAllPathExists) {
                    log(TAG, INFO) { "Skipping existing file (skip apply-to-all): $adjustedDest" }
                    skipped.add(sourceLookup.lookedUp)
                    itemsProcessed++
                    return
                }

                if (!issueOverwriteAllPathExists) {
                    val existsError = WriteException(path = adjustedDest, cause = FileAlreadyExistsException(adjustedDest.path))
                    if (onIssue == null) throw existsError

                    val issue = PathActionIssue.PathAlreadyExists(
                        source = sourceLookup,
                        destination = adjustedDest.performLookup(),
                        canSkip = true,
                        canOverwrite = true,
                        canRenameSource = true,
                        canRenameDestination = true,
                        suggestedName = generateUniqueName(adjustedDest.name, adjustedDest.file.parentFile!!),
                    )

                    workQueue.addFirst(WorkItem.ResolveIssue(issue, item, existsError))
                    return
                } else {
                    log(TAG, INFO) { "Overwriting existing file (overwrite apply-to-all): $adjustedDest" }
                    Files.delete(adjustedDest.toNioPath())
                }
            }

            val sourcePath = sourceLookup.lookedUp.toNioPath()

            // Symlinks need special handling - atomic move doesn't adjust relative targets
            if (sourceLookup.fileType == FileType.SYMBOLIC_LINK) {
                val linkTarget = Files.readSymbolicLink(sourcePath)
                val newTarget = if (linkTarget.isAbsolute) {
                    linkTarget
                } else {
                    // Relative symlink targets are relative to CWD, not to the link's directory
                    // Resolve to absolute path first, then make relative to destination
                    val absoluteTarget = java.nio.file.Paths.get("").toAbsolutePath().resolve(linkTarget).normalize()
                    val absoluteDest = adjustedDest.toNioPath().parent.toAbsolutePath().normalize()
                    absoluteDest.relativize(absoluteTarget)
                }
                Files.createSymbolicLink(adjustedDest.toNioPath(), newTarget)
                Files.delete(sourcePath)
                movedBytes += sourceLookup.size
                moved.add(sourceLookup.lookedUp to adjustedDest)
                itemsProcessed++

                currentFileSize = 0L
                currentFileBytes = 0L
                currentFileStartTime = null
                return
            }

            // Try atomic move for regular files
            try {
                Files.move(
                    sourcePath,
                    adjustedDest.toNioPath(),
                    java.nio.file.LinkOption.NOFOLLOW_LINKS
                )
                log(TAG, DEBUG) { "Atomic move succeeded: ${sourceLookup.lookedUp} -> $adjustedDest" }
                movedBytes += sourceLookup.size
                moved.add(sourceLookup.lookedUp to adjustedDest)
                itemsProcessed++

                currentFileSize = 0L
                currentFileBytes = 0L
                currentFileStartTime = null
                return
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                log(TAG, DEBUG) { "Atomic move not supported, falling back to copy+delete: ${sourceLookup.lookedUp}" }
                // Fall through to copy+delete logic below
            }

            // Atomic move failed - use copy+delete fallback
            // Note: Symlinks are handled above, but keep this for clarity
            if (sourceLookup.fileType == FileType.SYMBOLIC_LINK) {
                val linkTarget = Files.readSymbolicLink(sourcePath)
                val newTarget = if (linkTarget.isAbsolute) {
                    linkTarget
                } else {
                    // Relative symlink targets are relative to CWD, not to the link's directory
                    val absoluteTarget = java.nio.file.Paths.get("").toAbsolutePath().resolve(linkTarget).normalize()
                    val absoluteDest = adjustedDest.toNioPath().parent.toAbsolutePath().normalize()
                    absoluteDest.relativize(absoluteTarget)
                }
                Files.createSymbolicLink(adjustedDest.toNioPath(), newTarget)
                movedBytes += sourceLookup.size
            } else {
                // Chunked file copy with progress tracking
                Files.newInputStream(sourcePath).source().buffer().use { source ->
                    Files.newOutputStream(adjustedDest.toNioPath()).sink().buffer().use { sink ->
                        val buffer = okio.Buffer()
                        var bytesRead: Long

                        while (source.read(buffer, BUFFER_SIZE.toLong()).also { bytesRead = it } != -1L) {
                            sink.write(buffer, bytesRead)
                            currentFileBytes += bytesRead
                            movedBytes += bytesRead

                            if (lastProgressReport.elapsedNow().inWholeMilliseconds >= PROGRESS_REPORT_INTERVAL_MS) {
                                lastProgressReport = kotlin.time.TimeSource.Monotonic.markNow()
                                onProgress?.invoke(createProgress(sourceLookup, adjustedDest))
                            }
                        }
                        sink.flush()
                    }
                }

                // Copy file attributes
                if (options.preserveAttributes) {
                    try {
                        val lastModified = Files.getLastModifiedTime(sourcePath)
                        Files.setLastModifiedTime(adjustedDest.toNioPath(), lastModified)

                        if (Files.getFileAttributeView(sourcePath, PosixFileAttributeView::class.java) != null) {
                            val permissions = Files.getPosixFilePermissions(sourcePath)
                            Files.setPosixFilePermissions(adjustedDest.toNioPath(), permissions)
                        }
                    } catch (e: Exception) {
                        log(TAG, WARN) { "Failed to copy attributes: $e" }
                    }
                }
            }

            // Delete source after successful copy
            Files.delete(sourcePath)
            moved.add(sourceLookup.lookedUp to adjustedDest)
            itemsProcessed++

            currentFileSize = 0L
            currentFileBytes = 0L
            currentFileStartTime = null
        } catch (securityError: SecurityException) {
            handlePermissionError(
                error = securityError,
                context = ErrorContext.Read(sourceLookup, "Read file for move"),
                workItem = item,
            )
        } catch (moveError: Exception) {
            handleUnknownError(
                error = moveError,
                context = ErrorContext.Write(sourceLookup, adjustedDest, "Move file"),
                workItem = item,
            )
        }
    }

    private suspend fun processResolveIssue(item: WorkItem.ResolveIssue) {
        val resolution = onIssue!!.invoke(item.issue)

        when (item.issue) {
            is PathActionIssue.PathAlreadyExists -> {
                when (val res = resolution as PathActionIssue.PathAlreadyExists.Resolution) {
                    is PathActionIssue.PathAlreadyExists.Resolution.Cancel -> throw CancellationException(
                        "User cancelled",
                        item.exception,
                    )
                    is PathActionIssue.PathAlreadyExists.Resolution.Skip -> {
                        if (res.applyToAll) issueSkipAllPathExists = true
                        val source = when (val orig = item.originalItem) {
                            is WorkItem.CreateDirectory -> orig.sourceLookup.lookedUp
                            is WorkItem.MoveFile -> orig.sourceLookup.lookedUp
                            else -> error("Unexpected original item type")
                        }
                        skipped.add(source)
                        if (item.originalItem is WorkItem.CreateDirectory) {
                            skippedSourceDirs.add(source)
                        }
                        itemsProcessed++
                    }
                    is PathActionIssue.PathAlreadyExists.Resolution.Overwrite -> {
                        if (res.applyToAll) issueOverwriteAllPathExists = true
                        when (val orig = item.originalItem) {
                            is WorkItem.CreateDirectory, is WorkItem.MoveFile -> {
                                val dest = if (orig is WorkItem.CreateDirectory) orig.dest else (orig as WorkItem.MoveFile).dest
                                if (Files.exists(dest.toNioPath())) {
                                    if (Files.isDirectory(dest.toNioPath())) {
                                        deleteRecursively(dest)
                                    } else {
                                        Files.delete(dest.toNioPath())
                                    }
                                }
                            }
                            else -> error("Unexpected original item type")
                        }
                        workQueue.addFirst(item.originalItem)
                    }
                    is PathActionIssue.PathAlreadyExists.Resolution.Merge -> {
                        if (res.applyToAll) issueMergeAllPathExists = true
                        itemsProcessed++
                    }
                    is PathActionIssue.PathAlreadyExists.Resolution.RenameSource -> {
                        log(TAG, INFO) { "User chose rename source: ${res.newName}" }
                        when (val orig = item.originalItem) {
                            is WorkItem.CreateDirectory -> {
                                val newDestPath = LocalPath.build(File(orig.dest.file.parentFile!!, res.newName))
                                Files.createDirectories(newDestPath.toNioPath())
                                renamedSourceDirs[orig.sourceLookup.lookedUp] = newDestPath
                                itemsProcessed++
                            }
                            is WorkItem.MoveFile -> {
                                val newDestPath = LocalPath.build(File(orig.dest.file.parentFile!!, res.newName))
                                val adjustedNewDestPath = adjustDestinationForRenames(newDestPath, orig.sourceLookup.lookedUp)
                                Files.move(
                                    orig.sourceLookup.lookedUp.toNioPath(),
                                    adjustedNewDestPath.toNioPath(),
                                )
                                moved.add(orig.sourceLookup.lookedUp to adjustedNewDestPath)
                                movedBytes += orig.sourceLookup.size
                                itemsProcessed++
                            }
                            else -> error("Unexpected original item type")
                        }
                    }
                    is PathActionIssue.PathAlreadyExists.Resolution.RenameDestination -> {
                        log(TAG, INFO) { "User chose rename destination: ${res.newName}" }
                        when (val orig = item.originalItem) {
                            is WorkItem.CreateDirectory, is WorkItem.MoveFile -> {
                                val dest = when (orig) {
                                    is WorkItem.CreateDirectory -> orig.dest
                                    is WorkItem.MoveFile -> orig.dest
                                    else -> error("Unreachable")
                                }
                                val newDestPath = LocalPath.build(File(dest.file.parentFile!!, res.newName))
                                Files.move(dest.toNioPath(), newDestPath.toNioPath())
                                workQueue.addFirst(orig)
                            }
                            else -> error("Unexpected original item type")
                        }
                    }
                }
            }
            is PathActionIssue.InsufficientPermission -> {
                when (val res = resolution as PathActionIssue.InsufficientPermission.Resolution) {
                    is PathActionIssue.InsufficientPermission.Resolution.Cancel -> throw CancellationException(
                        "User cancelled",
                        item.exception,
                    )
                    is PathActionIssue.InsufficientPermission.Resolution.Skip -> {
                        if (res.applyToAll) issueSkipAllPermission = true
                        val source = when (val orig = item.originalItem) {
                            is WorkItem.CreateDirectory -> orig.sourceLookup.lookedUp
                            is WorkItem.MoveFile -> orig.sourceLookup.lookedUp
                            else -> error("Unexpected original item type")
                        }
                        skipped.add(source)
                        itemsProcessed++
                    }
                }
            }
            is PathActionIssue.UnknownError -> {
                when (val res = resolution as PathActionIssue.UnknownError.Resolution) {
                    is PathActionIssue.UnknownError.Resolution.Cancel -> throw CancellationException(
                        "User cancelled",
                        item.exception,
                    )
                    is PathActionIssue.UnknownError.Resolution.Retry -> {
                        workQueue.addFirst(item.originalItem)
                    }
                    is PathActionIssue.UnknownError.Resolution.Skip -> {
                        if (res.applyToAll) issueSkipAllUnknown = true
                        val source = when (val orig = item.originalItem) {
                            is WorkItem.CreateDirectory -> orig.sourceLookup.lookedUp
                            is WorkItem.MoveFile -> orig.sourceLookup.lookedUp
                            else -> error("Unexpected original item type: ${orig::class.simpleName}")
                        }
                        skipped.add(source)
                        itemsProcessed++
                    }
                }
            }
            else -> error("Unexpected issue type: ${item.issue}")
        }
    }

    private suspend fun ensureDestinationExists() {
        try {
            if (!Files.exists(destination.toNioPath())) {
                Files.createDirectories(destination.toNioPath())
                log(TAG) { "Destination directory created: $destination" }
                return
            }

            if (Files.isDirectory(destination.toNioPath())) {
                log(TAG) { "Destination is an existing directory: $destination" }
                return
            }

            log(TAG, WARN) { "Destination exists but is not a directory: $destination" }

            if (onIssue == null) {
                throw IOException("Destination exists but is not a directory: ${destination.path}")
            }

            val existsError = FileAlreadyExistsException(destination.path)
            val destLookup = destination.performLookup()
            val sourceLookup = sources.first().performLookup()

            val issue = PathActionIssue.PathAlreadyExists(
                source = sourceLookup,
                destination = destLookup,
                canOverwrite = true,
                canRenameDestination = true,
                suggestedName = generateUniqueName(destination.name, destination.file.parentFile!!),
            )

            when (val resolution = onIssue.invoke(issue) as PathActionIssue.PathAlreadyExists.Resolution) {
                is PathActionIssue.PathAlreadyExists.Resolution.Overwrite -> {
                    log(TAG, INFO) { "Overwriting file at destination: $destination" }
                    Files.delete(destination.toNioPath())
                }
                is PathActionIssue.PathAlreadyExists.Resolution.RenameDestination -> {
                    log(TAG, INFO) { "Renaming existing file: $destination -> ${resolution.newName}" }
                    val newDestPath = LocalPath.build(File(destination.file.parentFile!!, resolution.newName))
                    Files.move(destination.toNioPath(), newDestPath.toNioPath())
                }
                is PathActionIssue.PathAlreadyExists.Resolution.Cancel -> throw CancellationException(
                    "User cancelled",
                    existsError,
                )
                is PathActionIssue.PathAlreadyExists.Resolution.Skip,
                is PathActionIssue.PathAlreadyExists.Resolution.RenameSource,
                is PathActionIssue.PathAlreadyExists.Resolution.Merge -> {
                    throw UnsupportedOperationException("Invalid resolution for destination conflict", existsError)
                }
            }

            Files.createDirectories(destination.toNioPath())
        } catch (e: Exception) {
            throw WriteException(path = destination, cause = e)
        }
    }

    private fun adjustDestinationForRenames(dest: LocalPath, source: LocalPath): LocalPath {
        return renamedSourceDirs.entries.find { (renamedSource, _) ->
            renamedSource.isAncestorOf(source)
        }?.let { (renamedSource, newDestDir) ->
            val relativeSegments = renamedSource.relativeSegmentsTo(source)
            val relativePath = relativeSegments.joinToString(File.separator)
            LocalPath.build(File(newDestDir.file, relativePath))
        } ?: dest
    }

    private fun isDescendantOfSkippedDir(source: LocalPath): Boolean {
        return skippedSourceDirs.any { skippedDir ->
            skippedDir.isAncestorOf(source)
        }
    }

    private fun createProgress(
        sourceLookup: LocalPathLookup,
        dest: LocalPath,
    ): MoveAction.State.Progress<LocalPath> = MoveAction.State.Progress(
        currentSource = sourceLookup.lookedUp,
        currentDestination = dest,
        totalSources = totalSources,
        sourcesCompleted = sourcesCompleted,
        totalFiles = totalItems,
        filesProcessed = itemsProcessed,
        totalBytes = totalBytes,
        bytesMoved = movedBytes,
    )

    private fun deleteRecursively(path: LocalPath) {
        if (!Files.exists(path.toNioPath())) return

        Files.walk(path.toNioPath())
            .sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
    }

    private fun calculateDirectorySize(path: LocalPath): Long {
        if (!Files.exists(path.toNioPath())) return 0L

        return try {
            Files.walk(path.toNioPath()).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) || Files.isSymbolicLink(it) }
                    .mapToLong { filePath ->
                        try {
                            Files.size(filePath)
                        } catch (e: Exception) {
                            0L
                        }
                    }
                    .sum()
            }
        } catch (e: Exception) {
            log(TAG, WARN) { "Failed to calculate directory size for $path: ${e.message}" }
            0L
        }
    }

    private fun generateUniqueName(originalName: String, parentDir: File): String {
        val file = File(parentDir, originalName)
        if (!file.exists()) return originalName

        val nameParts = originalName.split('.')
        val baseName = if (nameParts.size > 1) {
            nameParts.dropLast(1).joinToString(".")
        } else {
            originalName
        }
        val extension = if (nameParts.size > 1) ".${nameParts.last()}" else ""

        var counter = 1
        var newName: String
        do {
            newName = "$baseName ($counter)$extension"
            counter++
        } while (File(parentDir, newName).exists())

        return newName
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024 // 64KB chunks
        private const val PROGRESS_REPORT_INTERVAL_MS = 100L // Report every 100ms
    }
}

suspend fun LocalPath.move(
    destination: LocalPath,
    options: MoveAction.Options<LocalPath> = MoveAction.Options(),
    onProgress: (suspend (MoveAction.State.Progress<LocalPath>) -> Unit)? = null,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null,
) = setOf(this).move(destination, options, onProgress, onIssue)

suspend fun Collection<LocalPath>.move(
    destination: LocalPath,
    options: MoveAction.Options<LocalPath> = MoveAction.Options(),
    onProgress: (suspend (MoveAction.State.Progress<LocalPath>) -> Unit)? = null,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null,
): MoveAction.State.Result<LocalPath> {
    log(TAG, DEBUG) {
        "move(): Moving $size targets to $destination (options=$options, onProgress=$onProgress, onIssue=$onIssue)"
    }

    return LocalPathMove(
        sources = this,
        destination = destination,
        options = options,
        onProgress = onProgress,
        onIssue = onIssue,
    ).execute()
}

private val TAG = logTag("Gateway", "LocalPath", "Move")
