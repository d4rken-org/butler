package eu.darken.butler.common.files.local.operations.core

import eu.darken.butler.common.files.actions.PathActionIssue
import kotlin.coroutines.cancellation.CancellationException

/**
 * Manages issue resolution and "apply to all" flags for path operations.
 *
 * Tracks user decisions across multiple file conflicts/issues during batch operations,
 * allowing users to apply a resolution (skip, overwrite, rename, etc.) to all
 * subsequent similar issues without being prompted again.
 */
class PathOperationIssueResolver(
    val onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?
) {

    // PathAlreadyExists resolution flags
    var skipAllPathExists = false
        private set
    var overwriteAllPathExists = false
        private set
    var mergeAllPathExists = false
        private set
    var renameSourceAllPathExists = false
        private set

    // Permission issue flags
    var skipAllPermission = false
        private set

    // Unknown error flags
    var skipAllUnknown = false
        private set

    /**
     * Resolves an issue by invoking the callback and updating "apply to all" flags.
     *
     * @param issue The issue to resolve
     * @return The resolution chosen by the user
     * @throws CancellationException if user cancels the operation
     */
    suspend fun resolveIssue(issue: PathActionIssue): PathActionIssue.Resolution {
        val resolution = onIssue?.invoke(issue)
            ?: throw IllegalStateException("No issue handler configured")

        // Update flags based on resolution type
        when (issue) {
            is PathActionIssue.PathAlreadyExists -> {
                when (val res = resolution as PathActionIssue.PathAlreadyExists.Resolution) {
                    is PathActionIssue.PathAlreadyExists.Resolution.Skip -> {
                        if (res.applyToAll) skipAllPathExists = true
                    }
                    is PathActionIssue.PathAlreadyExists.Resolution.Overwrite -> {
                        if (res.applyToAll) overwriteAllPathExists = true
                    }
                    is PathActionIssue.PathAlreadyExists.Resolution.Merge -> {
                        if (res.applyToAll) mergeAllPathExists = true
                    }
                    is PathActionIssue.PathAlreadyExists.Resolution.RenameSource -> {
                        if (res.applyToAll) renameSourceAllPathExists = true
                    }
                    is PathActionIssue.PathAlreadyExists.Resolution.Cancel -> {
                        throw CancellationException("User cancelled operation", res.error)
                    }
                    // RenameDestination intentionally doesn't have apply-to-all
                    else -> {}
                }
            }

            is PathActionIssue.InsufficientPermission -> {
                when (val res = resolution as PathActionIssue.InsufficientPermission.Resolution) {
                    is PathActionIssue.InsufficientPermission.Resolution.Skip -> {
                        if (res.applyToAll) skipAllPermission = true
                    }
                    is PathActionIssue.InsufficientPermission.Resolution.Cancel -> {
                        throw CancellationException("User cancelled operation", res.error)
                    }
                }
            }

            is PathActionIssue.UnknownError -> {
                when (val res = resolution as PathActionIssue.UnknownError.Resolution) {
                    is PathActionIssue.UnknownError.Resolution.Skip -> {
                        if (res.applyToAll) skipAllUnknown = true
                    }
                    is PathActionIssue.UnknownError.Resolution.Cancel -> {
                        throw CancellationException("User cancelled operation", res.error)
                    }
                    // Retry doesn't support apply-to-all
                    else -> {}
                }
            }

            is PathActionIssue.InsufficientSpace -> {
                // InsufficientSpace resolutions don't support apply-to-all
                if (resolution is PathActionIssue.InsufficientSpace.Resolution.Cancel) {
                    throw CancellationException("User cancelled operation", resolution.error)
                }
            }

            is PathActionIssue.TrashSizeLimitExceeded -> {
                // TrashSizeLimitExceeded is handled at CoreDeleteExecutor level, not here
                // But if it does come through, handle Cancel appropriately
                if (resolution is PathActionIssue.TrashSizeLimitExceeded.Resolution.Cancel) {
                    throw CancellationException("User cancelled operation", resolution.error)
                }
            }
        }

        return resolution
    }

    /**
     * Checks if permission issues should be automatically skipped.
     */
    fun shouldSkipPermission(): Boolean = skipAllPermission

    /**
     * Checks if unknown errors should be automatically skipped.
     */
    fun shouldSkipUnknown(): Boolean = skipAllUnknown

    /**
     * Checks if path conflicts should be automatically skipped.
     */
    fun shouldSkipPathExists(): Boolean = skipAllPathExists

    /**
     * Checks if path conflicts should be automatically overwritten.
     */
    fun shouldOverwritePathExists(): Boolean = overwriteAllPathExists

    /**
     * Checks if directory conflicts should be automatically merged.
     */
    fun shouldMergePathExists(): Boolean = mergeAllPathExists

    /**
     * Checks if path conflicts should be automatically renamed.
     */
    fun shouldRenameSource(): Boolean = renameSourceAllPathExists
}
