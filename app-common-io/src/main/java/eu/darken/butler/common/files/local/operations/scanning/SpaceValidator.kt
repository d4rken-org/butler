package eu.darken.butler.common.files.local.operations.scanning

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.local.operations.core.PathOperationIssueResolver
import eu.darken.butler.common.files.local.performLookup
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
            @Suppress("UsableSpace")
            val availableSpace = destination.file.usableSpace

            log(TAG, DEBUG) { "Space check: need $requiredBytes bytes, available $availableSpace bytes" }

            if (requiredBytes > availableSpace) {
                log(TAG, WARN) { "Insufficient space: need $requiredBytes, have $availableSpace" }

                val spaceError = WriteException(
                    path = destination,
                    cause = IOException("Insufficient space: need $requiredBytes bytes, available $availableSpace bytes")
                )

                val sourceLookup = if (sources.size == 1) {
                    sources.first().performLookup()
                } else {
                    destination.performLookup()
                }

                val issue = PathActionIssue.InsufficientSpace(
                    source = sourceLookup,
                    destination = destination.performLookup(),
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
