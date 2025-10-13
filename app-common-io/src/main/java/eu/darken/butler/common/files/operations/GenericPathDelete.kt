package eu.darken.butler.common.files.operations

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.APathLookupExtended
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.local.operations.core.PathOperationIssueResolver
import eu.darken.butler.common.files.local.operations.core.PathOperationProgressTracker
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.io.R
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * Generic delete operation that works with any path type.
 *
 * Delete is simpler than copy/move as it doesn't need a TransferStrategy - it just
 * uses FileSystemOps.delete() directly. The complexity is in:
 * 1. Tree traversal and post-order deletion (children before parents)
 * 2. Error handling with "apply to all" support
 * 3. Progress reporting
 *
 * ## Algorithm
 *
 * 1. **Scan Phase**: Walk source tree, calculate total size, queue delete operations
 * 2. **Delete Phase**: Delete files and directories in post-order (children first)
 * 3. **Progress Reporting**: Report progress with throttling
 * 4. **Error Handling**: Handle permission errors, etc. via user callback
 *
 * ## Work Queue Pattern
 *
 * Uses work queue with two phases:
 * - **Phase 1 (Scan)**: ScanPath items are processed, building list of all items
 * - **Phase 2 (Delete)**: After scan completes, DeletePath items execute in post-order
 *
 * Post-order deletion ensures directories are empty before deletion attempt.
 *
 * @param P The path type (LocalPath, SAFPath, FtpPath, etc.)
 * @param PL The path lookup type (LocalPathLookup, SAFPathLookup, etc.)
 * @param PLE The path lookup extended type (LocalPathLookupExtended, SAFPathLookupExtended, etc.)
 */
