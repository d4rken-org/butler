package eu.darken.butler.common.files.local

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.CopyAction
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
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import kotlin.coroutines.cancellation.CancellationException

internal class LocalPathCopy(
    private val sources: Collection<LocalPath>,
    private val destination: LocalPath,
    private val options: CopyAction.Options<LocalPath>,
    private val onProgress: (suspend (CopyAction.State.Progress<LocalPath, LocalPathLookup>) -> Unit)?,
    private val onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?
) {
    private val copied = linkedSetOf<Pair<LocalPath, LocalPath>>()
    private val skipped = linkedSetOf<LocalPath>()
    private var copiedBytes = 0L

    // Current file progress tracking
    private var currentFileSize = 0L
    private var currentFileBytes = 0L
    private var currentFileStartTime: kotlin.time.Instant? = null
    private var lastProgressReport = kotlin.time.TimeSource.Monotonic.markNow()

    private var issueSkipAllPathExists = false
    private var issueOverwriteAllPathExists = false
    private var issueMergeAllPathExists = false
    private var issueSkippAllPermission = false
    private var issueSkippAllUnknown = false

    // Track renamed and skipped directories across all sources
    private val skippedSourceDirs = mutableSetOf<LocalPath>()
    private val renamedSourceDirs = mutableMapOf<LocalPath, LocalPath>()

    // Progress tracking
    private var totalItems = 0
    private var itemsProcessed = 0

    // Total accumulated size from all scans
    private var totalBytes = 0L

    // Work queue for processing copy operations
    private var workQueue = ArrayDeque<WorkItem>()

    // Single-use flag
    private var hasExecuted = false

    /**
     * Sealed hierarchy of work items for the copy queue
     */
    private sealed class WorkItem {
        /**
         * Scan a source directory tree and queue create/copy items
         * @param source The actual path to scan (may be resolved from symlink)
         * @param displayPath The path to use for destination calculation (preserves symlink names)
         * @param topLevelSource The root source path for this scan tree
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
            val topLevelSource: LocalPath
        ) : WorkItem()

        /**
         * Copy a file to the destination
         */
        data class CopyFile(
            val sourceLookup: LocalPathLookup,
            val dest: LocalPath,
            val topLevelSource: LocalPath
        ) : WorkItem()

        /**
         * Resolve an issue that occurred during processing
         */
        data class ResolveIssue(
            val issue: PathActionIssue,
            val originalItem: WorkItem,
            val exception: Exception
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

    suspend fun execute(): CopyAction.State.Result<LocalPath, LocalPathLookup> {
        check(!hasExecuted) { "LocalPathCopyTool can only be executed once" }
        hasExecuted = true

        ensureDestinationExists()

        // Initialize queue with scan items for each source
        workQueue.addAll(sources.map { WorkItem.ScanSource(it) })
        // After all sources are scanned, we need to do a space check
        workQueue.add(WorkItem.CheckSpace)

        // Process work queue
        while (workQueue.isNotEmpty() && currentCoroutineContext().isActive) {
            when (val item = workQueue.removeFirst()) {
                is WorkItem.ScanSource -> processScan(item)
                is WorkItem.CheckSpace -> {
                    processSpaceCheck()
                }
                is WorkItem.CreateDirectory -> {
                    processCreateDirectory(item)
                }
                is WorkItem.CopyFile -> {
                    processCopyFile(item)
                }
                is WorkItem.ResolveIssue -> {
                    processResolveIssue(item)
                }
            }
        }

        return CopyAction.State.Result(
            copied = copied,
            skipped = skipped,
            copiedBytes = copiedBytes,
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

        if (issueSkippAllPermission) {
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

        if (issueSkippAllUnknown) {
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
            canSkip = true
        )

        workQueue.addFirst(WorkItem.ResolveIssue(issue, workItem, exception))
    }

    private fun processScan(item: WorkItem.ScanSource) {
        log(TAG, VERBOSE) { "Scanning source: ${item.source}" }

        val lookup = try {
            item.source.performLookup()
        } catch (e: ReadException) {
            // Distinguish between top-level sources and child sources
            if (item.source == item.topLevelSource) {
                // Top-level source doesn't exist - user explicitly asked to copy it, so throw
                throw e
            }
            // Child source disappeared during scan (likely concurrent deletion) - skip silently
            log(TAG, WARN) { "Child source disappeared during scan: ${item.source} - ${e.message}" }
            return
        }

        // Calculate destination path relative to top-level source
        // Use displayPath for destination calculation (preserves symlink names)
        val pathForDestination = item.displayPath
        val relativePath = if (pathForDestination == item.topLevelSource) {
            item.topLevelSource.name
        } else {
            val segments = item.topLevelSource.relativeSegmentsTo(pathForDestination)
            item.topLevelSource.name + File.separator + segments.joinToString(File.separator)
        }
        val cleanRelativePath = relativePath.trimStart('/')
        val destinationPath = LocalPath.build(File(destination.file, cleanRelativePath))

        // Queue work item for this item itself
        when (lookup.fileType) {
            FileType.DIRECTORY -> {
                workQueue.addLast(WorkItem.CreateDirectory(lookup, destinationPath, item.topLevelSource))
                totalBytes += lookup.size
                totalItems++

                // List and queue children
                try {
                    Files.newDirectoryStream(item.source.toNioPath()).use { ds ->
                        for (child in ds) {
                            val childPath = LocalPath.build(child.toFile())
                            // Maintain displayPath mapping for children
                            val childDisplayPath = LocalPath.build(File(item.displayPath.file, child.fileName.toString()))
                            // Add child scan to front (processed before CheckSpace and work items)
                            workQueue.addFirst(
                                WorkItem.ScanSource(
                                    source = childPath,
                                    displayPath = childDisplayPath,
                                    topLevelSource = item.topLevelSource
                                )
                            )
                        }
                    }
                } catch (_: NoSuchFileException) {
                    // Directory disappeared between lookup and listing
                    log(TAG, WARN) { "Directory disappeared during scan: ${item.source}" }
                } catch (e: AccessDeniedException) {
                    handlePermissionError(
                        error = e,
                        context = ErrorContext.Read(lookup, "List directory contents"),
                        workItem = item
                    )
                } catch (e: SecurityException) {
                    handlePermissionError(
                        error = e,
                        context = ErrorContext.Read(lookup, "List directory contents"),
                        workItem = item
                    )
                } catch (e: Exception) {
                    handleUnknownError(
                        error = e,
                        context = ErrorContext.Read(lookup, "List directory contents"),
                        workItem = item
                    )
                }
            }

            FileType.FILE -> {
                workQueue.addLast(WorkItem.CopyFile(lookup, destinationPath, item.topLevelSource))
                totalBytes += lookup.size
                totalItems++
            }

            FileType.SYMBOLIC_LINK -> {
                if (options.followSymlinks) {
                    try {
                        val targetPath = Files.readSymbolicLink(item.source.toNioPath())
                        val resolvedPath = item.source.toNioPath().parent.resolve(targetPath).normalize()
                        if (Files.isDirectory(resolvedPath)) {
                            workQueue.addLast(WorkItem.CreateDirectory(lookup, destinationPath, item.topLevelSource))
                            totalBytes += lookup.size
                            totalItems++

                            // Re-queue to list children
                            // Use resolved path for scanning, but preserve displayPath for destination calc
                            workQueue.addFirst(
                                WorkItem.ScanSource(
                                    source = LocalPath.build(resolvedPath.toFile()),
                                    displayPath = item.displayPath,
                                    topLevelSource = item.topLevelSource
                                )
                            )
                        } else {
                            workQueue.addLast(WorkItem.CopyFile(lookup, destinationPath, item.topLevelSource))
                            totalBytes += lookup.size
                            totalItems++
                        }
                    } catch (e: IOException) {
                        log(TAG, WARN) { "Cannot resolve symlink: $lookup - ${e.message}" }
                        workQueue.addLast(WorkItem.CopyFile(lookup, destinationPath, item.topLevelSource))
                        totalBytes += lookup.size
                        totalItems++
                    }
                } else {
                    workQueue.addLast(WorkItem.CopyFile(lookup, destinationPath, item.topLevelSource))
                    totalBytes += lookup.size
                    totalItems++
                }
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
                    cause = IOException("Insufficient space: need $totalBytes bytes, available $availableSpace bytes")
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
                            spaceError
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
        // Adjust destination if parent was renamed
        val adjustedDest = adjustDestinationForRenames(item.dest, item.sourceLookup.lookedUp)

        val sourceLookup = item.sourceLookup

        log(TAG, VERBOSE) { "tryCreateDirectory(): $sourceLookup -> $adjustedDest" }

        try {
            onProgress?.invoke(createProgress(sourceLookup, adjustedDest))

            // Check if destination already exists
            if (Files.exists(adjustedDest.toNioPath())) {
                val destLookup = adjustedDest.performLookup()

                // Directory-directory conflict
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
                    // File-directory conflict
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

            // Create directory or symlink
            if (sourceLookup.fileType == FileType.SYMBOLIC_LINK && !options.followSymlinks) {
                val sourcePath = sourceLookup.lookedUp.toNioPath()
                val linkTarget = Files.readSymbolicLink(sourcePath)
                val newTarget = if (linkTarget.isAbsolute) {
                    linkTarget
                } else {
                    val absoluteTarget = sourcePath.parent.resolve(linkTarget).normalize()
                    adjustedDest.toNioPath().parent.relativize(absoluteTarget)
                }
                if (Files.exists(adjustedDest.toNioPath())) {
                    Files.delete(adjustedDest.toNioPath())
                }
                Files.createSymbolicLink(adjustedDest.toNioPath(), newTarget)
            } else {
                Files.createDirectories(adjustedDest.toNioPath())
            }

            copied.add(sourceLookup.lookedUp to adjustedDest)
            itemsProcessed++

        } catch (e: SecurityException) {
            handlePermissionError(
                error = e,
                context = ErrorContext.Write(sourceLookup, adjustedDest, "Create directory"),
                workItem = item
            )
        } catch (e: Exception) {
            handleUnknownError(
                error = e,
                context = ErrorContext.Write(sourceLookup, adjustedDest, "Create directory"),
                workItem = item
            )
        } finally {
            onProgress?.invoke(createProgress(sourceLookup, adjustedDest))
        }
    }

    private suspend fun processCopyFile(item: WorkItem.CopyFile) {
        // Skip if parent directory was skipped
        if (isDescendantOfSkippedDir(item.sourceLookup.lookedUp)) {
            log(TAG, VERBOSE) { "Skipping file because parent directory was skipped: ${item.sourceLookup}" }
            skipped.add(item.sourceLookup.lookedUp)
            itemsProcessed++
            return
        }

        // Adjust destination if parent was renamed
        val adjustedDest = adjustDestinationForRenames(item.dest, item.sourceLookup.lookedUp)

        val sourceLookup = item.sourceLookup

        log(TAG, VERBOSE) { "tryCopyFile(): $sourceLookup -> $adjustedDest" }

        try {
            // Set current file size for progress tracking
            currentFileSize = sourceLookup.size
            currentFileBytes = 0L
            currentFileStartTime = kotlin.time.Clock.System.now()

            onProgress?.invoke(createProgress(sourceLookup, adjustedDest))

            // Ensure parent directory exists
            val parentPath = adjustedDest.file.parentFile?.let { LocalPath.build(it) }
            if (parentPath != null && !Files.exists(parentPath.toNioPath())) {
                Files.createDirectories(parentPath.toNioPath())
            }

            // Check if destination already exists
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
                }
            }

            // Perform the copy
            val sourcePath = sourceLookup.lookedUp.toNioPath()

            if (sourceLookup.fileType == FileType.SYMBOLIC_LINK) {
                if (options.followSymlinks) {
                    val targetPath = Files.readSymbolicLink(sourcePath).let { target ->
                        sourcePath.parent.resolve(target).normalize()
                    }
                    Files.copy(
                        targetPath,
                        adjustedDest.toNioPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES
                    )
                } else {
                    val linkTarget = Files.readSymbolicLink(sourcePath)
                    val newTarget = if (linkTarget.isAbsolute) {
                        linkTarget
                    } else {
                        val absoluteTarget = sourcePath.parent.resolve(linkTarget).normalize()
                        adjustedDest.toNioPath().parent.relativize(absoluteTarget)
                    }
                    if (Files.exists(adjustedDest.toNioPath())) {
                        Files.delete(adjustedDest.toNioPath())
                    }
                    Files.createSymbolicLink(adjustedDest.toNioPath(), newTarget)
                }
                copiedBytes += sourceLookup.size
            } else {
                // Chunked file copy with OkIO for progress tracking
                Files.newInputStream(sourcePath).source().buffer().use { source ->
                    Files.newOutputStream(adjustedDest.toNioPath()).sink().buffer().use { sink ->
                        val buffer = okio.Buffer()
                        var bytesRead: Long

                        while (source.read(buffer, BUFFER_SIZE.toLong()).also { bytesRead = it } != -1L) {
                            sink.write(buffer, bytesRead)
                            currentFileBytes += bytesRead
                            copiedBytes += bytesRead

                            // Report progress at intervals to avoid overwhelming the flow
                            if (lastProgressReport.elapsedNow().inWholeMilliseconds >= PROGRESS_REPORT_INTERVAL_MS) {
                                lastProgressReport = kotlin.time.TimeSource.Monotonic.markNow()
                                onProgress?.invoke(createProgress(sourceLookup, adjustedDest))
                            }
                        }
                        sink.flush()
                    }
                }

                // Copy file attributes (without re-copying content)
                if (options.preserveAttributes) {
                    try {
                        // Copy last modified time
                        val lastModified = Files.getLastModifiedTime(sourcePath)
                        Files.setLastModifiedTime(adjustedDest.toNioPath(), lastModified)

                        // Copy POSIX permissions if available
                        if (Files.getFileAttributeView(sourcePath, PosixFileAttributeView::class.java) != null) {
                            val permissions = Files.getPosixFilePermissions(sourcePath)
                            Files.setPosixFilePermissions(adjustedDest.toNioPath(), permissions)
                        }
                    } catch (e: Exception) {
                        log(TAG, WARN) { "Failed to copy attributes: $e" }
                    }
                }
            }

            copied.add(sourceLookup.lookedUp to adjustedDest)
            itemsProcessed++

            // Reset current file tracking
            currentFileSize = 0L
            currentFileBytes = 0L
            currentFileStartTime = null
        } catch (securityError: SecurityException) {
            handlePermissionError(
                error = securityError,
                context = ErrorContext.Read(sourceLookup, "Read file for copy"),
                workItem = item
            )
        } catch (copyError: Exception) {
            handleUnknownError(
                error = copyError,
                context = ErrorContext.Write(sourceLookup, adjustedDest, "Copy file"),
                workItem = item
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
                        item.exception
                    )
                    is PathActionIssue.PathAlreadyExists.Resolution.Skip -> {
                        if (res.applyToAll) issueSkipAllPathExists = true
                        val sourceLookup = when (val orig = item.originalItem) {
                            is WorkItem.CreateDirectory -> orig.sourceLookup
                            is WorkItem.CopyFile -> orig.sourceLookup
                            else -> error("Unexpected original item type")
                        }
                        skipped.add(sourceLookup.lookedUp)
                        if (item.originalItem is WorkItem.CreateDirectory) {
                            skippedSourceDirs.add(sourceLookup.lookedUp)
                        }
                        itemsProcessed++
                    }
                    is PathActionIssue.PathAlreadyExists.Resolution.Overwrite -> {
                        if (res.applyToAll) issueOverwriteAllPathExists = true
                        // Perform the overwrite before requeueing
                        when (val orig = item.originalItem) {
                            is WorkItem.CreateDirectory -> {
                                val dest = orig.dest
                                if (Files.exists(dest.toNioPath())) {
                                    if (Files.isDirectory(dest.toNioPath())) {
                                        deleteRecursively(dest)
                                    } else {
                                        Files.delete(dest.toNioPath())
                                    }
                                }
                            }
                            is WorkItem.CopyFile -> {
                                val dest = orig.dest
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
                                copied.add(orig.sourceLookup.lookedUp to newDestPath)
                                renamedSourceDirs[orig.sourceLookup.lookedUp] = newDestPath
                                itemsProcessed++
                            }
                            is WorkItem.CopyFile -> {
                                val newDestPath = LocalPath.build(File(orig.dest.file.parentFile!!, res.newName))
                                Files.copy(
                                    orig.sourceLookup.lookedUp.toNioPath(),
                                    newDestPath.toNioPath(),
                                    StandardCopyOption.COPY_ATTRIBUTES
                                )
                                copiedBytes += orig.sourceLookup.size
                                copied.add(orig.sourceLookup.lookedUp to newDestPath)
                                itemsProcessed++
                            }
                            else -> error("Unexpected original item type")
                        }
                    }
                    is PathActionIssue.PathAlreadyExists.Resolution.RenameDestination -> {
                        log(TAG, INFO) { "User chose rename destination: ${res.newName}" }
                        when (val orig = item.originalItem) {
                            is WorkItem.CreateDirectory, is WorkItem.CopyFile -> {
                                val dest =
                                    if (orig is WorkItem.CreateDirectory) orig.dest else (orig as WorkItem.CopyFile).dest
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
                        item.exception
                    )
                    is PathActionIssue.InsufficientPermission.Resolution.Skip -> {
                        if (res.applyToAll) issueSkippAllPermission = true
                        val sourceLookup = when (val orig = item.originalItem) {
                            is WorkItem.CreateDirectory -> orig.sourceLookup
                            is WorkItem.CopyFile -> orig.sourceLookup
                            else -> error("Unexpected original item type")
                        }
                        skipped.add(sourceLookup.lookedUp)
                        itemsProcessed++
                    }
                }
            }
            is PathActionIssue.UnknownError -> {
                when (val res = resolution as PathActionIssue.UnknownError.Resolution) {
                    is PathActionIssue.UnknownError.Resolution.Cancel -> throw CancellationException(
                        "User cancelled",
                        item.exception
                    )
                    is PathActionIssue.UnknownError.Resolution.Retry -> {
                        workQueue.addFirst(item.originalItem)
                    }
                    is PathActionIssue.UnknownError.Resolution.Skip -> {
                        if (res.applyToAll) issueSkippAllUnknown = true
                        val sourceLookup = when (val orig = item.originalItem) {
                            is WorkItem.CreateDirectory -> orig.sourceLookup
                            is WorkItem.CopyFile -> orig.sourceLookup
                            else -> error("Unexpected original item type: ${orig::class.simpleName}")
                        }
                        skipped.add(sourceLookup.lookedUp)
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
                    existsError
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
    ): CopyAction.State.Progress<LocalPath, LocalPathLookup> = CopyAction.State.Progress(
        currentSource = sourceLookup.lookedUp,
        currentDestination = dest,
        copiedBytes = copiedBytes,
        totalBytes = totalBytes,
        currentFileSize = currentFileSize,
        currentFileBytes = currentFileBytes,
        currentFileStartTime = currentFileStartTime,
        primaryProgress = eu.darken.butler.common.progress.Progress.Data(
            primary = R.string.general_copy_progress_title.toCaString(),
            secondary = sourceLookup.userReadablePath,
            count = eu.darken.butler.common.progress.Progress.Count.Counter(
                current = itemsProcessed,
                max = totalItems
            )
        ),
        secondaryProgress = eu.darken.butler.common.progress.Progress.Data(
            primary = sourceLookup.lookedUp.name.toCaString(),
            count = eu.darken.butler.common.progress.Progress.Count.Size(
                current = currentFileBytes,
                max = currentFileSize
            )
        )
    )

    private fun deleteRecursively(path: LocalPath) {
        if (!Files.exists(path.toNioPath())) return

        Files.walk(path.toNioPath())
            .sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
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

suspend fun LocalPath.copy(
    destination: LocalPath,
    options: CopyAction.Options<LocalPath> = CopyAction.Options(),
    onProgress: (suspend (CopyAction.State.Progress<LocalPath, LocalPathLookup>) -> Unit)? = null,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
) = setOf(this).copy(destination, options, onProgress, onIssue)

suspend fun Collection<LocalPath>.copy(
    destination: LocalPath,
    options: CopyAction.Options<LocalPath> = CopyAction.Options(),
    onProgress: (suspend (CopyAction.State.Progress<LocalPath, LocalPathLookup>) -> Unit)? = null,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
): CopyAction.State.Result<LocalPath, LocalPathLookup> {
    log(TAG, DEBUG) {
        "copy(): Copying $size targets to $destination (options=$options, onProgress=$onProgress, onIssue=$onIssue)"
    }

    return LocalPathCopy(
        sources = this,
        destination = destination,
        options = options,
        onProgress = onProgress,
        onIssue = onIssue
    ).execute()
}

private val TAG = logTag("Gateway", "LocalPath", "Copy")