package eu.darken.butler.common.files.local

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.local.operations.strategies.LocalPathCopyStrategy
import eu.darken.butler.common.files.local.operations.strategies.LocalPathMoveStrategy
import eu.darken.butler.common.files.operations.copyGeneric
import eu.darken.butler.common.files.operations.deleteGeneric
import eu.darken.butler.common.files.operations.moveGeneric
import kotlinx.coroutines.flow.Flow

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
 * val stateFlow = setOf(source1, source2).copyGenericOp(
 *     destination = destDir,
 *     fileSystemOps = localFileSystemOps,
 *     options = CopyAction.Options(),
 *     onIssue = { issue -> /* handle conflicts */ }
 * )
 * stateFlow.collect { state ->
 *     when (state) {
 *         is CopyAction.State.Progress -> /* update UI */
 *         is CopyAction.State.Result -> /* operation complete */
 *     }
 * }
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
fun LocalPath.copyGenericOp(
    destination: LocalPath,
    fileSystemOps: LocalFileSystemOps,
    options: CopyAction.Options<LocalPath> = CopyAction.Options(),
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
): Flow<CopyAction.State<LocalPath, LocalPathLookup>> = setOf(this).copyGenericOp(
    destination, fileSystemOps, options, onIssue
)

fun Collection<LocalPath>.copyGenericOp(
    destination: LocalPath,
    fileSystemOps: LocalFileSystemOps,
    options: CopyAction.Options<LocalPath> = CopyAction.Options(),
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
): Flow<CopyAction.State<LocalPath, LocalPathLookup>> {
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
 * val stateFlow = setOf(source1, source2).moveGenericOp(
 *     destination = destDir,
 *     fileSystemOps = localFileSystemOps,
 *     options = MoveAction.Options(),
 *     onIssue = { issue -> /* handle conflicts */ }
 * )
 * stateFlow.collect { state ->
 *     when (state) {
 *         is MoveAction.State.Progress -> /* update UI */
 *         is MoveAction.State.Result -> /* operation complete */
 *     }
 * }
 * ```
 */
fun LocalPath.moveGenericOp(
    destination: LocalPath,
    fileSystemOps: LocalFileSystemOps,
    options: MoveAction.Options<LocalPath> = MoveAction.Options(),
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
): Flow<MoveAction.State<LocalPath, LocalPathLookup>> = setOf(this).moveGenericOp(
    destination, fileSystemOps, options, onIssue
)

fun Collection<LocalPath>.moveGenericOp(
    destination: LocalPath,
    fileSystemOps: LocalFileSystemOps,
    options: MoveAction.Options<LocalPath> = MoveAction.Options(),
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
): Flow<MoveAction.State<LocalPath, LocalPathLookup>> {
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
        onIssue = onIssue
    )
}

/**
 * LocalPath delete operation using the generic framework.
 *
 * This is a thin wrapper that delegates to GenericPathDelete.
 *
 * ## Usage
 *
 * ```kotlin
 * val stateFlow = setOf(path1, path2).deleteGenericOp(
 *     fileSystemOps = localFileSystemOps,
 *     recursive = true,
 *     ignoreMissing = true,
 *     onIssue = { issue -> /* handle issues */ }
 * )
 * stateFlow.collect { state ->
 *     when (state) {
 *         is DeleteAction.State.Progress -> /* update UI */
 *         is DeleteAction.State.Result -> /* operation complete */
 *     }
 * }
 * ```
 */
fun LocalPath.deleteGenericOp(
    fileSystemOps: LocalFileSystemOps,
    recursive: Boolean = true,
    ignoreMissing: Boolean = true,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
): Flow<DeleteAction.State<LocalPath, LocalPathLookup>> = setOf(this).deleteGenericOp(
    fileSystemOps, recursive, ignoreMissing, onIssue
)

fun Collection<LocalPath>.deleteGenericOp(
    fileSystemOps: LocalFileSystemOps,
    recursive: Boolean = true,
    ignoreMissing: Boolean = true,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
): Flow<DeleteAction.State<LocalPath, LocalPathLookup>> {
    return this.deleteGeneric(
        fileSystemOps = fileSystemOps,
        recursive = recursive,
        ignoreMissing = ignoreMissing,
        onIssue = onIssue
    )
}
