package eu.darken.butler.common.files.local

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.io.R
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import kotlin.coroutines.cancellation.CancellationException

private val TAG = logTag("Gateway", "Local", "Delete", "Extensions")

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
    log(TAG, DEBUG) {
        "delete(): Deleting $size targets (recursive=$recursive,onProgress=$onProgress, onIssue=$onIssue)"
    }

    val deleted = linkedSetOf<LocalPathLookup>()
    var bytesTotal = 0L

    var issueSkippAllPermission = false
    var issueSkippAllUnknown = false

    this.forEachIndexed { index, currentTopLevel ->
        log(TAG, VERBOSE) { "delete(): Processing target ${index + 1}/${this.size}: $currentTopLevel" }

        val toVisit = ArrayDeque<LocalPath>().apply { add(currentTopLevel) }
        val files = ArrayDeque<LocalPathLookup>()
        val dirsPost = ArrayDeque<LocalPathLookup>()

        while (toVisit.isNotEmpty() && currentCoroutineContext().isActive) {
            val localPath = toVisit.removeFirst()
            val lookup = localPath.performLookup()

            when (lookup.fileType) {
                FileType.SYMBOLIC_LINK, FileType.FILE -> {
                    files.addLast(lookup)
                    continue
                }
                FileType.DIRECTORY -> {
                    if (!recursive) {
                        files.addLast(lookup)
                        continue
                    }
                }
                FileType.UNKNOWN -> throw IllegalStateException("Unknown file type: $lookup")
            }

            try {
                val p = localPath.file.toPath()
                Files.newDirectoryStream(p).use { ds ->
                    for (child in ds) toVisit.addLast(LocalPath.build(child.toFile()))
                }
            } catch (e: IOException) {
                log(TAG, WARN) { "Cannot list directory: $lookup - ${e.message}" }
            }
            dirsPost.addFirst(lookup)
        }

        val totalItemsForTarget = files.size + dirsPost.size
        var itemsProcessed = 0

        suspend fun tryDelete(target: LocalPathLookup) {
            while (currentCoroutineContext().isActive) {
                try {
                    onProgress?.invoke(
                        DeleteAction.State.Progress(
                            target = target,
                            bytesCurrent = bytesTotal,
                            primaryProgress = eu.darken.butler.common.progress.Progress.Data(
                                primary = R.string.general_delete_progress_title.toCaString(currentTopLevel.name),
                                secondary = if (target == currentTopLevel) {
                                    R.string.general_delete_progress_processing_main.toCaString()
                                } else {
                                    R.string.general_delete_progress_processing_content.toCaString()
                                },
                                count = eu.darken.butler.common.progress.Progress.Count.Counter(
                                    current = index,
                                    max = this@delete.size
                                )
                            ),
                            secondaryProgress = if (totalItemsForTarget > 1) {
                                eu.darken.butler.common.progress.Progress.Data(
                                    primary = R.string.general_delete_progress_items_in_folder.toCaString(
                                        currentTopLevel.name
                                    ),
                                    secondary = target.userReadablePath,
                                    count = eu.darken.butler.common.progress.Progress.Count.Counter(
                                        current = itemsProcessed,
                                        max = totalItemsForTarget
                                    )
                                )
                            } else null
                        )
                    )
                    Files.delete(target.lookedUp.file.toPath())
                    bytesTotal += size
                    deleted += target
                    itemsProcessed++
                    break
                } catch (securityError: SecurityException) {
                    log(TAG, ERROR) { "delete(): Security exception on $target: $securityError" }

                    if (issueSkippAllPermission) {
                        log(TAG, INFO) { "Skipping permission issue (apply-to-all): $target" }
                        break
                    }

                    val deleteError = WriteException(path = target.lookedUp, cause = securityError)
                    if (onIssue == null) throw deleteError

                    val issue = PathActionIssue.InsufficientPermission(
                        destination = target,
                        exception = deleteError,
                    )

                    when (val resolution = onIssue.invoke(issue) as PathActionIssue.InsufficientPermission.Resolution) {
                        is PathActionIssue.InsufficientPermission.Resolution.Cancel -> throw CancellationException(
                            "User cancelled",
                            deleteError
                        )
                        is PathActionIssue.InsufficientPermission.Resolution.Skip -> {
                            if (resolution.applyToAll) issueSkippAllPermission = true
                            break
                        }
                    }
                } catch (deleteError: Exception) {
                    log(TAG, ERROR) { "delete(): Failed to delete $target: $deleteError" }

                    if (issueSkippAllUnknown) {
                        log(TAG, INFO) { "Skipping unknown issue (apply-to-all): $target" }
                        break
                    }

                    if (deleteError is NoSuchFileException) {
                        log(TAG, WARN) { "delete(): File doesn't exist: $target" }
                        if (ignoreMissing) break
                    }

                    val deleteError = WriteException(path = target.lookedUp, cause = deleteError)
                    if (onIssue == null) throw deleteError

                    val issue = PathActionIssue.UnknownError(
                        destination = target,
                        exception = deleteError
                    )

                    when (val resolution = onIssue.invoke(issue) as PathActionIssue.UnknownError.Resolution) {
                        is PathActionIssue.UnknownError.Resolution.Cancel -> throw CancellationException(
                            "User cancelled",
                            deleteError
                        )
                        is PathActionIssue.UnknownError.Resolution.Retry -> continue
                        is PathActionIssue.UnknownError.Resolution.Skip -> {
                            if (resolution.applyToAll) issueSkippAllUnknown = true
                            break
                        }
                    }
                }
            }
        }

        log(TAG, VERBOSE) { "delete(): Deleting ${files.size} files for target: $currentTopLevel" }
        for (localPath in files) tryDelete(localPath)

        log(TAG, VERBOSE) { "delete(): Deleting ${dirsPost.size} directories for target: $currentTopLevel" }
        for (dir in dirsPost) tryDelete(dir)
    }

    return DeleteAction.State.Result(
        deleted = deleted,
    )
}