internal class GenericPathDelete<P : APath, PL : APathLookup<P>, PLE : APathLookupExtended<P>>(
    private val targets: Collection<P>,
    private val recursive: Boolean,
    private val ignoreMissing: Boolean,
    private val fileSystemOps: FileSystemOps<P, PL, PLE>,
    private val onProgress: (suspend (DeleteAction.State.Progress<P, PL>) -> Unit)?,
    private val onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?
) {

    private val deleted = linkedSetOf<PL>()
    private val skipped = linkedSetOf<PL>()

    // Shared components
    private val progressTracker = PathOperationProgressTracker()
    private val issueResolver = PathOperationIssueResolver(onIssue)

    // Scan tracking
    private var scanItemsRemaining = 0

    // Work queue
    private var workQueue = ArrayDeque<WorkItem>()

    // Collect DeletePath items during scanning to preserve post-order
    private val deferredDeletions = ArrayDeque<WorkItem.DeletePath<P, PL>>()

    private sealed class WorkItem {
        /**
         * Scan a path and queue children for deletion.
         */
        data class ScanPath<P : APath>(
            val path: P,
        ) : WorkItem()

        /**
         * Perform actual deletion of a path.
         * Stores lookup from scan phase to avoid redundant lookups during deletion.
         */
        data class DeletePath<P : APath, PL : APathLookup<P>>(
            val lookup: PL,
        ) : WorkItem()
    }

    suspend fun execute(): DeleteAction.State.Result<P, PL> {
        log(TAG, DEBUG) {
            "execute(): Deleting ${targets.size} targets (recursive=$recursive, ignoreMissing=$ignoreMissing)"
        }

        // Initialize work queue with scan operations
        scanItemsRemaining = targets.size
        targets.forEach { target ->
            workQueue.addLast(WorkItem.ScanPath(target))
        }

        // Process work queue in two phases
        while (workQueue.isNotEmpty() && currentCoroutineContext().isActive) {
            when (val item = workQueue.removeFirst()) {
                is WorkItem.ScanPath<*> -> {
                    scanItemsRemaining--
                    val childrenAdded = processScan(item as WorkItem.ScanPath<P>)
                    scanItemsRemaining += childrenAdded

                    // When scan completes, add all deferred deletions to queue
                    if (scanItemsRemaining == 0) {
                        val snapshot = progressTracker.createSnapshot()
                        log(TAG, DEBUG) { "Scan complete: ${snapshot.totalItems} items to delete" }
                        deferredDeletions.forEach { workQueue.addLast(it) }
                        deferredDeletions.clear()
                    }
                }

                is WorkItem.DeletePath<*, *> -> processDeletePath(item as WorkItem.DeletePath<P, PL>)
            }
        }

        return DeleteAction.State.Result(
            deleted = deleted,
            skipped = skipped,
        )
    }

    private suspend fun processScan(item: WorkItem.ScanPath<P>): Int {
        log(TAG, VERBOSE) { "Scanning path: ${item.path}" }

        val lookup = try {
            fileSystemOps.lookup(item.path)
        } catch (e: Exception) {
            if (ignoreMissing) {
                log(TAG, VERBOSE) { "Skipping missing file (ignoreMissing=true): ${item.path}" }
                return 0
            }
            throw eu.darken.butler.common.files.errors.ReadException(
                "File does not exist",
                item.path,
                e
            )
        }

        when (lookup.fileType) {
            FileType.SYMBOLIC_LINK, FileType.FILE -> {
                // Files: defer deletion until scan completes (using addFirst for post-order)
                progressTracker.totalItems++
                progressTracker.totalBytes += lookup.size
                deferredDeletions.addFirst(WorkItem.DeletePath(lookup))

                // Report scan progress with throttling
                if (progressTracker.shouldReportProgress()) {
                    reportScanProgress(lookup)
                }

                return 0
            }

            FileType.DIRECTORY -> {
                if (!recursive) {
                    // Non-recursive: defer directory deletion (will fail if not empty)
                    progressTracker.totalItems++
                    progressTracker.totalBytes += lookup.size
                    deferredDeletions.addFirst(WorkItem.DeletePath(lookup))

                    // Report scan progress with throttling
                    if (progressTracker.shouldReportProgress()) {
                        reportScanProgress(lookup)
                    }

                    return 0
                } else {
                    // Recursive: scan children first, then defer directory deletion
                    var childrenFound = 0

                    try {
                        val children = fileSystemOps.listFiles(item.path)
                        children.forEach { child ->
                            // Add child scan to front (processed before parent's deletion)
                            workQueue.addFirst(WorkItem.ScanPath(child))
                            childrenFound++
                        }
                    } catch (e: Exception) {
                        // Add item before handling error so counts are correct
                        progressTracker.totalItems++
                        progressTracker.totalBytes += lookup.size
                        handleScanError(e, lookup, "List directory contents")
                        return 0
                    }

                    // After successfully scanning children, defer directory deletion (post-order)
                    progressTracker.totalItems++
                    progressTracker.totalBytes += lookup.size
                    deferredDeletions.addFirst(WorkItem.DeletePath(lookup))

                    // Report scan progress with throttling
                    if (progressTracker.shouldReportProgress()) {
                        reportScanProgress(lookup)
                    }

                    return childrenFound
                }
            }

            FileType.UNKNOWN -> throw IllegalStateException("Unknown file type: $lookup")
        }
    }

    private suspend fun processDeletePath(item: WorkItem.DeletePath<P, PL>) {
        // Use lookup from scan phase to avoid redundant lookup
        val lookup = item.lookup
        log(TAG, VERBOSE) { "Deleting path: ${lookup.lookedUp}" }

        progressTracker.startFile(lookup.size)

        try {
            // Report progress with throttling
            if (progressTracker.shouldReportProgress()) {
                reportProgress(lookup)
            }

            val deleteResult = fileSystemOps.delete(lookup.lookedUp)
            if (!deleteResult && ignoreMissing) {
                // File might have been deleted between scan and delete phases
                log(TAG, VERBOSE) { "File not found during delete (ignoreMissing=true): ${lookup.lookedUp}" }
                progressTracker.completeItem(lookup.size)
                return
            }

            deleted += lookup
            progressTracker.completeItem(lookup.size)

        } catch (e: Exception) {
            // Handle case where file was deleted between scan and delete phases
            if (ignoreMissing && (e is java.io.FileNotFoundException ||
                e.cause is java.io.FileNotFoundException)) {
                log(TAG, VERBOSE) { "File already deleted (ignoreMissing=true): ${lookup.lookedUp}" }
                progressTracker.completeItem(lookup.size)
                return
            }
            handleDeleteError(e, lookup)

        } finally {
            // Force final progress report
            if (progressTracker.shouldReportProgress(force = true)) {
                reportProgress(lookup)
            }
        }
    }

    /**
     * Shared error handling for both scan and delete operations.
     */
    private suspend fun handleError(
        error: Exception,
        lookup: PL,
        operation: String,
        canRetry: Boolean = false
    ) {
        log(TAG, ERROR) { "$operation failed: ${lookup.lookedUp} - $error" }

        // Check if we should skip based on error type
        val isPermissionError = error is java.nio.file.AccessDeniedException ||
            error is SecurityException

        if (isPermissionError) {
            if (issueResolver.shouldSkipPermission()) {
                log(TAG, INFO) { "Skipping permission issue (apply-to-all): ${lookup.lookedUp}" }
                skipped.add(lookup)
                progressTracker.completeItem()
                return
            }

            if (onIssue == null) {
                throw eu.darken.butler.common.files.errors.WriteException(
                    path = lookup.lookedUp,
                    cause = error
                )
            }

            val issue = PathActionIssue.InsufficientPermission(
                destination = lookup,
                exception = eu.darken.butler.common.files.errors.WriteException(
                    path = lookup.lookedUp,
                    cause = error
                ),
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

            if (onIssue == null) {
                throw eu.darken.butler.common.files.errors.WriteException(
                    path = lookup.lookedUp,
                    cause = error
                )
            }

            val issue = PathActionIssue.UnknownError(
                destination = lookup,
                exception = eu.darken.butler.common.files.errors.WriteException(
                    path = lookup.lookedUp,
                    cause = error
                ),
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
                    // Retry not implemented
                }
                is PathActionIssue.UnknownError.Resolution.Cancel -> {
                    // Already thrown by resolveIssue
                }
            }
        }
    }

    private suspend fun handleDeleteError(error: Exception, lookup: PL) {
        handleError(error, lookup, operation = "Delete", canRetry = false)
    }

    private suspend fun handleScanError(error: Exception, lookup: PL, operation: String) {
        handleError(error, lookup, operation = operation, canRetry = false)
    }

    private suspend fun reportScanProgress(lookup: PL) {
        val snapshot = progressTracker.createSnapshot()

        onProgress?.invoke(
            DeleteAction.State.Progress(
                target = lookup,
                primaryProgress = eu.darken.butler.common.progress.Progress.Data(
                    primary = R.string.general_scan_progress_title.toCaString(),
                    secondary = lookup.userReadablePath,
                    count = eu.darken.butler.common.progress.Progress.Count.Counter(
                        current = 0,
                        max = snapshot.totalItems
                    )
                ),
                secondaryProgress = null,
                deletedBytes = 0,
                totalBytes = snapshot.totalBytes,
                currentItemStartTime = null
            )
        )
    }

    private suspend fun reportProgress(lookup: PL) {
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
        private val TAG = logTag("PathOperation", "GenericDelete")
    }
}

/**
 * Extension function for easy use of GenericPathDelete.
 */
suspend fun <P : APath, PL : APathLookup<P>, PLE : APathLookupExtended<P>> P.deleteGeneric(
    fileSystemOps: FileSystemOps<P, PL, PLE>,
    recursive: Boolean = true,
    ignoreMissing: Boolean = true,
    onProgress: (suspend (DeleteAction.State.Progress<P, PL>) -> Unit)? = null,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
) = setOf(this).deleteGeneric(fileSystemOps, recursive, ignoreMissing, onProgress, onIssue)

suspend fun <P : APath, PL : APathLookup<P>, PLE : APathLookupExtended<P>> Collection<P>.deleteGeneric(
    fileSystemOps: FileSystemOps<P, PL, PLE>,
    recursive: Boolean = true,
    ignoreMissing: Boolean = true,
    onProgress: (suspend (DeleteAction.State.Progress<P, PL>) -> Unit)? = null,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
): DeleteAction.State.Result<P, PL> = GenericPathDelete(
    targets = this,
    recursive = recursive,
    ignoreMissing = ignoreMissing,
    fileSystemOps = fileSystemOps,
    onProgress = onProgress,
    onIssue = onIssue
).execute()
