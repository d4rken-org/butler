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
import eu.darken.butler.common.files.local.operations.core.PathOperationIssueResolver
import eu.darken.butler.common.files.local.operations.core.PathOperationProgressTracker
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.io.R
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException

internal class LocalPathDelete(
    private val targets: Collection<LocalPath>,
    private val recursive: Boolean,
    private val ignoreMissing: Boolean,
    private val onProgress: (suspend (DeleteAction.State.Progress<LocalPath, LocalPathLookup>) -> Unit)?,
    private val onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?
) {
    private val deleted = linkedSetOf<LocalPathLookup>()
    private val skipped = linkedSetOf<LocalPathLookup>()

    // Shared components
    private val progressTracker = PathOperationProgressTracker()
    private val issueResolver = PathOperationIssueResolver(onIssue)

    // Scan tracking to know when all items are discovered
    private var scanItemsRemaining = 0

    // Work queue for processing all delete and issue operations
    private var workQueue = ArrayDeque<WorkItem>()

    // Collect DeletePath items during scanning to preserve post-order
    private val deferredDeletions = ArrayDeque<WorkItem.DeletePath>()

    // Single-use flag
    private var hasExecuted = false

    /**
     * Sealed hierarchy of work items for the delete queue
     */
    private sealed class WorkItem {
        /**
         * Scan a path and queue children for deletion
         * @param path The path to scan
         */
        data class ScanPath(
            val path: LocalPath,
        ) : WorkItem()

        /**
         * Perform actual deletion of a path
         * @param path The path to delete
         */
        data class DeletePath(
            val path: LocalPath,
        ) : WorkItem()
    }

    suspend fun execute(): DeleteAction.State.Result<LocalPath, LocalPathLookup> {
        check(!hasExecuted) { "LocalPathDelete can only be executed once" }
        hasExecuted = true

        log(TAG, DEBUG) {
            "execute(): Deleting ${targets.size} targets (recursive=$recursive, ignoreMissing=$ignoreMissing)"
        }

        // Initialize work queue with scan operations for all targets
        scanItemsRemaining = targets.size
        targets.forEach { target ->
            workQueue.addLast(WorkItem.ScanPath(path = target))
        }

        // Process work queue in single unified loop
        // Scan items are processed first, delete items are deferred until scan completes
        while (workQueue.isNotEmpty() && currentCoroutineContext().isActive) {
            when (val item = workQueue.removeFirst()) {
                is WorkItem.ScanPath -> {
                    scanItemsRemaining--
                    val childrenAdded = processScan(item)
                    scanItemsRemaining += childrenAdded

                    if (scanItemsRemaining == 0) {
                        val snapshot = progressTracker.createSnapshot()
                        log(TAG, DEBUG) { "Scan complete: ${snapshot.totalItems} items to delete" }
                        // Add all deferred deletions to queue in correct post-order
                        deferredDeletions.forEach { workQueue.addLast(it) }
                        deferredDeletions.clear()
                    }
                }

                is WorkItem.DeletePath -> processDeletePath(item)
            }
        }

        return DeleteAction.State.Result(
            deleted = deleted,
            skipped = skipped,
        )
    }

    /**
     * Shared error handling for both scan and delete operations.
     *
     * Handles permission errors and unknown errors with "apply to all" support.
     * Automatically adds items to skipped set and completes progress tracking.
     *
     * @param error The exception that occurred
     * @param lookup The path lookup that failed
     * @param operation Description of the operation for logging
     * @param canRetry Whether retry is supported for this operation
     */
    private suspend fun handleError(
        error: Exception,
        lookup: LocalPathLookup,
        operation: String,
        canRetry: Boolean = false
    ) {
        log(TAG, ERROR) { "$operation failed: ${lookup.lookedUp} - $error" }

        // Resolve issue and apply resolution
        if (error is AccessDeniedException || error is SecurityException) {
            if (issueResolver.shouldSkipPermission()) {
                log(TAG, INFO) { "Skipping permission issue (apply-to-all): ${lookup.lookedUp}" }
                skipped.add(lookup)
                progressTracker.completeItem()
                return
            }

            if (onIssue == null) throw WriteException(path = lookup.lookedUp, cause = error)

            val issue = PathActionIssue.InsufficientPermission(
                destination = lookup,
                exception = WriteException(path = lookup.lookedUp, cause = error),
                canSkip = true
            )
            val resolution = issueResolver.resolveIssue(issue)

            when (resolution) {
                is PathActionIssue.InsufficientPermission.Resolution.Skip -> {
                    skipped.add(lookup)
                    progressTracker.completeItem()
                }
                is PathActionIssue.InsufficientPermission.Resolution.Cancel -> {
                    // Already thrown by resolveIssue
                }
            }
        } else {
            if (issueResolver.shouldSkipUnknown()) {
                log(TAG, INFO) { "Skipping unknown issue (apply-to-all): ${lookup.lookedUp}" }
                skipped.add(lookup)
                progressTracker.completeItem()
                return
            }

            if (onIssue == null) throw WriteException(path = lookup.lookedUp, cause = error)

            val issue = PathActionIssue.UnknownError(
                destination = lookup,
                exception = WriteException(path = lookup.lookedUp, cause = error),
                canRetry = canRetry,
                canSkip = true
            )
            val resolution = issueResolver.resolveIssue(issue)

            when (resolution) {
                is PathActionIssue.UnknownError.Resolution.Skip -> {
                    skipped.add(lookup)
                    progressTracker.completeItem()
                }
                is PathActionIssue.UnknownError.Resolution.Retry -> {
                    // Retry not implemented in delete operation
                }
                is PathActionIssue.UnknownError.Resolution.Cancel -> {
                    // Already thrown by resolveIssue
                }
            }
        }
    }

    private suspend fun handleDeleteError(error: Exception, lookup: LocalPathLookup) {
        handleError(error, lookup, operation = "Delete", canRetry = false)
    }

    private suspend fun handleScanError(error: Exception, lookup: LocalPathLookup, operation: String) {
        handleError(error, lookup, operation = operation, canRetry = false)
    }

    private suspend fun processScan(item: WorkItem.ScanPath): Int {
        log(TAG, VERBOSE) { "Scanning path: ${item.path}" }

        // Check file existence first when ignoreMissing is enabled
        if (ignoreMissing && !Files.exists(item.path.toNioPath(), LinkOption.NOFOLLOW_LINKS)) {
            log(TAG, VERBOSE) { "Skipping missing file (ignoreMissing=true): ${item.path}" }
            return 0
        }

        val lookup = try {
            item.path.performLookup()
        } catch (e: NoSuchFileException) {
            if (ignoreMissing) {
                log(TAG, VERBOSE) { "Skipping missing file (ignoreMissing=true): ${item.path}" }
                return 0
            }
            throw ReadException("File does not exist", item.path, e)
        }

        when (lookup.fileType) {
            FileType.SYMBOLIC_LINK, FileType.FILE -> {
                // Files: defer deletion until scan completes (using addFirst for post-order)
                progressTracker.totalItems++
                progressTracker.totalBytes += lookup.size
                deferredDeletions.addFirst(WorkItem.DeletePath(path = item.path))
                return 0 // No children for files
            }

            FileType.DIRECTORY -> {
                if (!recursive) {
                    // Non-recursive: defer directory deletion (will fail if not empty, using addFirst for post-order)
                    progressTracker.totalItems++
                    progressTracker.totalBytes += lookup.size
                    deferredDeletions.addFirst(WorkItem.DeletePath(path = item.path))
                    return 0
                } else {
                    // Recursive: scan children first, then defer directory deletion
                    var childrenFound = 0

                    // List and queue children (they will be processed before parent)
                    try {
                        Files.newDirectoryStream(item.path.toNioPath()).use { ds ->
                            for (child in ds) {
                                val childPath = LocalPath.build(child.toFile())
                                // Add child scan to front (processed before parent's DELETE_SELF)
                                workQueue.addFirst(WorkItem.ScanPath(path = childPath))
                                childrenFound++
                            }
                        }
                    } catch (_: NoSuchFileException) {
                        // Directory disappeared between lookup and listing
                        log(TAG, WARN) { "Directory disappeared during scan: ${item.path}" }
                    } catch (e: AccessDeniedException) {
                        // Add item before handling error so counts are correct
                        progressTracker.totalItems++
                        progressTracker.totalBytes += lookup.size
                        handleScanError(e, lookup, "List directory contents - Permission denied")
                        return 0
                    } catch (e: SecurityException) {
                        // Add item before handling error so counts are correct
                        progressTracker.totalItems++
                        progressTracker.totalBytes += lookup.size
                        handleScanError(e, lookup, "List directory contents - Permission denied")
                        return 0
                    } catch (e: Exception) {
                        // Add item before handling error so counts are correct
                        progressTracker.totalItems++
                        progressTracker.totalBytes += lookup.size
                        handleScanError(e, lookup, "List directory contents")
                        return 0
                    }

                    // After successfully scanning children, defer directory deletion (using addFirst for post-order)
                    progressTracker.totalItems++
                    progressTracker.totalBytes += lookup.size
                    deferredDeletions.addFirst(WorkItem.DeletePath(path = item.path))

                    return childrenFound
                }
            }

            FileType.UNKNOWN -> throw IllegalStateException("Unknown file type: $lookup")
        }
    }

    private suspend fun processDeletePath(item: WorkItem.DeletePath) {
        log(TAG, VERBOSE) { "Deleting path: ${item.path}" }

        val lookup = try {
            item.path.performLookup()
        } catch (e: NoSuchFileException) {
            if (ignoreMissing) {
                log(TAG, VERBOSE) { "File already deleted (ignoreMissing=true): ${item.path}" }
                progressTracker.completeItem()
                return
            }
            throw ReadException("File does not exist", item.path, e)
        } catch (e: ReadException) {
            if (ignoreMissing) {
                log(TAG, VERBOSE) { "File already deleted (ignoreMissing=true): ${item.path}" }
                progressTracker.completeItem()
                return
            }
            throw e
        }

        progressTracker.startFile(lookup.size)

        try {
            // Report progress with throttling
            if (progressTracker.shouldReportProgress()) {
                reportProgress(lookup)
            }

            Files.delete(lookup.lookedUp.toNioPath())
            deleted += lookup
            progressTracker.completeItem(lookup.size)

        } catch (e: SecurityException) {
            handleDeleteError(e, lookup)

        } catch (e: DirectoryNotEmptyException) {
            // DirectoryNotEmptyException without issue handler should throw the original exception
            if (onIssue == null) {
                log(TAG, WARN) { "Directory not empty: ${lookup.lookedUp}" }
                throw e
            }
            handleDeleteError(e, lookup)

        } catch (e: Exception) {
            handleDeleteError(e, lookup)

        } finally {
            // Force final progress report
            if (progressTracker.shouldReportProgress(force = true)) {
                reportProgress(lookup)
            }
        }
    }

    private suspend fun reportProgress(lookup: LocalPathLookup) {
        val snapshot = progressTracker.createSnapshot()

        onProgress?.invoke(
            DeleteAction.State.Progress(
                target = lookup,
                primaryProgress = eu.darken.butler.common.progress.Progress.Data(
                    primary = R.string.general_delete_progress_title.toCaString(),
                    secondary = lookup.userReadablePath,
                    count = eu.darken.butler.common.progress.Progress.Count.Counter(
                        current = snapshot.itemsProcessed,
                        max = snapshot.totalItems
                    )
                ),
                secondaryProgress = eu.darken.butler.common.progress.Progress.Data(
                    primary = lookup.lookedUp.name.toCaString(),
                    count = eu.darken.butler.common.progress.Progress.Count.Size(
                        current = lookup.size,
                        max = lookup.size
                    )
                ),
                deletedBytes = snapshot.processedBytes,
                totalBytes = snapshot.totalBytes,
                currentItemStartTime = snapshot.currentFileStartTime
            )
        )
    }

    companion object {
        private val TAG = logTag("Gateway", "Local", "Delete")
    }
}

suspend fun LocalPath.delete(
    recursive: Boolean = true,
    ignoreMissing: Boolean = true,
    onProgress: (suspend (DeleteAction.State.Progress<LocalPath, LocalPathLookup>) -> Unit)? = null,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
) = setOf(this).delete(recursive, ignoreMissing, onProgress, onIssue)

suspend fun Collection<LocalPath>.delete(
    recursive: Boolean = true,
    ignoreMissing: Boolean = true,
    onProgress: (suspend (DeleteAction.State.Progress<LocalPath, LocalPathLookup>) -> Unit)? = null,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
): DeleteAction.State.Result<LocalPath, LocalPathLookup> = LocalPathDelete(
    targets = this,
    recursive = recursive,
    ignoreMissing = ignoreMissing,
    onProgress = onProgress,
    onIssue = onIssue
).execute()
