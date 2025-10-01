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
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
    private var bytesCopied = 0L

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
    private var totalSizeNeeded = 0L

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
            bytesCopied = bytesCopied,
        )
    }

    private fun processScan(item: WorkItem.ScanSource) {
        log(TAG, VERBOSE) { "Scanning source: ${item.source}" }

        val lookup = item.source.performLookup()

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
                totalSizeNeeded += lookup.size
                totalItems++

                // List and queue children
                Files.newDirectoryStream(item.source.file.toPath()).use { ds ->
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
            }

            FileType.FILE -> {
                workQueue.addLast(WorkItem.CopyFile(lookup, destinationPath, item.topLevelSource))
                totalSizeNeeded += lookup.size
                totalItems++
            }

            FileType.SYMBOLIC_LINK -> {
                if (options.followSymlinks) {
                    try {
                        val targetPath = Files.readSymbolicLink(item.source.file.toPath())
                        val resolvedPath = item.source.file.toPath().parent.resolve(targetPath).normalize()
                        if (Files.isDirectory(resolvedPath)) {
                            workQueue.addLast(WorkItem.CreateDirectory(lookup, destinationPath, item.topLevelSource))
                            totalSizeNeeded += lookup.size
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
                            totalSizeNeeded += lookup.size
                            totalItems++
                        }
                    } catch (e: IOException) {
                        log(TAG, WARN) { "Cannot resolve symlink: $lookup - ${e.message}" }
                        workQueue.addLast(WorkItem.CopyFile(lookup, destinationPath, item.topLevelSource))
                        totalSizeNeeded += lookup.size
                        totalItems++
                    }
                } else {
                    workQueue.addLast(WorkItem.CopyFile(lookup, destinationPath, item.topLevelSource))
                    totalSizeNeeded += lookup.size
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
            log(TAG, DEBUG) { "Space check: need $totalSizeNeeded bytes, available $availableSpace bytes" }

            if (totalSizeNeeded > availableSpace) {
                log(TAG, WARN) { "Insufficient space: need $totalSizeNeeded, have $availableSpace" }
                val spaceError = WriteException(
                    path = destination,
                    cause = IOException("Insufficient space: need $totalSizeNeeded bytes, available $availableSpace bytes")
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
        val dest = adjustedDest

        log(TAG, VERBOSE) { "tryCreateDirectory(): $sourceLookup -> $dest" }

        try {
            onProgress?.invoke(createProgress(sourceLookup, dest))

            // Check if destination already exists
            if (Files.exists(dest.file.toPath())) {
                val destLookup = dest.performLookup()

                // Directory-directory conflict
                if (destLookup.fileType == FileType.DIRECTORY) {
                    if (issueSkipAllPathExists) {
                        log(TAG, INFO) { "Skipping directory merge (skip apply-to-all): $dest" }
                        skipped.add(sourceLookup.lookedUp)
                        skippedSourceDirs.add(sourceLookup.lookedUp)
                        itemsProcessed++
                        return
                    }

                    if (issueMergeAllPathExists) {
                        log(TAG, INFO) { "Merging directory (merge apply-to-all): $dest" }
                        itemsProcessed++
                        return
                    }

                    if (issueOverwriteAllPathExists) {
                        log(TAG, INFO) { "Overwriting directory (overwrite apply-to-all): $dest" }
                        deleteRecursively(dest)
                    } else {
                        val existsError = WriteException(path = dest, cause = FileAlreadyExistsException(dest.path))
                        if (onIssue == null) {
                            log(TAG, VERBOSE) { "Directory already exists, auto-merging (no issue handler): $dest" }
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
                            suggestedName = generateUniqueName(dest.name, dest.file.parentFile!!),
                        )

                        workQueue.addFirst(WorkItem.ResolveIssue(issue, item, existsError))
                        return
                    }
                } else {
                    // File-directory conflict
                    if (issueSkipAllPathExists) {
                        log(TAG, INFO) { "Skipping file-directory conflict (skip apply-to-all): $dest" }
                        skipped.add(sourceLookup.lookedUp)
                        skippedSourceDirs.add(sourceLookup.lookedUp)
                        itemsProcessed++
                        return
                    }

                    if (issueOverwriteAllPathExists) {
                        log(TAG, INFO) { "Overwriting file with directory (overwrite apply-to-all): $dest" }
                        Files.delete(dest.file.toPath())
                    } else {
                        val existsError = WriteException(path = dest, cause = FileAlreadyExistsException(dest.path))
                        if (onIssue == null) throw existsError

                        val issue = PathActionIssue.PathAlreadyExists(
                            source = sourceLookup,
                            destination = destLookup,
                            canSkip = true,
                            canOverwrite = true,
                            canRenameSource = true,
                            canRenameDestination = true,
                            suggestedName = generateUniqueName(dest.name, dest.file.parentFile!!),
                        )

                        workQueue.addFirst(WorkItem.ResolveIssue(issue, item, existsError))
                        return
                    }
                }
            }

            // Create directory or symlink
            if (sourceLookup.fileType == FileType.SYMBOLIC_LINK && !options.followSymlinks) {
                val sourcePath = sourceLookup.lookedUp.file.toPath()
                val linkTarget = Files.readSymbolicLink(sourcePath)
                val newTarget = if (linkTarget.isAbsolute) {
                    linkTarget
                } else {
                    val absoluteTarget = sourcePath.parent.resolve(linkTarget).normalize()
                    dest.file.toPath().parent.relativize(absoluteTarget)
                }
                if (Files.exists(dest.file.toPath())) {
                    Files.delete(dest.file.toPath())
                }
                Files.createSymbolicLink(dest.file.toPath(), newTarget)
            } else {
                Files.createDirectories(dest.file.toPath())
            }

            copied.add(sourceLookup.lookedUp to dest)
            itemsProcessed++

        } catch (e: SecurityException) {
            log(TAG, ERROR) { "Security exception on $dest: $e" }
            if (issueSkippAllPermission) {
                log(TAG, INFO) { "Skipping permission issue (apply-to-all): $dest" }
                skipped.add(sourceLookup.lookedUp)
                itemsProcessed++
                return
            }

            val writeError = WriteException(path = dest, cause = e)
            if (onIssue == null) throw writeError

            val destLookup = if (Files.exists(dest.file.toPath())) dest.performLookup() else sourceLookup
            val issue = PathActionIssue.InsufficientPermission(
                destination = destLookup,
                exception = writeError,
                canSkip = true,
            )

            workQueue.addFirst(WorkItem.ResolveIssue(issue, item, writeError))

        } catch (e: Exception) {
            log(TAG, ERROR) { "Failed to create directory $dest: $e" }
            if (issueSkippAllUnknown) {
                log(TAG, INFO) { "Skipping unknown issue (apply-to-all): $dest" }
                skipped.add(sourceLookup.lookedUp)
                itemsProcessed++
                return
            }

            val writeError = WriteException(path = dest, cause = e)
            if (onIssue == null) throw writeError

            val destLookup = if (Files.exists(dest.file.toPath())) dest.performLookup() else sourceLookup
            val issue = PathActionIssue.UnknownError(
                destination = destLookup,
                exception = writeError,
                canRetry = true,
                canSkip = true
            )

            workQueue.addFirst(WorkItem.ResolveIssue(issue, item, writeError))
        } finally {
            onProgress?.invoke(createProgress(sourceLookup, dest))
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
        val dest = adjustedDest

        log(TAG, VERBOSE) { "tryCopyFile(): $sourceLookup -> $dest" }

        try {
            onProgress?.invoke(createProgress(sourceLookup, dest))

            // Ensure parent directory exists
            val parentPath = dest.file.parentFile?.let { LocalPath.build(it) }
            if (parentPath != null && !Files.exists(parentPath.file.toPath())) {
                Files.createDirectories(parentPath.file.toPath())
            }

            // Check if destination already exists
            if (Files.exists(dest.file.toPath())) {
                if (issueSkipAllPathExists) {
                    log(TAG, INFO) { "Skipping existing file (skip apply-to-all): $dest" }
                    skipped.add(sourceLookup.lookedUp)
                    itemsProcessed++
                    return
                }

                if (!issueOverwriteAllPathExists) {
                    val existsError = WriteException(path = dest, cause = FileAlreadyExistsException(dest.path))
                    if (onIssue == null) throw existsError

                    val issue = PathActionIssue.PathAlreadyExists(
                        source = sourceLookup,
                        destination = dest.performLookup(),
                        canSkip = true,
                        canOverwrite = true,
                        canRenameSource = true,
                        canRenameDestination = true,
                        suggestedName = generateUniqueName(dest.name, dest.file.parentFile!!),
                    )

                    workQueue.addFirst(WorkItem.ResolveIssue(issue, item, existsError))
                    return
                } else {
                    log(TAG, INFO) { "Overwriting existing file (overwrite apply-to-all): $dest" }
                }
            }

            // Perform the copy
            val sourcePath = sourceLookup.lookedUp.file.toPath()

            if (sourceLookup.fileType == FileType.SYMBOLIC_LINK) {
                if (options.followSymlinks) {
                    val targetPath = Files.readSymbolicLink(sourcePath).let { target ->
                        sourcePath.parent.resolve(target).normalize()
                    }
                    Files.copy(
                        targetPath,
                        dest.file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES
                    )
                } else {
                    val linkTarget = Files.readSymbolicLink(sourcePath)
                    val newTarget = if (linkTarget.isAbsolute) {
                        linkTarget
                    } else {
                        val absoluteTarget = sourcePath.parent.resolve(linkTarget).normalize()
                        dest.file.toPath().parent.relativize(absoluteTarget)
                    }
                    if (Files.exists(dest.file.toPath())) {
                        Files.delete(dest.file.toPath())
                    }
                    Files.createSymbolicLink(dest.file.toPath(), newTarget)
                }
            } else {
                Files.copy(
                    sourcePath,
                    dest.file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES
                )
            }

            bytesCopied += sourceLookup.size
            copied.add(sourceLookup.lookedUp to dest)
            itemsProcessed++

        } catch (securityError: SecurityException) {
            log(TAG, ERROR) { "Security exception on $sourceLookup: $securityError" }
            if (issueSkippAllPermission) {
                log(TAG, INFO) { "Skipping permission issue (apply-to-all): $sourceLookup" }
                skipped.add(sourceLookup.lookedUp)
                itemsProcessed++
                return
            }

            val readError =
                ReadException(message = "Cannot read file", path = sourceLookup.lookedUp, cause = securityError)
            if (onIssue == null) throw readError

            val issue = PathActionIssue.InsufficientPermission(
                destination = sourceLookup,
                exception = readError,
                canSkip = true,
            )

            workQueue.addFirst(WorkItem.ResolveIssue(issue, item, readError))

        } catch (copyError: Exception) {
            log(TAG, ERROR) { "Failed to copy $sourceLookup to $dest: $copyError" }
            if (issueSkippAllUnknown) {
                log(TAG, INFO) { "Skipping unknown issue (apply-to-all): $sourceLookup" }
                skipped.add(sourceLookup.lookedUp)
                itemsProcessed++
                return
            }

            val writeError = WriteException(path = dest, cause = copyError)
            if (onIssue == null) throw writeError

            val destLookup = if (Files.exists(dest.file.toPath())) dest.performLookup() else sourceLookup
            val issue = PathActionIssue.UnknownError(
                destination = destLookup,
                exception = writeError,
                canRetry = true,
                canSkip = true
            )

            workQueue.addFirst(WorkItem.ResolveIssue(issue, item, writeError))
        } finally {
            onProgress?.invoke(createProgress(sourceLookup, dest))
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
                                if (Files.exists(dest.file.toPath())) {
                                    if (Files.isDirectory(dest.file.toPath())) {
                                        deleteRecursively(dest)
                                    } else {
                                        Files.delete(dest.file.toPath())
                                    }
                                }
                            }
                            is WorkItem.CopyFile -> {
                                val dest = orig.dest
                                if (Files.exists(dest.file.toPath())) {
                                    if (Files.isDirectory(dest.file.toPath())) {
                                        deleteRecursively(dest)
                                    } else {
                                        Files.delete(dest.file.toPath())
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
                                Files.createDirectories(newDestPath.file.toPath())
                                copied.add(orig.sourceLookup.lookedUp to newDestPath)
                                renamedSourceDirs[orig.sourceLookup.lookedUp] = newDestPath
                                itemsProcessed++
                            }
                            is WorkItem.CopyFile -> {
                                val newDestPath = LocalPath.build(File(orig.dest.file.parentFile!!, res.newName))
                                Files.copy(
                                    orig.sourceLookup.lookedUp.file.toPath(),
                                    newDestPath.file.toPath(),
                                    StandardCopyOption.COPY_ATTRIBUTES
                                )
                                bytesCopied += orig.sourceLookup.size
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
                                Files.move(dest.file.toPath(), newDestPath.file.toPath())
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
            if (!Files.exists(destination.file.toPath())) {
                Files.createDirectories(destination.file.toPath())
                log(TAG) { "Destination directory created: $destination" }
                return
            }

            if (Files.isDirectory(destination.file.toPath())) {
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
                    Files.delete(destination.file.toPath())
                }
                is PathActionIssue.PathAlreadyExists.Resolution.RenameDestination -> {
                    log(TAG, INFO) { "Renaming existing file: $destination -> ${resolution.newName}" }
                    val newDestPath = LocalPath.build(File(destination.file.parentFile!!, resolution.newName))
                    Files.move(destination.file.toPath(), newDestPath.file.toPath())
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

            Files.createDirectories(destination.file.toPath())
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
        bytesCopied = bytesCopied,
        primaryProgress = eu.darken.butler.common.progress.Progress.Data(
            primary = R.string.general_copy_progress_title.toCaString(),
            secondary = sourceLookup.userReadablePath,
            count = eu.darken.butler.common.progress.Progress.Count.Counter(
                current = itemsProcessed,
                max = totalItems
            )
        ),
        secondaryProgress = null
    )

    private fun deleteRecursively(path: LocalPath) {
        if (!Files.exists(path.file.toPath())) return

        Files.walk(path.file.toPath())
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