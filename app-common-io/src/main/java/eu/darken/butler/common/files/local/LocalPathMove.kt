package eu.darken.butler.common.files.local

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.local.operations.core.PathOperationErrorHandler
import eu.darken.butler.common.files.local.operations.core.PathOperationExecutor
import eu.darken.butler.common.files.local.operations.core.PathOperationIssueResolver
import eu.darken.butler.common.files.local.operations.core.PathOperationProgressTracker
import eu.darken.butler.common.files.local.operations.core.PathOperationUtils
import eu.darken.butler.common.files.local.operations.scanning.SpaceValidator
import eu.darken.butler.common.files.local.operations.strategies.LocalPathMoveStrategy
import eu.darken.butler.common.files.local.operations.strategies.TransferStrategy
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.io.R

internal class LocalPathMove(
    private val fileSystemOps: LocalFileSystemOps,
    private val sources: Collection<LocalPath>,
    private val destination: LocalPath,
    private val options: MoveAction.Options<LocalPath>,
    private val onProgress: (suspend (MoveAction.State.Progress<LocalPath, LocalPathLookup>) -> Unit)?,
    private val onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?,
) {
    private val fileOperatUtils = PathOperationUtils(fileSystemOps)
    private val progressTracker = PathOperationProgressTracker()
    private var hasExecuted = false

    suspend fun execute(): MoveAction.State.Result<LocalPath, LocalPathLookup> {
        check(!hasExecuted) { "LocalPathMove can only be executed once" }
        hasExecuted = true

        log(TAG, DEBUG) { "Starting move operation: $sources -> $destination" }

        // Ensure destination exists
        fileOperatUtils.ensureDestinationExists(destination, sources, onIssue)

        // Create components
        val issueResolver = PathOperationIssueResolver(onIssue)
        val errorHandler = PathOperationErrorHandler(
            issueResolver = issueResolver,
            onItemSkipped = { lookup ->
                reportProgress(lookup.lookedUp as LocalPath, destination, lookup as LocalPathLookup)
            }
        )
        val spaceValidator = SpaceValidator(fileSystemOps, issueResolver)
        val strategy = LocalPathMoveStrategy(fileSystemOps)
        val transferOptions = TransferStrategy.Options(
            preserveAttributes = options.preserveAttributes,
            followSymlinks = false
        )

        // Create executor
        val executor = PathOperationExecutor(
            fileSystemOps = fileSystemOps,
            strategy = strategy,
            sources = sources,
            destination = destination,
            issueResolver = issueResolver,
            errorHandler = errorHandler,
            progressTracker = progressTracker,
            spaceValidator = spaceValidator,
            transferOptions = transferOptions,
            followSymlinks = false,
            onProgress = { currentSource, currentDest, sourceLookup ->
                reportProgress(currentSource, currentDest, sourceLookup)
            }
        )

        // Execute operation
        val result = executor.execute()

        // Clean up source directories that were moved file-by-file
        cleanupSourceDirectories(result)

        log(
            TAG,
            DEBUG
        ) { "Move operation completed: ${result.transferred.size} transferred, ${result.skipped.size} skipped" }

        return MoveAction.State.Result(
            movedFiles = result.transferred.toSet(),
            skippedFiles = result.skipped.toSet(),
            bytesMoved = result.bytesTransferred
        )
    }

    private suspend fun cleanupSourceDirectories(result: PathOperationExecutor.Result) {
        for (source in sources) {
            if (source in result.skipped) continue

            // Check if source was a directory
            val sourceLookup = try {
                if (fileSystemOps.exists(source)) {
                    fileSystemOps.lookup(source)
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
                    fileOperatUtils.deleteRecursively(source)
                    log(TAG, DEBUG) { "Deleted source directory after move: $source" }
                } catch (e: Exception) {
                    log(TAG, WARN) { "Failed to delete source directory: $source - $e" }
                }
            }
        }
    }

    private suspend fun reportProgress(
        currentSource: LocalPath,
        currentDestination: LocalPath,
        sourceLookup: LocalPathLookup
    ) {
        val snapshot = progressTracker.createSnapshot()

        onProgress?.invoke(
            MoveAction.State.Progress(
                currentSource = currentSource,
                currentDestination = currentDestination,
                movedBytes = snapshot.processedBytes,
                totalBytes = snapshot.totalBytes,
                currentFileSize = snapshot.currentFileSize,
                currentFileBytes = snapshot.currentFileBytes,
                currentFileStartTime = snapshot.currentFileStartTime,
                primaryProgress = eu.darken.butler.common.progress.Progress.Data(
                    primary = R.string.general_move_progress_title.toCaString(),
                    secondary = sourceLookup.userReadablePath,
                    count = eu.darken.butler.common.progress.Progress.Count.Counter(
                        current = snapshot.itemsProcessed,
                        max = snapshot.totalItems
                    )
                ),
                secondaryProgress = eu.darken.butler.common.progress.Progress.Data(
                    primary = currentSource.name.toCaString(),
                    count = eu.darken.butler.common.progress.Progress.Count.Size(
                        current = snapshot.currentFileBytes,
                        max = snapshot.currentFileSize
                    )
                )
            )
        )
    }

    companion object {
        private val TAG = logTag("Gateway", "LocalPath", "Move")
    }
}

suspend fun LocalPath.move(
    fileSystemOps: LocalFileSystemOps,
    destination: LocalPath,
    options: MoveAction.Options<LocalPath> = MoveAction.Options(),
    onProgress: (suspend (MoveAction.State.Progress<LocalPath, LocalPathLookup>) -> Unit)? = null,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null,
) = setOf(this).move(fileSystemOps, destination, options, onProgress, onIssue)

suspend fun Collection<LocalPath>.move(
    fileSystemOps: LocalFileSystemOps,
    destination: LocalPath,
    options: MoveAction.Options<LocalPath> = MoveAction.Options(),
    onProgress: (suspend (MoveAction.State.Progress<LocalPath, LocalPathLookup>) -> Unit)? = null,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null,
): MoveAction.State.Result<LocalPath, LocalPathLookup> {
    log(TAG, DEBUG) {
        "move(): Moving $size targets to $destination (options=$options, onProgress=$onProgress, onIssue=$onIssue)"
    }
    return LocalPathMove(
        fileSystemOps = fileSystemOps,
        sources = this,
        destination = destination,
        options = options,
        onProgress = onProgress,
        onIssue = onIssue,
    ).execute()
}

private val TAG = logTag("Gateway", "LocalPath", "Move")
