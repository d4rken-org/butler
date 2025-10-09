package eu.darken.butler.common.files.local

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.local.operations.core.PathOperationErrorHandler
import eu.darken.butler.common.files.local.operations.core.PathOperationExecutor
import eu.darken.butler.common.files.local.operations.core.PathOperationIssueResolver
import eu.darken.butler.common.files.local.operations.core.PathOperationProgressTracker
import eu.darken.butler.common.files.local.operations.core.PathOperationUtils
import eu.darken.butler.common.files.local.operations.scanning.SpaceValidator
import eu.darken.butler.common.files.local.operations.strategies.LocalPathCopyStrategy
import eu.darken.butler.common.files.local.operations.strategies.TransferStrategy
import eu.darken.butler.common.io.R
import java.nio.file.AccessDeniedException
import java.nio.file.Files

internal class LocalPathCopy(
    private val sources: Collection<LocalPath>,
    private val destination: LocalPath,
    private val options: CopyAction.Options<LocalPath>,
    private val onProgress: (suspend (CopyAction.State.Progress<LocalPath, LocalPathLookup>) -> Unit)?,
    private val onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?
) {
    private val progressTracker = PathOperationProgressTracker()
    private var hasExecuted = false

    suspend fun execute(): CopyAction.State.Result<LocalPath, LocalPathLookup> {
        check(!hasExecuted) { "LocalPathCopy can only be executed once" }
        hasExecuted = true

        log(TAG, DEBUG) { "Starting copy operation: $sources -> $destination" }

        // Ensure destination exists
        ensureDestinationExists()

        // Create components
        val issueResolver = PathOperationIssueResolver(onIssue)
        val errorHandler = PathOperationErrorHandler(
            issueResolver = issueResolver,
            onItemSkipped = { lookup ->
                reportProgress(lookup.lookedUp as LocalPath, destination, lookup as LocalPathLookup)
            }
        )
        val spaceValidator = SpaceValidator(issueResolver)
        val strategy = LocalPathCopyStrategy()
        val transferOptions = TransferStrategy.Options(
            preserveAttributes = options.preserveAttributes,
            followSymlinks = options.followSymlinks
        )

        // Create executor
        val executor = PathOperationExecutor(
            strategy = strategy,
            sources = sources,
            destination = destination,
            issueResolver = issueResolver,
            errorHandler = errorHandler,
            progressTracker = progressTracker,
            spaceValidator = spaceValidator,
            transferOptions = transferOptions,
            followSymlinks = options.followSymlinks,
            onProgress = { currentSource, currentDest, sourceLookup ->
                reportProgress(currentSource, currentDest, sourceLookup)
            }
        )

        // Execute operation
        val result = executor.execute()

        log(
            TAG,
            DEBUG
        ) { "Copy operation completed: ${result.transferred.size} copied, ${result.skipped.size} skipped" }

        return CopyAction.State.Result(
            copied = result.transferred.toSet(),
            skipped = result.skipped.toSet(),
            copiedBytes = result.bytesTransferred
        )
    }

    private suspend fun ensureDestinationExists() {
        if (!Files.exists(destination.toNioPath())) {
            try {
                Files.createDirectories(destination.toNioPath())
                log(TAG, DEBUG) { "Destination directory created: $destination" }
            } catch (e: AccessDeniedException) {
                throw eu.darken.butler.common.files.errors.WriteException(
                    path = destination,
                    cause = e
                )
            } catch (e: SecurityException) {
                throw eu.darken.butler.common.files.errors.WriteException(
                    path = destination,
                    cause = e
                )
            }
            return
        }

        if (Files.isDirectory(destination.toNioPath())) {
            log(TAG, DEBUG) { "Destination is an existing directory: $destination" }
            return
        }

        log(TAG, WARN) { "Destination exists but is not a directory: $destination" }

        if (onIssue == null) {
            throw eu.darken.butler.common.files.errors.WriteException(
                path = destination,
                cause = java.io.IOException("Destination exists but is not a directory: ${destination.path}")
            )
        }

        val existsError = java.nio.file.FileAlreadyExistsException(destination.path)
        val destLookup = destination.performLookup()
        val sourceLookup = sources.first().performLookup()

        val issue = PathActionIssue.PathAlreadyExists(
            source = sourceLookup,
            destination = destLookup,
            canOverwrite = true,
            canRenameDestination = true,
            suggestedName = PathOperationUtils.generateUniqueName(destination.name, destination.file.parentFile!!),
        )

        when (val resolution = onIssue.invoke(issue) as PathActionIssue.PathAlreadyExists.Resolution) {
            is PathActionIssue.PathAlreadyExists.Resolution.Overwrite -> {
                log(TAG, DEBUG) { "Overwriting file at destination: $destination" }
                Files.delete(destination.toNioPath())
            }
            is PathActionIssue.PathAlreadyExists.Resolution.RenameDestination -> {
                log(TAG, DEBUG) { "Renaming existing file: $destination -> ${resolution.newName}" }
                val newDestPath = LocalPath.build(java.io.File(destination.file.parentFile!!, resolution.newName))
                Files.move(destination.toNioPath(), newDestPath.toNioPath())
            }
            is PathActionIssue.PathAlreadyExists.Resolution.Cancel -> throw kotlin.coroutines.cancellation.CancellationException(
                "User cancelled",
                existsError
            )
            is PathActionIssue.PathAlreadyExists.Resolution.Skip,
            is PathActionIssue.PathAlreadyExists.Resolution.RenameSource,
            is PathActionIssue.PathAlreadyExists.Resolution.Merge -> {
                throw UnsupportedOperationException("Invalid resolution for destination conflict", existsError)
            }
        }

        try {
            Files.createDirectories(destination.toNioPath())
        } catch (e: AccessDeniedException) {
            throw eu.darken.butler.common.files.errors.WriteException(
                path = destination,
                cause = e
            )
        } catch (e: SecurityException) {
            throw eu.darken.butler.common.files.errors.WriteException(
                path = destination,
                cause = e
            )
        }
    }

    private suspend fun reportProgress(
        currentSource: LocalPath,
        currentDestination: LocalPath,
        sourceLookup: LocalPathLookup
    ) {
        val snapshot = progressTracker.createSnapshot()

        onProgress?.invoke(
            CopyAction.State.Progress(
                currentSource = currentSource,
                currentDestination = currentDestination,
                copiedBytes = snapshot.processedBytes,
                totalBytes = snapshot.totalBytes,
                currentFileSize = snapshot.currentFileSize,
                currentFileBytes = snapshot.currentFileBytes,
                currentFileStartTime = snapshot.currentFileStartTime,
                primaryProgress = eu.darken.butler.common.progress.Progress.Data(
                    primary = R.string.general_copy_progress_title.toCaString(),
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
        private val TAG = logTag("Gateway", "LocalPath", "Copy")
    }
}

suspend fun LocalPath.copy(
    destination: LocalPath,
    options: CopyAction.Options<LocalPath> = CopyAction.Options(),
    onProgress: (suspend (CopyAction.State.Progress<LocalPath, LocalPathLookup>) -> Unit)? = null,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null,
) = setOf(this).copy(destination, options, onProgress, onIssue)

suspend fun Collection<LocalPath>.copy(
    destination: LocalPath,
    options: CopyAction.Options<LocalPath> = CopyAction.Options(),
    onProgress: (suspend (CopyAction.State.Progress<LocalPath, LocalPathLookup>) -> Unit)? = null,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null,
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
