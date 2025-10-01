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

internal class LocalPathCopyTool(
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

    // Temporary state for current source being processed
    private var currentTopLevel: LocalPath? = null
    private var currentItemsProcessed = 0
    private var currentTotalItems = 0

    private data class SourceCopyData(
        val source: LocalPath,
        val dirs: List<Pair<LocalPathLookup, LocalPath>>,
        val files: List<Pair<LocalPathLookup, LocalPath>>,
        val skippedSourceDirs: Set<LocalPath>,
        val renamedSourceDirs: Map<LocalPath, LocalPath>,
        val totalSize: Long
    )

    suspend fun execute(): CopyAction.State.Result<LocalPath, LocalPathLookup> {
        ensureDestinationExists()

        val allSourceData = analyzeAllSources()
        val totalSizeNeeded = allSourceData.sumOf { it.totalSize }

        checkSpaceAvailable(totalSizeNeeded)
        copyAllSources(allSourceData)

        return CopyAction.State.Result(
            copied = copied,
            skipped = skipped,
            bytesCopied = bytesCopied,
        )
    }

    private suspend fun ensureDestinationExists() {
        try {
            // Check if destination exists
            if (Files.exists(destination.file.toPath())) {
                // Verify it's a directory, not a file
                if (!Files.isDirectory(destination.file.toPath())) {
                    val existsError = WriteException(
                        path = destination,
                        cause = FileAlreadyExistsException(destination.path)
                    )

                    // If no issue handler, throw immediately (backward compatibility)
                    if (onIssue == null) {
                        throw WriteException(
                            path = destination,
                            cause = IOException("Destination exists but is not a directory: ${destination.path}")
                        )
                    }

                    // Ask user what to do - a file exists where we need a directory
                    val destLookup = destination.performLookup()
                    val sourceLookup = sources.first().performLookup()

                    val issue = PathActionIssue.PathAlreadyExists(
                        source = sourceLookup,
                        destination = destLookup,
                        canSkip = false,
                        canOverwrite = true,
                        canRenameDestination = true,
                        canRenameSource = false,
                        canMerge = false,
                        suggestedName = generateUniqueName(destination.name, destination.file.parentFile!!),
                    )

                    when (val resolution = onIssue.invoke(issue) as PathActionIssue.PathAlreadyExists.Resolution) {
                        is PathActionIssue.PathAlreadyExists.Resolution.Cancel -> throw CancellationException(
                            "User cancelled",
                            existsError
                        )
                        is PathActionIssue.PathAlreadyExists.Resolution.Overwrite -> {
                            log(TAG, INFO) { "Overwriting file at destination: $destination" }
                            Files.delete(destination.file.toPath())
                            // File deleted, fall through to create directory
                        }
                        is PathActionIssue.PathAlreadyExists.Resolution.RenameDestination -> {
                            log(TAG, INFO) { "Renaming existing file: $destination -> ${resolution.newName}" }
                            val newDestPath = LocalPath.build(File(destination.file.parentFile!!, resolution.newName))
                            Files.move(destination.file.toPath(), newDestPath.file.toPath())
                            // File renamed, fall through to create directory
                        }
                        is PathActionIssue.PathAlreadyExists.Resolution.Skip,
                        is PathActionIssue.PathAlreadyExists.Resolution.RenameSource,
                        is PathActionIssue.PathAlreadyExists.Resolution.Merge -> {
                            throw CancellationException("Invalid resolution for destination conflict", existsError)
                        }
                    }
                    // After handling file conflict, create the directory
                    Files.createDirectories(destination.file.toPath())
                    return
                } else {
                    // Destination exists and is a directory - good!
                    return
                }
            }

            // Create destination directory
            Files.createDirectories(destination.file.toPath())

        } catch (e: SecurityException) {
            // Permission denied
            throw WriteException(
                path = destination,
                cause = IOException("Cannot create destination directory (permission denied): ${destination.path}", e)
            )
        } catch (e: WriteException) {
            // Re-throw our own exceptions
            throw e
        } catch (e: CancellationException) {
            // Re-throw cancellation
            throw e
        } catch (e: IOException) {
            // Other IO errors (disk full, invalid path, etc.)
            throw WriteException(
                path = destination,
                cause = IOException("Cannot create destination directory: ${destination.path}", e)
            )
        }
    }

    private fun analyzeAllSources(): List<SourceCopyData> {
        val allSourceData = mutableListOf<SourceCopyData>()

        sources.forEachIndexed { index, currentTopLevel ->
            val sourceData = analyzeSingleSource(currentTopLevel, index, sources.size)
            allSourceData.add(sourceData)
        }

        return allSourceData
    }

    private fun analyzeSingleSource(source: LocalPath, index: Int, total: Int): SourceCopyData {
        log(TAG, VERBOSE) { "Analyzing target ${index + 1}/$total: $source" }

        var sourceSize = 0L
        val toVisit = ArrayDeque<LocalPath>().apply { add(source) }
        val dirs = ArrayDeque<Pair<LocalPathLookup, LocalPath>>()
        val files = ArrayDeque<Pair<LocalPathLookup, LocalPath>>()
        val skippedSourceDirs = mutableSetOf<LocalPath>()
        val renamedSourceDirs = mutableMapOf<LocalPath, LocalPath>()

        // Build lists of directories and files to copy
        while (toVisit.isNotEmpty()) {
            val localPath = toVisit.removeFirst()
            val lookup = localPath.performLookup()

            val relativePath = if (localPath == source) {
                source.name
            } else {
                val segments = source.crumbsTo(localPath)
                source.name + "/" + segments.joinToString("/")
            }

            // Ensure relativePath doesn't start with separator (would make it absolute)
            val cleanRelativePath = relativePath.trimStart('/')
            val destinationPath = LocalPath.build(File(destination.file, cleanRelativePath))

            when (lookup.fileType) {
                FileType.SYMBOLIC_LINK -> {
                    if (options.followSymlinks) {
                        // Follow the symlink and treat as whatever it points to
                        try {
                            val targetPath = Files.readSymbolicLink(localPath.file.toPath())
                            val resolvedPath = localPath.file.toPath().parent.resolve(targetPath).normalize()
                            if (Files.isDirectory(resolvedPath)) {
                                // It's a directory symlink - treat as directory
                                dirs.addLast(lookup to destinationPath)
                                // Traverse the resolved directory, but add children as if they're under the symlink
                                Files.newDirectoryStream(resolvedPath).use { ds ->
                                    for (child in ds) {
                                        // Create path relative to symlink location, not resolved location
                                        val childName = child.fileName.toString()
                                        val symlinkChild = LocalPath.build(File(localPath.file, childName))
                                        toVisit.addLast(symlinkChild)
                                    }
                                }
                            } else {
                                // It's a file symlink - treat as file
                                files.addLast(lookup to destinationPath)
                                sourceSize += lookup.size
                            }
                        } catch (e: IOException) {
                            log(TAG, WARN) { "Cannot resolve symlink: $lookup - ${e.message}" }
                            // If we can't resolve it, copy the link as-is
                            files.addLast(lookup to destinationPath)
                            sourceSize += lookup.size
                        }
                    } else {
                        // Don't follow symlink - copy it as-is (treat as file)
                        files.addLast(lookup to destinationPath)
                        sourceSize += lookup.size
                    }
                    continue
                }
                FileType.FILE -> {
                    files.addLast(lookup to destinationPath)
                    sourceSize += lookup.size
                    continue
                }
                FileType.DIRECTORY -> {
                    dirs.addLast(lookup to destinationPath)
                }
                FileType.UNKNOWN -> throw IllegalStateException("Unknown file type: $lookup")
            }

            try {
                val p = localPath.file.toPath()
                Files.newDirectoryStream(p).use { ds ->
                    for (child in ds) toVisit.addLast(LocalPath.build(child.toFile()))
                }
            } catch (e: IOException) {
                log(TAG, WARN) { "Cannot list directory: $lookup - ${e.message}" }
            }
        }

        return SourceCopyData(
            source = source,
            dirs = dirs.toList(),
            files = files.toList(),
            skippedSourceDirs = skippedSourceDirs,
            renamedSourceDirs = renamedSourceDirs,
            totalSize = sourceSize
        )
    }

    private suspend fun checkSpaceAvailable(totalSizeNeeded: Long) {
        while (true) {
            val availableSpace = Files.getFileStore(destination.file.toPath()).usableSpace
            log(TAG, DEBUG) { "Space check: need $totalSizeNeeded bytes, available $availableSpace bytes" }

            if (totalSizeNeeded > availableSpace) {
                log(TAG, WARN) { "Insufficient space: need $totalSizeNeeded, have $availableSpace" }
                val spaceError = WriteException(
                    path = destination,
                    cause = IOException("Insufficient space: need $totalSizeNeeded bytes, available $availableSpace bytes")
                )
                if (onIssue != null) {
                    // Create a temporary lookup for the source (collection)
                    val sourceLookup = if (sources.size == 1) {
                        sources.first().performLookup()
                    } else {
                        destination.performLookup() // Use destination as placeholder for multiple sources
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
                // Space is sufficient, exit loop
                break
            }
        }
    }

    private suspend fun copyAllSources(allSourceData: List<SourceCopyData>) {
        allSourceData.forEachIndexed { index, sourceData ->
            copySingleSource(sourceData, index, allSourceData.size)
        }
    }

    private suspend fun copySingleSource(
        sourceData: SourceCopyData,
        index: Int,
        total: Int
    ) {
        log(TAG, VERBOSE) { "Copying target ${index + 1}/$total: ${sourceData.source}" }

        // Set current source state
        currentTopLevel = sourceData.source
        currentTotalItems = sourceData.files.size + sourceData.dirs.size
        currentItemsProcessed = 0

        // Create mutable tracking collections that will be updated during copying
        val skippedSourceDirs = sourceData.skippedSourceDirs.toMutableSet()
        val renamedSourceDirs = sourceData.renamedSourceDirs.toMutableMap()

        // Helper to adjust destination path if parent was renamed
        fun adjustDestinationForRenames(
            dest: LocalPath,
            source: LocalPath,
            renamedSourceDirs: Map<LocalPath, LocalPath>
        ): LocalPath {
            return renamedSourceDirs.entries.find { (renamedSource, _) ->
                renamedSource.isAncestorOf(source)
            }?.let { (renamedSource, newDestDir) ->
                val relativeSegments = renamedSource.crumbsTo(source)
                val relativePath = relativeSegments.joinToString("/")
                LocalPath.build(File(newDestDir.file, relativePath))
            } ?: dest
        }

        // Helper to check if file is descendant of skipped dir
        fun isDescendantOfSkippedDir(source: LocalPath, skippedSourceDirs: Set<LocalPath>): Boolean {
            return skippedSourceDirs.any { skippedDir ->
                skippedDir.isAncestorOf(source)
            }
        }

        // Copy directories first
        log(TAG, VERBOSE) { "Creating ${sourceData.dirs.size} directories for target: $currentTopLevel" }
        for ((sourceLookup, dest) in sourceData.dirs) {
            val adjustedDest = adjustDestinationForRenames(dest, sourceLookup.lookedUp, renamedSourceDirs)
            tryCreateDirectory(
                sourceLookup,
                adjustedDest,
                skippedSourceDirs,
                renamedSourceDirs,
                index,
                total
            )
        }

        // Copy files
        log(TAG, VERBOSE) { "Copying ${sourceData.files.size} files for target: $currentTopLevel" }
        for ((sourceLookup, dest) in sourceData.files) {
            if (isDescendantOfSkippedDir(sourceLookup.lookedUp, skippedSourceDirs)) {
                log(TAG, VERBOSE) { "Skipping file because parent directory was skipped: $sourceLookup" }
                skipped.add(sourceLookup.lookedUp)
                currentItemsProcessed++
                continue
            }

            val adjustedDest = adjustDestinationForRenames(dest, sourceLookup.lookedUp, renamedSourceDirs)
            tryCopyFile(
                sourceLookup,
                adjustedDest,
                index,
                total
            )
        }
    }

    private fun createProgress(
        sourceLookup: LocalPathLookup,
        dest: LocalPath,
        index: Int,
        total: Int
    ): CopyAction.State.Progress<LocalPath, LocalPathLookup> = CopyAction.State.Progress(
        currentSource = sourceLookup.lookedUp,
        currentDestination = dest,
        bytesCopied = bytesCopied,
        primaryProgress = eu.darken.butler.common.progress.Progress.Data(
            primary = R.string.general_copy_progress_title.toCaString(currentTopLevel!!.name),
            secondary = if (sourceLookup.lookedUp == currentTopLevel) {
                R.string.general_copy_progress_processing_main.toCaString()
            } else {
                R.string.general_copy_progress_processing_content.toCaString()
            },
            count = eu.darken.butler.common.progress.Progress.Count.Counter(
                current = index,
                max = total
            )
        ),
        secondaryProgress = if (currentTotalItems > 1) {
            eu.darken.butler.common.progress.Progress.Data(
                primary = R.string.general_copy_progress_items_in_folder.toCaString(
                    currentTopLevel!!.name
                ),
                secondary = sourceLookup.userReadablePath,
                count = eu.darken.butler.common.progress.Progress.Count.Percent(
                    current = currentItemsProcessed,
                    max = currentTotalItems
                )
            )
        } else null
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

    private suspend fun tryCreateDirectory(
        sourceLookup: LocalPathLookup,
        dest: LocalPath,
        skippedSourceDirs: MutableSet<LocalPath>,
        renamedSourceDirs: MutableMap<LocalPath, LocalPath>,
        index: Int,
        total: Int
    ) {
        log(TAG, VERBOSE) { "tryCreateDirectory(): $sourceLookup -> $dest" }
        while (currentCoroutineContext().isActive) {
            try {
                onProgress?.invoke(createProgress(sourceLookup, dest, index, total))

                // Check if destination already exists
                if (Files.exists(dest.file.toPath())) {
                    val destLookup = dest.performLookup()

                    // If it's already a directory, handle directory-directory conflict
                    if (destLookup.fileType == FileType.DIRECTORY) {
                        // Check "apply to all" flags first
                        if (issueSkipAllPathExists) {
                            log(TAG, INFO) { "Skipping directory merge (skip apply-to-all): $dest" }
                            skipped.add(sourceLookup.lookedUp)
                            skippedSourceDirs.add(sourceLookup.lookedUp)
                            currentItemsProcessed++
                            break
                        }

                        if (issueMergeAllPathExists) {
                            log(TAG, INFO) { "Merging directory (merge apply-to-all): $dest" }
                            // Directory exists, just continue (no action needed for merge)
                            currentItemsProcessed++
                            break
                        }

                        if (issueOverwriteAllPathExists) {
                            log(TAG, INFO) { "Overwriting directory (overwrite apply-to-all): $dest" }
                            deleteRecursively(dest)
                            // Continue to creation below
                        } else {
                            // No "apply to all" - ask user
                            val existsError = WriteException(
                                path = dest,
                                cause = FileAlreadyExistsException(dest.path)
                            )
                            if (onIssue == null) {
                                // Default: auto-merge (preserve backward compatibility when no handler)
                                log(TAG, VERBOSE) { "Directory already exists, auto-merging (no issue handler): $dest" }
                                currentItemsProcessed++
                                break
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

                            when (val resolution = onIssue.invoke(issue) as PathActionIssue.PathAlreadyExists.Resolution) {
                                is PathActionIssue.PathAlreadyExists.Resolution.Cancel -> throw CancellationException(
                                    "User cancelled",
                                    existsError
                                )
                                is PathActionIssue.PathAlreadyExists.Resolution.Merge -> {
                                    if (resolution.applyToAll) issueMergeAllPathExists = true
                                    log(TAG, VERBOSE) { "Merging directory: $dest" }
                                    // Directory exists, just continue
                                    currentItemsProcessed++
                                    break
                                }
                                is PathActionIssue.PathAlreadyExists.Resolution.Overwrite -> {
                                    if (resolution.applyToAll) issueOverwriteAllPathExists = true
                                    log(TAG, INFO) { "Overwriting directory: $dest" }
                                    deleteRecursively(dest)
                                    // Continue to creation below
                                }
                                is PathActionIssue.PathAlreadyExists.Resolution.Skip -> {
                                    if (resolution.applyToAll) issueSkipAllPathExists = true
                                    skipped.add(sourceLookup.lookedUp)
                                    skippedSourceDirs.add(sourceLookup.lookedUp)
                                    currentItemsProcessed++
                                    break
                                }
                                is PathActionIssue.PathAlreadyExists.Resolution.RenameDestination -> {
                                    log(TAG, INFO) { "Renaming existing directory: $dest -> ${resolution.newName}" }
                                    val newDestPath = LocalPath.build(File(dest.file.parentFile!!, resolution.newName))
                                    Files.move(dest.file.toPath(), newDestPath.file.toPath())
                                    // Continue to create directory with original name
                                }
                                is PathActionIssue.PathAlreadyExists.Resolution.RenameSource -> {
                                    log(TAG, INFO) { "Creating directory with new name: $dest -> ${resolution.newName}" }
                                    val newDestPath = LocalPath.build(File(dest.file.parentFile!!, resolution.newName))
                                    Files.createDirectories(newDestPath.file.toPath())
                                    copied.add(sourceLookup.lookedUp to newDestPath)
                                    renamedSourceDirs[sourceLookup.lookedUp] = newDestPath
                                    currentItemsProcessed++
                                    break
                                }
                            }
                        }
                    } else {
                        // It's a file blocking our directory creation
                        if (issueSkipAllPathExists) {
                            log(TAG, INFO) { "Skipping file-directory conflict (skip apply-to-all): $dest" }
                            skipped.add(sourceLookup.lookedUp)
                            skippedSourceDirs.add(sourceLookup.lookedUp)
                            currentItemsProcessed++
                            break
                        }

                        if (issueOverwriteAllPathExists) {
                            log(TAG, INFO) { "Overwriting file with directory (overwrite apply-to-all): $dest" }
                            Files.delete(dest.file.toPath())
                            // Continue to creation below
                        } else {
                            val existsError = WriteException(
                                path = dest,
                                cause = FileAlreadyExistsException(dest.path)
                            )
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

                            when (val resolution = onIssue.invoke(issue) as PathActionIssue.PathAlreadyExists.Resolution) {
                                is PathActionIssue.PathAlreadyExists.Resolution.Cancel -> throw CancellationException(
                                    "User cancelled",
                                    existsError
                                )
                                is PathActionIssue.PathAlreadyExists.Resolution.Overwrite -> {
                                    if (resolution.applyToAll) issueOverwriteAllPathExists = true
                                    Files.delete(dest.file.toPath())
                                    // Continue to creation below
                                }
                                is PathActionIssue.PathAlreadyExists.Resolution.Skip -> {
                                    if (resolution.applyToAll) issueSkipAllPathExists = true
                                    skipped.add(sourceLookup.lookedUp)
                                    skippedSourceDirs.add(sourceLookup.lookedUp)
                                    currentItemsProcessed++
                                    break
                                }
                                is PathActionIssue.PathAlreadyExists.Resolution.Merge -> {
                                    // Can't merge file with directory
                                    throw CancellationException("Cannot merge file with directory", existsError)
                                }
                                is PathActionIssue.PathAlreadyExists.Resolution.RenameDestination -> {
                                    log(TAG, INFO) { "Renaming existing file: $dest -> ${resolution.newName}" }
                                    val newDestPath = LocalPath.build(File(dest.file.parentFile!!, resolution.newName))
                                    Files.move(dest.file.toPath(), newDestPath.file.toPath())
                                    // Continue to create directory with original name
                                }
                                is PathActionIssue.PathAlreadyExists.Resolution.RenameSource -> {
                                    log(TAG, INFO) { "Creating directory with new name: $dest -> ${resolution.newName}" }
                                    val newDestPath = LocalPath.build(File(dest.file.parentFile!!, resolution.newName))
                                    Files.createDirectories(newDestPath.file.toPath())
                                    copied.add(sourceLookup.lookedUp to newDestPath)
                                    renamedSourceDirs[sourceLookup.lookedUp] = newDestPath
                                    currentItemsProcessed++
                                    break
                                }
                            }
                        }
                    }
                }

                // Create directory or symlink depending on source type and options
                if (sourceLookup.fileType == FileType.SYMBOLIC_LINK && !options.followSymlinks) {
                    // Copy directory symlink as symlink (not the directory it points to)
                    val sourcePath = sourceLookup.lookedUp.file.toPath()
                    val linkTarget = Files.readSymbolicLink(sourcePath)

                    // Preserve whether target is absolute or relative
                    val newTarget = if (linkTarget.isAbsolute) {
                        // Keep absolute paths as absolute
                        linkTarget
                    } else {
                        // For relative paths, adjust relative to destination location
                        val absoluteTarget = sourcePath.parent.resolve(linkTarget).normalize()
                        dest.file.toPath().parent.relativize(absoluteTarget)
                    }

                    // Delete destination if it exists (createSymbolicLink doesn't support REPLACE_EXISTING)
                    if (Files.exists(dest.file.toPath())) {
                        Files.delete(dest.file.toPath())
                    }
                    Files.createSymbolicLink(dest.file.toPath(), newTarget)
                } else {
                    // Create actual directory
                    Files.createDirectories(dest.file.toPath())
                }

                copied.add(sourceLookup.lookedUp to dest)
                currentItemsProcessed++
                break
            } catch (e: SecurityException) {
                log(TAG, ERROR) { "copy(): Security exception on $dest: $e" }

                if (issueSkippAllPermission) {
                    log(TAG, INFO) { "Skipping permission issue (apply-to-all): $dest" }
                    skipped.add(sourceLookup.lookedUp)
                    currentItemsProcessed++
                    break
                }

                val writeError = WriteException(path = dest, cause = e)
                if (onIssue == null) throw writeError

                // Use source lookup if dest doesn't exist yet
                val destLookup = if (Files.exists(dest.file.toPath())) dest.performLookup() else sourceLookup

                val issue = PathActionIssue.InsufficientPermission(
                    destination = destLookup,
                    exception = writeError,
                    canSkip = true,
                )

                when (val resolution = onIssue.invoke(issue) as PathActionIssue.InsufficientPermission.Resolution) {
                    is PathActionIssue.InsufficientPermission.Resolution.Cancel -> throw CancellationException(
                        "User cancelled",
                        writeError
                    )
                    is PathActionIssue.InsufficientPermission.Resolution.Skip -> {
                        if (resolution.applyToAll) issueSkippAllPermission = true
                        skipped.add(sourceLookup.lookedUp)
                        currentItemsProcessed++
                        break
                    }
                }
            } catch (e: Exception) {
                log(TAG, ERROR) { "copy(): Failed to create directory $dest: $e" }

                if (issueSkippAllUnknown) {
                    log(TAG, INFO) { "Skipping unknown issue (apply-to-all): $dest" }
                    skipped.add(sourceLookup.lookedUp)
                    currentItemsProcessed++
                    break
                }

                val writeError = WriteException(path = dest, cause = e)
                if (onIssue == null) throw writeError

                // Use source lookup if dest doesn't exist yet
                val destLookup = if (Files.exists(dest.file.toPath())) dest.performLookup() else sourceLookup

                val issue = PathActionIssue.UnknownError(
                    destination = destLookup,
                    exception = writeError,
                    canRetry = true,
                    canSkip = true
                )

                when (val resolution = onIssue.invoke(issue) as PathActionIssue.UnknownError.Resolution) {
                    is PathActionIssue.UnknownError.Resolution.Cancel -> throw CancellationException(
                        "User cancelled",
                        writeError
                    )
                    is PathActionIssue.UnknownError.Resolution.Retry -> continue
                    is PathActionIssue.UnknownError.Resolution.Skip -> {
                        if (resolution.applyToAll) issueSkippAllUnknown = true
                        skipped.add(sourceLookup.lookedUp)
                        currentItemsProcessed++
                        break
                    }
                }
            } finally {
                onProgress?.invoke(createProgress(sourceLookup, dest, index, total))
            }
        }
    }

    private suspend fun tryCopyFile(
        sourceLookup: LocalPathLookup,
        dest: LocalPath,
        index: Int,
        total: Int
    ) {
        log(TAG, VERBOSE) { "tryCopyFile(): $sourceLookup -> $dest" }
        while (currentCoroutineContext().isActive) {
            try {
                onProgress?.invoke(createProgress(sourceLookup, dest, index, total))

                // Ensure parent directory exists
                val parentPath = dest.file.parentFile?.let { LocalPath.build(it) }
                if (parentPath != null && !Files.exists(parentPath.file.toPath())) {
                    Files.createDirectories(parentPath.file.toPath())
                }

                // Check if destination already exists
                if (Files.exists(dest.file.toPath())) {
                    // Handle "apply to all" for previous choices
                    if (issueSkipAllPathExists) {
                        log(TAG, INFO) { "Skipping existing file (skip apply-to-all): $dest" }
                        skipped.add(sourceLookup.lookedUp)
                        currentItemsProcessed++
                        break
                    }

                    if (issueOverwriteAllPathExists) {
                        log(TAG, INFO) { "Overwriting existing file (overwrite apply-to-all): $dest" }
                        // Continue to copy with overwrite
                    } else {
                        // Ask user what to do
                        val existsError = WriteException(
                            path = dest,
                            cause = FileAlreadyExistsException(dest.path)
                        )
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

                        when (val resolution = onIssue.invoke(issue) as PathActionIssue.PathAlreadyExists.Resolution) {
                            is PathActionIssue.PathAlreadyExists.Resolution.Cancel -> throw CancellationException(
                                "User cancelled",
                                existsError
                            )
                            is PathActionIssue.PathAlreadyExists.Resolution.Overwrite -> {
                                if (resolution.applyToAll) issueOverwriteAllPathExists = true
                                // Continue to copy with overwrite
                            }
                            is PathActionIssue.PathAlreadyExists.Resolution.Skip -> {
                                if (resolution.applyToAll) issueSkipAllPathExists = true
                                skipped.add(sourceLookup.lookedUp)
                                currentItemsProcessed++
                                break
                            }
                            is PathActionIssue.PathAlreadyExists.Resolution.Merge -> {
                                // Merge doesn't need "apply to all" for files, only directories
                                // Continue to copy
                            }
                            is PathActionIssue.PathAlreadyExists.Resolution.RenameDestination -> {
                                log(TAG, INFO) { "Renaming existing destination: $dest -> ${resolution.newName}" }
                                val newDestPath = LocalPath.build(File(dest.file.parentFile!!, resolution.newName))
                                Files.move(dest.file.toPath(), newDestPath.file.toPath())
                                // Continue to copy source to original destination name
                            }
                            is PathActionIssue.PathAlreadyExists.Resolution.RenameSource -> {
                                log(TAG, INFO) { "Copying source with new name: $dest -> ${resolution.newName}" }
                                val newDestPath = LocalPath.build(File(dest.file.parentFile!!, resolution.newName))
                                Files.copy(
                                    sourceLookup.lookedUp.file.toPath(),
                                    newDestPath.file.toPath(),
                                    StandardCopyOption.COPY_ATTRIBUTES
                                )
                                bytesCopied += sourceLookup.size
                                copied.add(sourceLookup.lookedUp to newDestPath)
                                currentItemsProcessed++
                                break
                            }
                        }
                    }
                }

                // Perform the copy
                val sourcePath = sourceLookup.lookedUp.file.toPath()

                if (sourceLookup.fileType == FileType.SYMBOLIC_LINK) {
                    if (options.followSymlinks) {
                        // Follow the symlink and copy the target content
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
                        // Copy the symlink itself (not the target)
                        val linkTarget = Files.readSymbolicLink(sourcePath)

                        // Preserve whether target is absolute or relative
                        val newTarget = if (linkTarget.isAbsolute) {
                            // Keep absolute paths as absolute
                            linkTarget
                        } else {
                            // For relative paths, adjust relative to destination location
                            val absoluteTarget = sourcePath.parent.resolve(linkTarget).normalize()
                            dest.file.toPath().parent.relativize(absoluteTarget)
                        }

                        // Delete destination if it exists (createSymbolicLink doesn't support REPLACE_EXISTING)
                        if (Files.exists(dest.file.toPath())) {
                            Files.delete(dest.file.toPath())
                        }
                        Files.createSymbolicLink(dest.file.toPath(), newTarget)
                    }
                } else {
                    // Regular file copy
                    Files.copy(
                        sourcePath,
                        dest.file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES
                    )
                }

                bytesCopied += sourceLookup.size
                copied.add(sourceLookup.lookedUp to dest)
                currentItemsProcessed++
                break
            } catch (securityError: SecurityException) {
                log(TAG, ERROR) { "copy(): Security exception on $sourceLookup: $securityError" }

                if (issueSkippAllPermission) {
                    log(TAG, INFO) { "Skipping permission issue (apply-to-all): $sourceLookup" }
                    skipped.add(sourceLookup.lookedUp)
                    currentItemsProcessed++
                    break
                }

                val readError = ReadException(
                    message = "Cannot read file",
                    path = sourceLookup.lookedUp,
                    cause = securityError
                )
                if (onIssue == null) throw readError

                val issue = PathActionIssue.InsufficientPermission(
                    destination = sourceLookup,
                    exception = readError,
                    canSkip = true,
                )

                when (val resolution = onIssue.invoke(issue) as PathActionIssue.InsufficientPermission.Resolution) {
                    is PathActionIssue.InsufficientPermission.Resolution.Cancel -> throw CancellationException(
                        "User cancelled",
                        readError
                    )
                    is PathActionIssue.InsufficientPermission.Resolution.Skip -> {
                        if (resolution.applyToAll) issueSkippAllPermission = true
                        skipped.add(sourceLookup.lookedUp)
                        currentItemsProcessed++
                        break
                    }
                }
            } catch (copyError: Exception) {
                log(TAG, ERROR) { "copy(): Failed to copy $sourceLookup to $dest: $copyError" }

                if (issueSkippAllUnknown) {
                    log(TAG, INFO) { "Skipping unknown issue (apply-to-all): $sourceLookup" }
                    skipped.add(sourceLookup.lookedUp)
                    currentItemsProcessed++
                    break
                }

                val writeError = WriteException(path = dest, cause = copyError)
                if (onIssue == null) throw writeError

                // Use source lookup if dest doesn't exist yet
                val destLookup = if (Files.exists(dest.file.toPath())) dest.performLookup() else sourceLookup

                val issue = PathActionIssue.UnknownError(
                    destination = destLookup,
                    exception = writeError,
                    canRetry = true,
                    canSkip = true
                )

                when (val resolution = onIssue.invoke(issue) as PathActionIssue.UnknownError.Resolution) {
                    is PathActionIssue.UnknownError.Resolution.Cancel -> throw CancellationException(
                        "User cancelled",
                        writeError
                    )
                    is PathActionIssue.UnknownError.Resolution.Retry -> continue
                    is PathActionIssue.UnknownError.Resolution.Skip -> {
                        if (resolution.applyToAll) issueSkippAllUnknown = true
                        skipped.add(sourceLookup.lookedUp)
                        currentItemsProcessed++
                        break
                    }
                }
            } finally {
                onProgress?.invoke(createProgress(sourceLookup, dest, index, total))
            }
        }
    }

    companion object {
        private val TAG = logTag("Gateway", "Local", "Copy", "Tool")
    }
}
