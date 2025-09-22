package eu.darken.butler.common.files.local

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.operations.DeleteOperation
import eu.darken.butler.common.files.operations.Issue
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import kotlin.coroutines.cancellation.CancellationException

private val TAG = logTag("Gateway", "Local", "Delete", "Extensions")

suspend fun Collection<LocalPath>.delete(
    recursive: Boolean = false,
    ignoreMissing: Boolean = false,
    onProgress: (suspend (DeleteOperation.State.Progress<LocalPath>) -> Unit)? = null,
    onIssue: (suspend (Issue) -> Issue.Resolution)? = null
): DeleteOperation.State.Result<LocalPath> {
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
                    DeleteOperation.State.Progress(
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
                    Issue.InsufficientPermission(
                        destination = target.performLookup(),
                        exception = deleteError,
                    )
                } catch (e: Exception) {
                    Issue.UnknownError(exception = e)
                }

                val resolution = onIssue.invoke(issue)
                when (issue) {
                    is Issue.InsufficientPermission -> {
                        val permissionResolution = resolution as Issue.InsufficientPermission.Resolution
                        when (permissionResolution) {
                            is Issue.InsufficientPermission.Resolution.Cancel -> throw CancellationException(
                                "User cancelled",
                                deleteError
                            )
                            is Issue.InsufficientPermission.Resolution.Skip -> {
                                if (permissionResolution.applyToAll) skipAllPermissionIssues = true
                                break
                            }
                        }
                    }
                    is Issue.UnknownError -> {
                        val unknownResolution = resolution as Issue.UnknownError.Resolution
                        when (unknownResolution) {
                            is Issue.UnknownError.Resolution.Cancel -> throw CancellationException(
                                "User cancelled",
                                deleteError
                            )
                            is Issue.UnknownError.Resolution.Retry -> continue
                            is Issue.UnknownError.Resolution.Skip -> break
                        }
                    }
                    else -> throw IllegalStateException("Unexpected issue type: $issue")
                }
            } catch (deleteError: Exception) {
                log(TAG, ERROR) { "delete(): Failed to delete $target: $deleteError" }
                if (onIssue == null) throw deleteError

                val issue = try {
                    Issue.UnknownError(
                        destination = target.performLookup(),
                        exception = deleteError
                    )
                } catch (e: Exception) {
                    Issue.UnknownError(exception = e)
                }

                val resolution = onIssue.invoke(issue) as Issue.UnknownError.Resolution
                when (resolution) {
                    is Issue.UnknownError.Resolution.Cancel -> throw CancellationException(
                        "User cancelled",
                        deleteError
                    )
                    is Issue.UnknownError.Resolution.Retry -> continue
                    is Issue.UnknownError.Resolution.Skip -> break
                }
            }
        }
    }

    log(TAG, VERBOSE) { "delete(): Traversing done, deleting ${files.size} files..." }
    for (localPath in files) tryDelete(localPath)

    log(TAG, VERBOSE) { "delete(): File deletio done, deleting ${dirsPost.size} directories..." }
    for (dir in dirsPost) tryDelete(dir)

    return DeleteOperation.State.Result(
        deleted = deleted,
        bytesTotal = bytesTotal,
    )
}
