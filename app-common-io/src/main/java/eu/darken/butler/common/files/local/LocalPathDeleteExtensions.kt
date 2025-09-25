package eu.darken.butler.common.files.local

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.io.R
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import kotlin.coroutines.cancellation.CancellationException

private val TAG = logTag("Gateway", "Local", "Delete", "Extensions")

suspend fun LocalPath.delete(
    recursive: Boolean = true,
    ignoreMissing: Boolean = true,
    onProgress: (suspend (DeleteAction.State.Progress<LocalPath>) -> Unit)? = null,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
) = setOf(this).delete(recursive, ignoreMissing, onProgress, onIssue)

suspend fun Collection<LocalPath>.delete(
    recursive: Boolean = true,
    ignoreMissing: Boolean = true,
    onProgress: (suspend (DeleteAction.State.Progress<LocalPath>) -> Unit)? = null,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
): DeleteAction.State.Result<LocalPath> {
    log(TAG, DEBUG) {
        "delete(): Deleting $size targets (recursive=$recursive,onProgress=$onProgress, onIssue=$onIssue)"
    }

    val deleted = linkedSetOf<LocalPath>()
    var bytesTotal = 0L

    // Apply-to-all state management
    var skipAllPermissionIssues = false

    // Process each original target separately for cleaner progress tracking
    this.forEachIndexed { index, currentTopLevel ->
        log(TAG, VERBOSE) { "delete(): Processing target ${index + 1}/${this.size}: $currentTopLevel" }

        // Collect all items for this specific target
        val toVisit = ArrayDeque<LocalPath>().apply { add(currentTopLevel) }
        val files = ArrayDeque<LocalPath>()
        val dirsPost = ArrayDeque<LocalPath>()

        // Traverse this target's tree
        while (toVisit.isNotEmpty() && currentCoroutineContext().isActive) {
            val localPath = toVisit.removeFirst()
            val p = localPath.file.toPath()

            val isDir = Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)
            val isSymlink = Files.isSymbolicLink(p)

            if (!isDir || isSymlink) {
                files.addLast(localPath)
                continue
            }

            if (!recursive) {
                files.addLast(localPath)
                continue
            }

            try {
                Files.newDirectoryStream(p).use { ds ->
                    for (child in ds) toVisit.addLast(LocalPath.build(child.toFile()))
                }
            } catch (e: IOException) {
                log(TAG, WARN) { "Cannot list directory: $localPath - ${e.message}" }
            }
            dirsPost.addFirst(localPath)
        }

        val totalItemsForTarget = files.size + dirsPost.size
        var itemsProcessed = 0

        suspend fun tryDelete(target: LocalPath) {
            while (currentCoroutineContext().isActive) {
                try {
                    val size = if (target.file.isFile) target.file.length() else 0L

                    onProgress?.invoke(
                        DeleteAction.State.Progress(
                            target = target,
                            targetSize = size,
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
                    delay(50) // FIXME Just for testing
//                Files.delete(target.file.toPath()) // FIXME Just for testing.
                    bytesTotal += size
                    deleted += target
                    itemsProcessed++
                    break
                } catch (e: NoSuchFileException) {
                    log(TAG, WARN) { "delete(): File doesn't exist: $target" }
                    if (ignoreMissing) break else throw ReadException(path = target, cause = e)

                } catch (securityError: SecurityException) {
                    log(TAG, ERROR) { "delete(): Security exception on $target: $securityError" }

                    if (skipAllPermissionIssues) {
                        log(TAG, INFO) { "Skipping permission issue (apply-to-all): $target" }
                        break
                    }

                    val deleteError = WriteException(path = target, cause = securityError)

                    if (onIssue == null) throw deleteError

                    val issue = try {
                        PathActionIssue.InsufficientPermission(
                            destination = target.performLookup(),
                            exception = deleteError,
                        )
                    } catch (e: Exception) {
                        PathActionIssue.UnknownError(exception = e)
                    }

                    val resolution = onIssue.invoke(issue)
                    when (issue) {
                        is PathActionIssue.InsufficientPermission -> {
                            val permissionResolution = resolution as PathActionIssue.InsufficientPermission.Resolution
                            when (permissionResolution) {
                                is PathActionIssue.InsufficientPermission.Resolution.Cancel -> throw CancellationException(
                                    "User cancelled",
                                    deleteError
                                )
                                is PathActionIssue.InsufficientPermission.Resolution.Skip -> {
                                    if (permissionResolution.applyToAll) skipAllPermissionIssues = true
                                    break
                                }
                            }
                        }
                        is PathActionIssue.UnknownError -> {
                            val unknownResolution = resolution as PathActionIssue.UnknownError.Resolution
                            when (unknownResolution) {
                                is PathActionIssue.UnknownError.Resolution.Cancel -> throw CancellationException(
                                    "User cancelled",
                                    deleteError
                                )
                                is PathActionIssue.UnknownError.Resolution.Retry -> continue
                                is PathActionIssue.UnknownError.Resolution.Skip -> break
                            }
                        }
                        else -> throw IllegalStateException("Unexpected issue type: $issue")
                    }
                } catch (deleteError: Exception) {
                    log(TAG, ERROR) { "delete(): Failed to delete $target: $deleteError" }
                    if (onIssue == null) throw deleteError

                    val issue = try {
                        PathActionIssue.UnknownError(
                            destination = target.performLookup(),
                            exception = deleteError
                        )
                    } catch (e: Exception) {
                        PathActionIssue.UnknownError(exception = e)
                    }

                    val resolution = onIssue.invoke(issue) as PathActionIssue.UnknownError.Resolution
                    when (resolution) {
                        is PathActionIssue.UnknownError.Resolution.Cancel -> throw CancellationException(
                            "User cancelled",
                            deleteError
                        )
                        is PathActionIssue.UnknownError.Resolution.Retry -> continue
                        is PathActionIssue.UnknownError.Resolution.Skip -> break
                    }
                }
            }
        }

        // Delete files and directories for this target
        log(TAG, VERBOSE) { "delete(): Deleting ${files.size} files for target: $currentTopLevel" }
        for (localPath in files) tryDelete(localPath)

        log(TAG, VERBOSE) { "delete(): Deleting ${dirsPost.size} directories for target: $currentTopLevel" }
        for (dir in dirsPost) tryDelete(dir)
    }

    return DeleteAction.State.Result(
        deleted = deleted,
        bytesTotal = bytesTotal,
    )
}
