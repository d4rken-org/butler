package eu.darken.butler.common.files.operations

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.actions.DeleteAction
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
 */
internal class GenericPathDelete<P : APath<P>, PL : APathLookup<P>>(
    private val targets: Collection<P>,
    private val recursive: Boolean,
    private val ignoreMissing: Boolean,
    private val fileSystemOps: FileSystemOps<P, PL>,
    private val onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?
) {

    private val deleted = linkedSetOf<PL>()
    private val skipped = linkedSetOf<PL>()

    // Shared components
    private val progressTracker = PathOperationProgressTracker()
    private val issueResolver = PathOperationIssueResolver(onIssue)
    private val errorHandler = TransferErrorHandler()

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
        data class ScanPath<P : APath<P>>(
            val path: P,
        ) : WorkItem()

        /**
         * Perform actual deletion of a path.
         * Stores lookup from scan phase to avoid redundant lookups during deletion.
         */
        data class DeletePath<P : APath<P>, PL : APathLookup<P>>(
            val lookup: PL,
        ) : WorkItem() {
            val path: P get() = lookup.lookedUp
        }
    }

    fun execute(): Flow<DeleteAction.State<P, PL>> = flow {
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
                    val childrenAdded = processScan(item as WorkItem.ScanPath<P>, ::emit)
                    scanItemsRemaining += childrenAdded

                    // When scan completes, add all deferred deletions to queue
                    if (scanItemsRemaining == 0) {
                        val snapshot = progressTracker.createSnapshot()
                        log(TAG, DEBUG) { "Scan complete: ${snapshot.totalItems} items to delete" }
                        deferredDeletions.forEach { workQueue.addLast(it) }
                        deferredDeletions.clear()
                    }
                }

                is WorkItem.DeletePath<*, *> -> processDeletePath(item as WorkItem.DeletePath<P, PL>, ::emit)
            }
        }

        // Record final 100% sample before completing
        progressTracker.shouldReportProgress(force = true)

        emit(
            DeleteAction.State.Completed(
                deleted = deleted,
                skipped = skipped,
            )
        )
    }

    private suspend fun processScan(
        item: WorkItem.ScanPath<P>,
        emit: suspend (DeleteAction.State<P, PL>) -> Unit
    ): Int {
        log(TAG, VERBOSE) { "Scanning path: ${item.path}" }

        val lookup = try {
            fileSystemOps.lookup(item.path, LookupOptions.BASE)
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
                progressTracker.totalBytes += lookup.size ?: 0L
                deferredDeletions.addFirst(WorkItem.DeletePath(lookup))

                // Report scan progress with throttling
                if (progressTracker.shouldReportProgress()) {
                    reportScanProgress(lookup, emit)
                }

                return 0
            }

            FileType.DIRECTORY -> {
                if (!recursive) {
                    // Non-recursive: defer directory deletion (will fail if not empty)
                    progressTracker.totalItems++
                    progressTracker.totalBytes += lookup.size ?: 0L
                    deferredDeletions.addFirst(WorkItem.DeletePath(lookup))

                    // Report scan progress with throttling
                    if (progressTracker.shouldReportProgress()) {
                        reportScanProgress(lookup, emit)
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
                        handleScanError(e, lookup, item.path)
                        return 0
                    }

                    // After successfully scanning children, defer directory deletion (post-order)
                    progressTracker.totalItems++
                    progressTracker.totalBytes += lookup.size ?: 0L
                    deferredDeletions.addFirst(WorkItem.DeletePath(lookup))

                    // Report scan progress with throttling
                    if (progressTracker.shouldReportProgress()) {
                        reportScanProgress(lookup, emit)
                    }

                    return childrenFound
                }
            }

            FileType.UNKNOWN -> throw IllegalStateException("Unknown file type: $lookup")
        }
    }

    private suspend fun processDeletePath(
        item: WorkItem.DeletePath<P, PL>,
        emit: suspend (DeleteAction.State<P, PL>) -> Unit
    ) {
        // Use lookup from scan phase to avoid redundant lookup
        val lookup = item.lookup
        log(TAG, VERBOSE) { "Deleting path: ${item.path}" }

        // Only start tracking if not already started (handles retry case)
        if (progressTracker.currentFileSize == 0L) {
            progressTracker.startFile(lookup.size ?: 0L)
        }

        try {
            // Report progress with throttling
            if (progressTracker.shouldReportProgress()) {
                reportProgress(lookup, emit)
            }

            val deleteResult = fileSystemOps.delete(lookup.lookedUp)
            if (!deleteResult && ignoreMissing) {
                // File might have been deleted between scan and delete phases
                log(TAG, VERBOSE) { "File not found during delete (ignoreMissing=true): ${item.path}" }
                progressTracker.completeItem(lookup.size ?: 0L)
                return
            }

            deleted += lookup
            progressTracker.completeItem(lookup.size ?: 0L)

        } catch (e: Exception) {
            // Handle case where file was deleted between scan and delete phases
            if (ignoreMissing && (e is java.io.FileNotFoundException ||
                    e.cause is java.io.FileNotFoundException)
            ) {
                log(TAG, VERBOSE) { "File already deleted (ignoreMissing=true): ${item.path}" }
                progressTracker.completeItem(lookup.size ?: 0L)
                return
            }
            handleDeleteError(e, item)

        } finally {
            // Force final progress report
            if (progressTracker.shouldReportProgress(force = true)) {
                reportProgress(lookup, emit)
            }
        }
    }

    private suspend fun handleDeleteError(error: Exception, originalItem: WorkItem.DeletePath<P, PL>) {
        errorHandler.handleError(
            error = error,
            lookup = originalItem.lookup,
            issueResolver = issueResolver,
            progressTracker = progressTracker,
            onSkip = { skipped.add(it) },
            onRetry = { workQueue.addFirst(originalItem) },
            canRetry = true,
            onIssue = onIssue,
            tag = TAG
        )
    }

    private suspend fun handleScanError(error: Exception, lookup: PL, originalPath: P) {
        errorHandler.handleScanError(
            error = error,
            lookup = lookup,
            issueResolver = issueResolver,
            onSkip = {
                // When skipped, add to progress tracking and skipped set
                progressTracker.totalItems++
                progressTracker.totalBytes += lookup.size ?: 0L
                skipped.add(it)
            },
            onRetry = {
                // Re-queue the scan operation for retry
                // Don't increment progress counters - they'll be incremented when retry succeeds
                workQueue.addFirst(WorkItem.ScanPath(originalPath))
                scanItemsRemaining++
            },
            onIssue = onIssue,
            tag = TAG
        )
    }

    private suspend fun reportScanProgress(lookup: PL, emit: suspend (DeleteAction.State<P, PL>) -> Unit) {
        val snapshot = progressTracker.createSnapshot()

        emit(
            DeleteAction.State.Active(
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

    private suspend fun reportProgress(lookup: PL, emit: suspend (DeleteAction.State<P, PL>) -> Unit) {
        val snapshot = progressTracker.createSnapshot()

        emit(
            DeleteAction.State.Active(
                target = lookup,
                primaryProgress = eu.darken.butler.common.progress.Progress.Data(
                    primary = R.string.general_delete_progress_title.toCaString(),
                    secondary = lookup.userReadablePath,
                    count = eu.darken.butler.common.progress.Progress.Count.Counter(
                        current = snapshot.itemsProcessed,
                        max = snapshot.totalItems
                    ),
                    extra = progressTracker.performanceHistory
                ),
                secondaryProgress = eu.darken.butler.common.progress.Progress.Data(
                    primary = lookup.lookedUp.name.toCaString(),
                    count = eu.darken.butler.common.progress.Progress.Count.Size(
                        current = lookup.size ?: 0L,
                        max = lookup.size ?: 0L
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
fun <P : APath<P>, PL : APathLookup<P>> P.deleteGeneric(
    fileSystemOps: FileSystemOps<P, PL>,
    recursive: Boolean = true,
    ignoreMissing: Boolean = true,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
) = setOf(this).deleteGeneric(fileSystemOps, recursive, ignoreMissing, onIssue)

fun <P : APath<P>, PL : APathLookup<P>> Collection<P>.deleteGeneric(
    fileSystemOps: FileSystemOps<P, PL>,
    recursive: Boolean = true,
    ignoreMissing: Boolean = true,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
): Flow<DeleteAction.State<P, PL>> = GenericPathDelete(
    targets = this,
    recursive = recursive,
    ignoreMissing = ignoreMissing,
    fileSystemOps = fileSystemOps,
    onIssue = onIssue
).execute()
