package eu.darken.butler.common.files.local

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.PathActionIssue

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
): DeleteAction.State.Result<LocalPath, LocalPathLookup> {
    return LocalPathDelete(
        targets = this,
        recursive = recursive,
        ignoreMissing = ignoreMissing,
        onProgress = onProgress,
        onIssue = onIssue
    ).execute()
}
