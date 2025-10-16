package eu.darken.butler.common.files.saf

import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.operations.copyGeneric
import eu.darken.butler.common.files.operations.deleteGeneric
import eu.darken.butler.common.files.operations.moveGeneric
import kotlinx.coroutines.flow.Flow

/**
 * SAFPath copy operation using the generic framework.
 *
 * This is a thin wrapper that:
 * 1. Creates SAFFileSystemOps with Android dependencies
 * 2. Creates SAFPathCopyStrategy
 * 3. Delegates to GenericPathCopy
 *
 * ## Usage
 *
 * ```kotlin
 * val stateFlow = setOf(source1, source2).copy(
 *     destination = destDir,
 *     fileSystemOps = safFileSystemOps,
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
 * ## Testability
 *
 * For testing, use MockSAFFileSystemOps instead of real SAFFileSystemOps:
 *
 * ```kotlin
 * @Test
 * fun `copy 100 SAF files`() = runTest {
 *     val mockOps = MockSAFFileSystemOps()
 *     // Setup mocks...
 *
 *     val states = sources.copy(
 *         destination = dest,
 *         fileSystemOps = mockOps,
 *         onIssue = null
 *     ).toList()
 *
 *     // Verify without Android framework!
 * }
 * ```
 */
fun SAFPath.copy(
    destination: SAFPath,
    fileSystemOps: SAFFileSystemOps,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
): Flow<CopyAction.State<SAFPath, SAFPathLookup>> = setOf(this).copy(
    destination, fileSystemOps, onIssue
)

fun Collection<SAFPath>.copy(
    destination: SAFPath,
    fileSystemOps: SAFFileSystemOps,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
): Flow<CopyAction.State<SAFPath, SAFPathLookup>> {
    val strategy = SAFPathCopyStrategy()

    // For same-type SAF operations, sourceOps and destOps are the same instance
    return this.copyGeneric(
        destination = destination,
        sourceOps = fileSystemOps,
        destOps = fileSystemOps,
        strategy = strategy,
        options = eu.darken.butler.common.files.operations.TransferStrategy.Options(),
        onIssue = onIssue
    )
}

/**
 * SAFPath move operation using the generic framework.
 *
 * Move is implemented as copy+delete since SAF doesn't support atomic moves.
 *
 * ## Usage
 *
 * ```kotlin
 * val stateFlow = setOf(source1, source2).move(
 *     destination = destDir,
 *     fileSystemOps = safFileSystemOps,
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
fun SAFPath.move(
    destination: SAFPath,
    fileSystemOps: SAFFileSystemOps,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
): Flow<MoveAction.State<SAFPath, SAFPathLookup>> = setOf(this).move(
    destination, fileSystemOps, onIssue
)

fun Collection<SAFPath>.move(
    destination: SAFPath,
    fileSystemOps: SAFFileSystemOps,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
): Flow<MoveAction.State<SAFPath, SAFPathLookup>> {
    val strategy = SAFPathMoveStrategy()

    return this.moveGeneric(
        destination = destination,
        sourceOps = fileSystemOps,
        destOps = fileSystemOps,
        strategy = strategy,
        options = eu.darken.butler.common.files.operations.TransferStrategy.Options(),
        onIssue = onIssue
    )
}

/**
 * SAFPath delete operation using the generic framework.
 *
 * Supports recursive deletion with progress tracking and error handling.
 *
 * ## Usage
 *
 * ```kotlin
 * val result = setOf(path1, path2).delete(
 *     fileSystemOps = safFileSystemOps,
 *     recursive = true,
 *     ignoreMissing = true,
 *     onProgress = { progress -> /* update UI */ },
 *     onIssue = { issue -> /* handle errors */ }
 * )
 * ```
 */
suspend fun SAFPath.delete(
    fileSystemOps: SAFFileSystemOps,
    recursive: Boolean = true,
    ignoreMissing: Boolean = true,
    onProgress: (suspend (DeleteAction.State.Progress<SAFPath, SAFPathLookup>) -> Unit)? = null,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
): DeleteAction.State.Result<SAFPath, SAFPathLookup> = setOf(this).delete(
    fileSystemOps, recursive, ignoreMissing, onProgress, onIssue
)

suspend fun Collection<SAFPath>.delete(
    fileSystemOps: SAFFileSystemOps,
    recursive: Boolean = true,
    ignoreMissing: Boolean = true,
    onProgress: (suspend (DeleteAction.State.Progress<SAFPath, SAFPathLookup>) -> Unit)? = null,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
): DeleteAction.State.Result<SAFPath, SAFPathLookup> {
    return this.deleteGeneric(
        fileSystemOps = fileSystemOps,
        recursive = recursive,
        ignoreMissing = ignoreMissing,
        onProgress = onProgress,
        onIssue = onIssue
    )
}
