package eu.darken.butler.common.files.local

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.operations.deleteGeneric
import kotlinx.coroutines.flow.Flow
import kotlin.time.Clock

fun LocalPath.delete(
    fileSystemOps: LocalFileSystemOps,
    recursive: Boolean = true,
    ignoreMissing: Boolean = true,
    progressClock: Clock = Clock.System,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null,
) = setOf(this).delete(fileSystemOps, recursive, ignoreMissing, progressClock, onIssue)

fun Collection<LocalPath>.delete(
    fileSystemOps: LocalFileSystemOps,
    recursive: Boolean = true,
    ignoreMissing: Boolean = true,
    progressClock: Clock = Clock.System,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null,
): Flow<DeleteAction.State<LocalPath, LocalPathLookup>> = this.deleteGeneric(
    fileSystemOps = fileSystemOps,
    recursive = recursive,
    ignoreMissing = ignoreMissing,
    onIssue = onIssue,
    progressClock = progressClock,
)
