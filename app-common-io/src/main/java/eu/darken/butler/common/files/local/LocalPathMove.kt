package eu.darken.butler.common.files.local

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.actions.PathActionIssue
import kotlinx.coroutines.flow.Flow

/**
 * Move extension functions for LocalPath.
 *
 * These are thin wrappers around the generic move framework using LocalPathOperations.
 * The actual implementation delegates to GenericPathMove with LocalPathMoveStrategy.
 *
 * ## Migration Note
 *
 * This file was migrated from using PathOperationExecutor (old) to GenericPathMove (new).
 * The public API now returns Flow<MoveAction.State> instead of using callback-based progress.
 * Source directory cleanup is now handled by GenericPathMove automatically.
 *
 * @see moveGenericOp for the actual implementation
 */

fun LocalPath.move(
    fileSystemOps: LocalFileSystemOps,
    destination: LocalPath,
    options: MoveAction.Options<LocalPath> = MoveAction.Options(),
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null,
) = setOf(this).move(fileSystemOps, destination, options, onIssue)

fun Collection<LocalPath>.move(
    fileSystemOps: LocalFileSystemOps,
    destination: LocalPath,
    options: MoveAction.Options<LocalPath> = MoveAction.Options(),
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null,
): Flow<MoveAction.State<LocalPath, LocalPathLookup>> {
    log(TAG, DEBUG) {
        "move(): Moving $size targets to $destination (options=$options)"
    }

    // Delegate to generic operation (new implementation)
    return this.moveGenericOp(
        destination = destination,
        fileSystemOps = fileSystemOps,
        options = options,
        onIssue = onIssue
    )
}

private val TAG = logTag("Gateway", "LocalPath", "Move")
