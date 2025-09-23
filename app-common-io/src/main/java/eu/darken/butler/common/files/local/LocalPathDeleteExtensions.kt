package eu.darken.butler.common.files.local

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.errors.WriteException
import kotlinx.coroutines.currentCoroutineContext
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

    val toVisit = ArrayDeque<LocalPath>()
    val files = ArrayDeque<LocalPath>()
    val dirsPost = ArrayDeque<LocalPath>() // delete dirs after contents

    this.forEach { toVisit += it }

    while (toVisit.isNotEmpty() && currentCoroutineContext().isActive) {
        val localPath = toVisit.removeFirst()
        val p = localPath.file.toPath()

        val isDir = Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)
        val isSymlink = Files.isSymbolicLink(p)

        if (!isDir || isSymlink) {
            files.addLast(localPath) // files and symlinks get deleted directly
            continue
        }

        if (!recursive) {
            files.addLast(localPath) // treat as a file delete attempt (will fail if non-empty)
            continue
        }

        try {
            Files.newDirectoryStream(p).use { ds ->
                for (child in ds) toVisit.addLast(LocalPath.build(child.toFile()))
            }
        } catch (e: IOException) {
            log(TAG, WARN) { "Cannot list directory: $localPath - ${e.message}" }
            // Still try to delete the directory itself
        }
        dirsPost.addFirst(localPath) // post-order
    }

    val deleted = linkedSetOf<LocalPath>()
    var bytesTotal = 0L

    // Apply-to-all state management
    var skipAllPermissionIssues = false
    val pathsTotalCount = files.size + dirsPost.size

    suspend fun tryDelete(target: LocalPath) {
        while (currentCoroutineContext().isActive) {
            try {
                val size = if (target.file.isFile) target.file.length() else 0L
                onProgress?.invoke(
                    DeleteAction.State.Progress(
                        target = target,
                        targetSize = size,
                        pathsCurrent = deleted.size,
                        pathsTotal = pathsTotalCount,
                        bytesCurrent = bytesTotal,
                    )
                )
                Files.delete(target.file.toPath())
                bytesTotal += size
                deleted += target
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

    log(TAG, VERBOSE) { "delete(): Traversing done, deleting ${files.size} files..." }
    for (localPath in files) tryDelete(localPath)

    log(TAG, VERBOSE) { "delete(): File deletion done, deleting ${dirsPost.size} directories..." }
    for (dir in dirsPost) tryDelete(dir)

    return DeleteAction.State.Result(
        deleted = deleted,
        bytesTotal = bytesTotal,
    )
}
