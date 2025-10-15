package eu.darken.butler.common.files.local

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.actions.PathActionIssue

/**
 * Copy extension functions for LocalPath.
 *
 * These are thin wrappers around the generic copy framework using LocalPathOperations.
 * The actual implementation delegates to GenericPathCopy with LocalPathCopyStrategy.
 *
 * ## Migration Note
 *
 * This file was migrated from using PathOperationExecutor (old) to GenericPathCopy (new).
 * The public API remains unchanged - only the internal implementation changed.
 *
 * @see copyGenericOp for the actual implementation
 */

suspend fun LocalPath.copy(
    fileSystemOps: LocalFileSystemOps,
    destination: LocalPath,
    options: CopyAction.Options<LocalPath> = CopyAction.Options(),
    onProgress: (suspend (CopyAction.State.Progress<LocalPath, LocalPathLookup>) -> Unit)? = null,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null,
) = setOf(this).copy(fileSystemOps, destination, options, onProgress, onIssue)

suspend fun Collection<LocalPath>.copy(
    fileSystemOps: LocalFileSystemOps,
    destination: LocalPath,
    options: CopyAction.Options<LocalPath> = CopyAction.Options(),
    onProgress: (suspend (CopyAction.State.Progress<LocalPath, LocalPathLookup>) -> Unit)? = null,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null,
): CopyAction.State.Result<LocalPath, LocalPathLookup> {
    log(TAG, DEBUG) {
        "copy(): Copying $size targets to $destination (options=$options)"
    }

    // Delegate to generic operation (new implementation)
    return this.copyGenericOp(
        destination = destination,
        fileSystemOps = fileSystemOps,
        options = options,
        onProgress = onProgress,
        onIssue = onIssue
    )
}

private val TAG = logTag("Gateway", "LocalPath", "Copy")
