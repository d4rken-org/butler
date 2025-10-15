package eu.darken.butler.common.files.local.operations.scanning

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.local.LocalFileSystemOps
import eu.darken.butler.common.files.local.operations.core.PathOperationIssueResolver
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.io.IOException

/**
 * Validates available disk space before performing file operations.
 *
 * Checks if the destination has enough free space to accommodate the operation,
 * and handles InsufficientSpace issues by allowing the user to retry after
 * freeing up space or cancel the operation.
 */
class SpaceValidator(
    private val fileSystemOps: LocalFileSystemOps,
    private val issueResolver: PathOperationIssueResolver
) {

    /**
     * Validates that the destination has sufficient space.
     *
     * If insufficient space is detected, shows a dialog allowing the user to:
     * - Retry after freeing up space
     * - Cancel the operation
     *
     * @param destination The destination path
     * @param requiredBytes Number of bytes needed
     * @param sources Collection of source paths (used for error reporting)
     * @throws kotlin.coroutines.cancellation.CancellationException if user cancels
     */
    suspend fun validateSpace(
        destination: LocalPath,
        requiredBytes: Long,
        sources: Collection<LocalPath>
    ) {
        log(TAG, DEBUG) { "Validating space: need $requiredBytes bytes" }

        while (currentCoroutineContext().isActive) {
            // For rename operations (single source with same parent directory),
            // check space on parent directory instead of non-existent destination
            val isRename = sources.size == 1 &&
                sources.first().file.parentFile?.absolutePath == destination.file.parentFile?.absolutePath
            val spaceCheckFile = if (isRename) {
                destination.file.absoluteFile.parentFile ?: destination.file
            } else {
                destination.file
            }

            @Suppress("UsableSpace")
            val availableSpace = spaceCheckFile.usableSpace

            log(
                TAG,
                DEBUG
            ) { "Space check: need $requiredBytes bytes, available $availableSpace bytes (from $spaceCheckFile)" }

            if (requiredBytes > availableSpace) {
                log(TAG, WARN) { "Insufficient space: need $requiredBytes, have $availableSpace" }

                val spaceError = WriteException(
                    path = destination,
                    cause = IOException("Insufficient space: need $requiredBytes bytes, available $availableSpace bytes")
                )

                // TODO why are we doing extra look ups here, can we already pass the looked up data in here?
                val sourceLookup = if (sources.size == 1) {
                    fileSystemOps.lookup(sources.first())
                } else {
                    fileSystemOps.lookup(destination)
                }

                // For rename operations (single source + file-like destination),
                // destination doesn't exist yet - use parent directory for lookup instead
                val destinationLookup = if (isRename) {
                    // TODO why not use LocalPath.parent?
                    val parent = destination.file.absoluteFile.parentFile
                    if (parent != null && parent.exists()) {
                        fileSystemOps.lookup(LocalPath.build(parent))
                    } else {
                        // No parent or doesn't exist? Fall back to destination
                        fileSystemOps.lookup(destination)
                    }
                } else {
                    fileSystemOps.lookup(destination)
                }

                val issue = PathActionIssue.InsufficientSpace(
                    source = sourceLookup,
                    destination = destinationLookup,
                )

                when (issueResolver.resolveIssue(issue) as PathActionIssue.InsufficientSpace.Resolution) {
                    is PathActionIssue.InsufficientSpace.Resolution.Retry -> {
                        log(TAG, DEBUG) { "Retrying space check..." }
                        continue
                    }
                    is PathActionIssue.InsufficientSpace.Resolution.Cancel -> {
                        throw kotlin.coroutines.cancellation.CancellationException(
                            "Insufficient space",
                            spaceError
                        )
                    }
                }
            } else {
                log(TAG, DEBUG) { "Space check passed" }
                break
            }
        }
    }

    companion object {
        private val TAG = logTag("PathOperation", "SpaceValidator")
    }
}
