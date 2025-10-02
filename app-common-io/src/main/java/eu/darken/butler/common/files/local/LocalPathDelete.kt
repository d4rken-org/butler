package eu.darken.butler.common.files.local

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.io.R
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import kotlin.coroutines.cancellation.CancellationException

internal class LocalPathDelete(
    private val targets: Collection<LocalPath>,
    private val recursive: Boolean,
    private val ignoreMissing: Boolean,
    private val onProgress: (suspend (DeleteAction.State.Progress<LocalPath, LocalPathLookup>) -> Unit)?,
    private val onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?
) {
    private val deleted = linkedSetOf<LocalPathLookup>()
    private val skipped = linkedSetOf<LocalPathLookup>()

    private var issueSkippAllPermission = false
    private var issueSkippAllUnknown = false

    // Global progress tracking
    private var totalItems = 0
    private var itemsProcessed = 0

    // Separate lists for files and directories to ensure correct deletion order
    private val filesToDelete = ArrayDeque<LocalPathLookup>()
    private val dirsToDeletePostOrder = ArrayDeque<LocalPathLookup>()

    // Work queue for processing scan and issue operations
    private var workQueue = ArrayDeque<WorkItem>()

    // Single-use flag
    private var hasExecuted = false

    /**
     * Sealed hierarchy of work items for the delete queue
     */
    private sealed class WorkItem {
        /**
         * Scan a path and add files/dirs to deletion lists
         * @param path The path to scan
         * @param topLevelTarget The root target being deleted
         * @param targetIndex Index of this target in the targets collection
         * @param totalTargets Total number of targets being deleted
         */
        data class ScanPath(
            val path: LocalPath,
            val topLevelTarget: LocalPath,
            val targetIndex: Int,
            val totalTargets: Int
        ) : WorkItem()

        /**
         * Resolve an issue that occurred during processing
         */
        data class ResolveIssue(
            val issue: PathActionIssue,
            val lookup: LocalPathLookup,
            val originalItem: WorkItem,
            val exception: Exception
        ) : WorkItem()
    }

    /**
     * Context for error handling operations
     */
    private sealed class ErrorContext {
        abstract val lookup: LocalPathLookup
        abstract val operation: String

        data class Scan(
            override val lookup: LocalPathLookup,
            override val operation: String,
        ) : ErrorContext()

        data class Delete(
            override val lookup: LocalPathLookup,
            override val operation: String,
        ) : ErrorContext()
    }

    suspend fun execute(): DeleteAction.State.Result<LocalPath, LocalPathLookup> {
        check(!hasExecuted) { "LocalPathDelete can only be executed once" }
        hasExecuted = true

        log(TAG, DEBUG) {
            "execute(): Deleting ${targets.size} targets (recursive=$recursive, ignoreMissing=$ignoreMissing)"
        }

        // Scan all targets first
        targets.forEachIndexed { targetIndex, currentTopLevel ->
            log(TAG, VERBOSE) { "Scanning target ${targetIndex + 1}/${targets.size}: $currentTopLevel" }

            // Initialize work queue with scan for this target
            workQueue.addLast(
                WorkItem.ScanPath(
                    path = currentTopLevel,
                    topLevelTarget = currentTopLevel,
                    targetIndex = targetIndex,
                    totalTargets = targets.size
                )
            )

            // Process scans and issues for this target
            while (workQueue.isNotEmpty() && currentCoroutineContext().isActive) {
                when (val item = workQueue.removeFirst()) {
                    is WorkItem.ScanPath -> processScan(item)
                    is WorkItem.ResolveIssue -> processResolveIssue(item)
                }
            }
        }

        // Calculate total items to delete
        totalItems = filesToDelete.size + dirsToDeletePostOrder.size
        log(TAG, DEBUG) { "Total items to delete: $totalItems (${filesToDelete.size} files, ${dirsToDeletePostOrder.size} dirs)" }

        // Now delete files first, then directories (post-order)
        log(TAG, VERBOSE) { "Deleting ${filesToDelete.size} files" }
        for (lookup in filesToDelete) {
            tryDelete(lookup)
        }

        log(TAG, VERBOSE) { "Deleting ${dirsToDeletePostOrder.size} directories" }
        for (dir in dirsToDeletePostOrder) {
            tryDelete(dir)
        }

        return DeleteAction.State.Result(
            deleted = deleted,
            skipped = skipped,
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
        log(TAG, ERROR) { "${context.operation} - Permission denied: ${context.lookup.lookedUp} - $error" }

        if (issueSkippAllPermission) {
            log(TAG, INFO) { "Skipping permission issue (apply-to-all): ${context.lookup.lookedUp}" }
            skipped.add(context.lookup)
            itemsProcessed++
            return
        }

        val exception = WriteException(path = context.lookup.lookedUp, cause = error)
        if (onIssue == null) throw exception

        val issue = PathActionIssue.InsufficientPermission(
            destination = context.lookup,
            exception = exception,
            canSkip = true,
        )

        workQueue.addFirst(WorkItem.ResolveIssue(issue, context.lookup, workItem, exception))
    }

    /**
     * Handles unknown I/O errors
     */
    private fun handleUnknownError(
        error: Exception,
        context: ErrorContext,
        workItem: WorkItem,
    ) {
        log(TAG, ERROR) { "${context.operation} failed: ${context.lookup.lookedUp} - $error" }

        // Handle NoSuchFileException specially
        if (error is NoSuchFileException) {
            log(TAG, WARN) { "File doesn't exist: ${context.lookup.lookedUp}" }
            if (ignoreMissing) {
                itemsProcessed++
                return
            }
        }

        // DirectoryNotEmptyException without issue handler should throw the original exception
        if (error is DirectoryNotEmptyException && onIssue == null) {
            log(TAG, WARN) { "Directory not empty: ${context.lookup.lookedUp}" }
            throw error
        }

        if (issueSkippAllUnknown) {
            log(TAG, INFO) { "Skipping unknown issue (apply-to-all): ${context.lookup.lookedUp}" }
            skipped.add(context.lookup)
            itemsProcessed++
            return
        }

        val exception = WriteException(path = context.lookup.lookedUp, cause = error)
        if (onIssue == null) throw exception

        val issue = PathActionIssue.UnknownError(
            destination = context.lookup,
            exception = exception,
            canRetry = true,
            canSkip = true
        )

        workQueue.addFirst(WorkItem.ResolveIssue(issue, context.lookup, workItem, exception))
    }

    private fun processScan(item: WorkItem.ScanPath) {
        log(TAG, VERBOSE) { "Scanning path: ${item.path}" }

        // Check file existence first when ignoreMissing is enabled
        if (ignoreMissing && !Files.exists(item.path.toNioPath(), LinkOption.NOFOLLOW_LINKS)) {
            log(TAG, VERBOSE) { "Skipping missing file (ignoreMissing=true): ${item.path}" }
            return
        }

        val lookup = try {
            item.path.performLookup()
        } catch (e: NoSuchFileException) {
            if (ignoreMissing) {
                log(TAG, VERBOSE) { "Skipping missing file (ignoreMissing=true): ${item.path}" }
                return
            }
            throw ReadException("File does not exist", item.path, e)
        }

        when (lookup.fileType) {
            FileType.SYMBOLIC_LINK, FileType.FILE -> {
                // Add file to deletion list
                filesToDelete.addLast(lookup)
            }

            FileType.DIRECTORY -> {
                if (!recursive) {
                    // Non-recursive: treat directory as a file (will fail if not empty)
                    filesToDelete.addLast(lookup)
                } else {
                    // Recursive: scan children first, then add directory
                    // List and queue children
                    try {
                        Files.newDirectoryStream(item.path.toNioPath()).use { ds ->
                            for (child in ds) {
                                val childPath = LocalPath.build(child.toFile())
                                // Add child scan to front (processed before parent)
                                workQueue.addFirst(
                                    WorkItem.ScanPath(
                                        path = childPath,
                                        topLevelTarget = item.topLevelTarget,
                                        targetIndex = item.targetIndex,
                                        totalTargets = item.totalTargets
                                    )
                                )
                            }
                        }
                    } catch (_: NoSuchFileException) {
                        // Directory disappeared between lookup and listing
                        log(TAG, WARN) { "Directory disappeared during scan: ${item.path}" }
                    } catch (e: AccessDeniedException) {
                        handlePermissionError(
                            error = e,
                            context = ErrorContext.Scan(lookup, "List directory contents"),
                            workItem = item
                        )
                    } catch (e: SecurityException) {
                        handlePermissionError(
                            error = e,
                            context = ErrorContext.Scan(lookup, "List directory contents"),
                            workItem = item
                        )
                    } catch (e: Exception) {
                        handleUnknownError(
                            error = e,
                            context = ErrorContext.Scan(lookup, "List directory contents"),
                            workItem = item
                        )
                    }

                    // Add directory to post-order list (added to front for post-order traversal)
                    dirsToDeletePostOrder.addFirst(lookup)
                }
            }

            FileType.UNKNOWN -> throw IllegalStateException("Unknown file type: $lookup")
        }
    }

    private suspend fun tryDelete(lookup: LocalPathLookup) {
        log(TAG, VERBOSE) { "tryDelete(): $lookup" }

        while (currentCoroutineContext().isActive) {
            val progress = createProgress(lookup)

            try {
                onProgress?.invoke(progress)

                Files.delete(lookup.lookedUp.toNioPath())
                deleted += lookup
                itemsProcessed++
                break

            } catch (e: SecurityException) {
                // Create a dummy scan item for error handling
                val dummyItem = WorkItem.ScanPath(
                    path = lookup.lookedUp,
                    topLevelTarget = lookup.lookedUp,
                    targetIndex = 0,
                    totalTargets = targets.size
                )

                handlePermissionError(
                    error = e,
                    context = ErrorContext.Delete(lookup, "Delete file/directory"),
                    workItem = dummyItem
                )
                break

            } catch (e: Exception) {
                val dummyItem = WorkItem.ScanPath(
                    path = lookup.lookedUp,
                    topLevelTarget = lookup.lookedUp,
                    targetIndex = 0,
                    totalTargets = targets.size
                )

                handleUnknownError(
                    error = e,
                    context = ErrorContext.Delete(lookup, "Delete file/directory"),
                    workItem = dummyItem
                )
                break

            } finally {
                onProgress?.invoke(progress)
            }
        }
    }

    private suspend fun processResolveIssue(item: WorkItem.ResolveIssue) {
        val resolution = onIssue!!.invoke(item.issue)

        when (item.issue) {
            is PathActionIssue.InsufficientPermission -> {
                when (val res = resolution as PathActionIssue.InsufficientPermission.Resolution) {
                    is PathActionIssue.InsufficientPermission.Resolution.Cancel -> {
                        throw CancellationException("User cancelled", item.exception)
                    }

                    is PathActionIssue.InsufficientPermission.Resolution.Skip -> {
                        if (res.applyToAll) issueSkippAllPermission = true
                        skipped.add(item.lookup)
                        itemsProcessed++
                    }
                }
            }

            is PathActionIssue.UnknownError -> {
                when (val res = resolution as PathActionIssue.UnknownError.Resolution) {
                    is PathActionIssue.UnknownError.Resolution.Cancel -> {
                        throw CancellationException("User cancelled", item.exception)
                    }

                    is PathActionIssue.UnknownError.Resolution.Retry -> {
                        // Re-queue the original work item
                        workQueue.addFirst(item.originalItem)
                    }

                    is PathActionIssue.UnknownError.Resolution.Skip -> {
                        if (res.applyToAll) issueSkippAllUnknown = true
                        skipped.add(item.lookup)
                        itemsProcessed++
                    }
                }
            }

            else -> throw IllegalArgumentException("Unsupported issue type: ${item.issue}")
        }
    }

    private fun createProgress(lookup: LocalPathLookup): DeleteAction.State.Progress<LocalPath, LocalPathLookup> {
        return DeleteAction.State.Progress(
            target = lookup,
            primaryProgress = eu.darken.butler.common.progress.Progress.Data(
                primary = R.string.general_delete_progress_title.toCaString(),
                secondary = lookup.userReadablePath,
                count = eu.darken.butler.common.progress.Progress.Count.Counter(
                    current = itemsProcessed,
                    max = totalItems
                )
            ),
            secondaryProgress = null
        )
    }

    companion object {
        private val TAG = logTag("Gateway", "Local", "Delete")
    }
}
