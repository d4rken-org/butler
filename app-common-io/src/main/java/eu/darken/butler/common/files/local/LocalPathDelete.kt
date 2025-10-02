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

    // Work queue for processing all delete and issue operations
    private var workQueue = ArrayDeque<WorkItem>()

    // Single-use flag
    private var hasExecuted = false

    /**
     * Sealed hierarchy of work items for the delete queue
     */
    private sealed class WorkItem {
        /**
         * Delete a path (either scan children first or perform deletion)
         * @param path The path to delete
         * @param phase Whether to scan children or perform actual deletion
         * @param topLevelTarget The root target being deleted
         * @param targetIndex Index of this target in the targets collection
         * @param totalTargets Total number of targets being deleted
         */
        data class Delete(
            val path: LocalPath,
            val phase: Phase,
            val topLevelTarget: LocalPath,
            val targetIndex: Int,
            val totalTargets: Int
        ) : WorkItem()

        /**
         * Phase of deletion operation
         */
        enum class Phase {
            SCAN_CHILDREN,  // Scan directory contents and queue children
            DELETE_SELF     // Perform actual deletion
        }

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

        // Initialize work queue with delete operations for all targets
        targets.forEachIndexed { targetIndex, target ->
            workQueue.addLast(
                WorkItem.Delete(
                    path = target,
                    phase = WorkItem.Phase.SCAN_CHILDREN,
                    topLevelTarget = target,
                    targetIndex = targetIndex,
                    totalTargets = targets.size
                )
            )
        }

        // Process work queue in single unified loop
        while (workQueue.isNotEmpty() && currentCoroutineContext().isActive) {
            when (val item = workQueue.removeFirst()) {
                is WorkItem.Delete -> processDelete(item)
                is WorkItem.ResolveIssue -> processResolveIssue(item)
            }
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

    private suspend fun processDelete(item: WorkItem.Delete) {
        when (item.phase) {
            WorkItem.Phase.SCAN_CHILDREN -> {
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
                        // Files: queue for immediate deletion
                        totalItems++
                        workQueue.addFirst(
                            WorkItem.Delete(
                                path = item.path,
                                phase = WorkItem.Phase.DELETE_SELF,
                                topLevelTarget = item.topLevelTarget,
                                targetIndex = item.targetIndex,
                                totalTargets = item.totalTargets
                            )
                        )
                    }

                    FileType.DIRECTORY -> {
                        if (!recursive) {
                            // Non-recursive: queue directory for deletion (will fail if not empty)
                            totalItems++
                            workQueue.addFirst(
                                WorkItem.Delete(
                                    path = item.path,
                                    phase = WorkItem.Phase.DELETE_SELF,
                                    topLevelTarget = item.topLevelTarget,
                                    targetIndex = item.targetIndex,
                                    totalTargets = item.totalTargets
                                )
                            )
                        } else {
                            // Recursive: queue directory deletion after children
                            // First, add THIS directory's DELETE_SELF to front
                            totalItems++
                            workQueue.addFirst(
                                WorkItem.Delete(
                                    path = item.path,
                                    phase = WorkItem.Phase.DELETE_SELF,
                                    topLevelTarget = item.topLevelTarget,
                                    targetIndex = item.targetIndex,
                                    totalTargets = item.totalTargets
                                )
                            )

                            // Then list and queue children (they will be processed before parent)
                            try {
                                Files.newDirectoryStream(item.path.toNioPath()).use { ds ->
                                    for (child in ds) {
                                        val childPath = LocalPath.build(child.toFile())
                                        // Add child scan to front (processed before parent's DELETE_SELF)
                                        workQueue.addFirst(
                                            WorkItem.Delete(
                                                path = childPath,
                                                phase = WorkItem.Phase.SCAN_CHILDREN,
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
                                return
                            } catch (e: SecurityException) {
                                handlePermissionError(
                                    error = e,
                                    context = ErrorContext.Scan(lookup, "List directory contents"),
                                    workItem = item
                                )
                                return
                            } catch (e: Exception) {
                                handleUnknownError(
                                    error = e,
                                    context = ErrorContext.Scan(lookup, "List directory contents"),
                                    workItem = item
                                )
                                return
                            }
                        }
                    }

                    FileType.UNKNOWN -> throw IllegalStateException("Unknown file type: $lookup")
                }
            }

            WorkItem.Phase.DELETE_SELF -> {
                log(TAG, VERBOSE) { "Deleting path: ${item.path}" }

                val lookup = try {
                    item.path.performLookup()
                } catch (e: NoSuchFileException) {
                    if (ignoreMissing) {
                        log(TAG, VERBOSE) { "File already deleted (ignoreMissing=true): ${item.path}" }
                        itemsProcessed++
                        return
                    }
                    throw ReadException("File does not exist", item.path, e)
                }

                val progress = createProgress(lookup)

                try {
                    onProgress?.invoke(progress)

                    Files.delete(lookup.lookedUp.toNioPath())
                    deleted += lookup
                    itemsProcessed++

                } catch (e: SecurityException) {
                    handlePermissionError(
                        error = e,
                        context = ErrorContext.Delete(lookup, "Delete file/directory"),
                        workItem = item
                    )

                } catch (e: Exception) {
                    handleUnknownError(
                        error = e,
                        context = ErrorContext.Delete(lookup, "Delete file/directory"),
                        workItem = item
                    )

                } finally {
                    onProgress?.invoke(progress)
                }
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
