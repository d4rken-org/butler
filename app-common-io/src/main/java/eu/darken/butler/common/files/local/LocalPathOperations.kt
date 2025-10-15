package eu.darken.butler.common.files.local

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.local.operations.strategies.LocalPathCopyStrategy
import eu.darken.butler.common.files.local.operations.strategies.LocalPathMoveStrategy
import eu.darken.butler.common.files.operations.copyGeneric
import eu.darken.butler.common.files.operations.moveGeneric

/**
 * LocalPath copy operation using the generic framework.
 *
 * This is a thin wrapper that:
 * 1. Creates LocalPathCopyStrategy
 * 2. Delegates to GenericPathCopy
 *
 * ## Usage
 *
 * ```kotlin
 * val result = setOf(source1, source2).copyGenericOp(
 *     destination = destDir,
 *     fileSystemOps = localFileSystemOps,
 *     options = CopyAction.Options(),
 *     onProgress = { progress -> /* update UI */ },
 *     onIssue = { issue -> /* handle conflicts */ }
 * )
 * ```
 *
 * ## Comparison with Old Implementation
 *
 * | Feature | Old (PathOperationExecutor) | New (GenericPathCopy) |
 * |---------|----------------------------|------------------------|
 * | Algorithm | LocalPath-specific | Generic for all path types |
 * | Code reuse | Duplicated logic | Shared with SAF, FTP, etc. |
 * | Testing | LocalPath tests only | Generic tests + strategy tests |
 * | Maintainability | 3 classes, 800+ lines | 1 strategy, ~200 lines |
 */
suspend fun LocalPath.copyGenericOp(
    destination: LocalPath,
    fileSystemOps: LocalFileSystemOps,
    options: CopyAction.Options<LocalPath> = CopyAction.Options(),
    onProgress: (suspend (CopyAction.State.Progress<LocalPath, LocalPathLookup>) -> Unit)? = null,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
): CopyAction.State.Result<LocalPath, LocalPathLookup> = setOf(this).copyGenericOp(
    destination, fileSystemOps, options, onProgress, onIssue
)

suspend fun Collection<LocalPath>.copyGenericOp(
    destination: LocalPath,
    fileSystemOps: LocalFileSystemOps,
    options: CopyAction.Options<LocalPath> = CopyAction.Options(),
    onProgress: (suspend (CopyAction.State.Progress<LocalPath, LocalPathLookup>) -> Unit)? = null,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
): CopyAction.State.Result<LocalPath, LocalPathLookup> {
    val strategy = LocalPathCopyStrategy(fileSystemOps)

    // Convert CopyAction.Options to TransferStrategy.Options
    val transferOptions = eu.darken.butler.common.files.operations.TransferStrategy.Options(
        preserveAttributes = options.preserveAttributes,
        followSymlinks = options.followSymlinks
    )

    // For same-type LocalPath operations, sourceOps and destOps are the same instance
    return this.copyGeneric(
        destination = destination,
        sourceOps = fileSystemOps,
        destOps = fileSystemOps,
        strategy = strategy,
        options = transferOptions,
        onProgress = onProgress,
        onIssue = onIssue
    )
}

/**
 * LocalPath move operation using the generic framework.
 *
 * Move attempts atomic operations where possible (Files.move with ATOMIC_MOVE),
 * falling back to copy+delete for cross-device moves.
 *
 * ## Usage
 *
 * ```kotlin
 * val result = setOf(source1, source2).moveGenericOp(
 *     destination = destDir,
 *     fileSystemOps = localFileSystemOps,
 *     options = MoveAction.Options(),
 *     onProgress = { progress -> /* update UI */ },
 *     onIssue = { issue -> /* handle conflicts */ }
 * )
 * ```
 */
suspend fun LocalPath.moveGenericOp(
    destination: LocalPath,
    fileSystemOps: LocalFileSystemOps,
    options: MoveAction.Options<LocalPath> = MoveAction.Options(),
    onProgress: (suspend (MoveAction.State.Progress<LocalPath, LocalPathLookup>) -> Unit)? = null,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
): MoveAction.State.Result<LocalPath, LocalPathLookup> = setOf(this).moveGenericOp(
    destination, fileSystemOps, options, onProgress, onIssue
)

suspend fun Collection<LocalPath>.moveGenericOp(
    destination: LocalPath,
    fileSystemOps: LocalFileSystemOps,
    options: MoveAction.Options<LocalPath> = MoveAction.Options(),
    onProgress: (suspend (MoveAction.State.Progress<LocalPath, LocalPathLookup>) -> Unit)? = null,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
): MoveAction.State.Result<LocalPath, LocalPathLookup> {
    val strategy = LocalPathMoveStrategy(fileSystemOps)

    // Convert MoveAction.Options to TransferStrategy.Options
    val transferOptions = eu.darken.butler.common.files.operations.TransferStrategy.Options(
        preserveAttributes = options.preserveAttributes,
        followSymlinks = false // MoveAction doesn't have followSymlinks option
    )

    return this.moveGeneric(
        destination = destination,
        sourceOps = fileSystemOps,
        destOps = fileSystemOps,
        strategy = strategy,
        options = transferOptions,
        onProgress = onProgress,
        onIssue = onIssue
    )
}
