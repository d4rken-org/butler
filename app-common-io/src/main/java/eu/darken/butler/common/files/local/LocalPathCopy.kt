package eu.darken.butler.common.files.local

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.local.operations.strategies.LocalPathCopyStrategy
import eu.darken.butler.common.files.operations.copyGeneric
import kotlinx.coroutines.flow.Flow
import kotlin.time.Clock

/**
 * Copy extension functions for LocalPath.
 *
 * These are thin wrappers around the generic copy framework using LocalPathOperations.
 * The actual implementation delegates to GenericPathCopy with LocalPathCopyStrategy.
 *
 * ## Migration Note
 *
 * This file was migrated from using PathOperationExecutor (old) to GenericPathCopy (new).
 * The public API now returns Flow<CopyAction.State> instead of using callback-based progress.
 *
 * @see copyGenericOp for the actual implementation
 */

fun LocalPath.copy(
    fileSystemOps: LocalFileSystemOps,
    destination: LocalPath,
    options: CopyAction.Options = CopyAction.Options(),
    progressClock: Clock = Clock.System,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null,
) = setOf(this).copy(fileSystemOps, destination, options, progressClock, onIssue)

fun Collection<LocalPath>.copy(
    fileSystemOps: LocalFileSystemOps,
    destination: LocalPath,
    options: CopyAction.Options = CopyAction.Options(),
    progressClock: Clock = Clock.System,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null,
): Flow<CopyAction.State<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>> {
    log(TAG, DEBUG) {
        "copy(): Copying $size targets to $destination (options=$options)"
    }

    // Convert CopyAction.Options to TransferStrategy.Options
    val transferOptions = eu.darken.butler.common.files.operations.TransferStrategy.Options(
        preserveAttributes = options.preserveAttributes,
        followSymlinks = options.followSymlinks,
        overwrite = options.overwrite,
    )

    // Delegate to generic operation (new implementation)
    return this.copyGeneric(
        destination = destination,
        sourceOps = fileSystemOps,
        destOps = fileSystemOps,
        options = transferOptions,
        strategy = LocalPathCopyStrategy(fileSystemOps),
        onIssue = onIssue,
        progressClock = progressClock,
    )
}

private val TAG = logTag("Gateway", "LocalPath", "Copy")
