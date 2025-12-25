package eu.darken.butler.common.files.operations

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.error.causeChain
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.errors.PathException
import eu.darken.butler.common.files.local.operations.core.PathOperationIssueResolver
import eu.darken.butler.common.files.local.operations.core.PathOperationProgressTracker
import java.io.IOException

/**
 * Shared error handling utilities for path operations (copy, move, delete).
 *
 * Provides consistent error categorization, "apply to all" checking, and
 * issue resolution across all file operations, eliminating code duplication.
 *
 * ## Error Categories
 *
 * - **Permission errors**: ReadException, WriteException, SecurityException, AccessDeniedException
 * - **Unknown errors**: All other exceptions
 *
 * ## "Apply to All" Support
 *
 * Checks issueResolver flags to automatically skip errors without prompting user:
 * - skipAllPermission: Auto-skip permission errors
 * - skipAllUnknown: Auto-skip unknown errors
 *
 * ## Usage Pattern
 *
 * ```kotlin
 * val errorHandler = TransferErrorHandler()
 * try {
 *     // operation
 * } catch (e: Exception) {
 *     errorHandler.handleError(
 *         error = e,
 *         lookup = sourceLookup,
 *         issueResolver = issueResolver,
 *         progressTracker = progressTracker,
 *         onSkip = { skipped.add(it) },
 *         onRetry = { workQueue.addFirst(originalItem) }
 *     )
 * }
 * ```
 */
class TransferErrorHandler {

    /**
     * Checks if an exception represents a permission/access error.
     *
     * @return true if error is due to insufficient permissions
     */
    private fun Exception.isPermissionError(): Boolean =
        causeChain.any {
            it is PathException ||
                it is SecurityException ||
                it is java.nio.file.AccessDeniedException ||
                it is AccessDeniedException ||
                (it is IOException && it.message?.contains("permission", ignoreCase = true) == true)
        }

    /**
     * Checks "apply to all" error flags and executes skip if applicable.
     *
     * For scan errors: Since scan errors always use UnknownError issues (even for permission errors)
     * to support Retry, we check skipAllUnknown for both permission and unknown errors.
     *
     * @return true if error was auto-skipped, false if needs user resolution
     */
    private fun <P : APath<P>, PL : APathLookup<P>> checkApplyToAllErrorFlags(
        error: Exception,
        lookup: PL,
        issueResolver: PathOperationIssueResolver,
        onSkip: (PL) -> Unit,
        onComplete: (() -> Unit)? = null,
        tag: String,
        isScanError: Boolean = false
    ): Boolean {
        val isPermissionError = error.isPermissionError()

        when {
            isPermissionError && issueResolver.skipAllPermission -> {
                log(tag, INFO) { "Skipping permission error (apply-to-all): ${lookup.lookedUp}" }
                onSkip(lookup)
                onComplete?.invoke()
                return true
            }
            issueResolver.skipAllUnknown -> {
                // For scan errors, skipAllUnknown applies to both permission and unknown errors
                // because scan errors always use UnknownError issues
                // For other errors, skipAllUnknown only applies to non-permission errors
                if (isScanError || !isPermissionError) {
                    log(
                        tag,
                        INFO
                    ) { "Skipping ${if (isPermissionError) "permission" else "unknown"} error (apply-to-all): ${lookup.lookedUp}" }
                    onSkip(lookup)
                    onComplete?.invoke()
                    return true
                }
            }
        }
        return false
    }

    /**
     * Handles errors during path operations with consistent behavior.
     *
     * Flow:
     * 1. Categorize error (permission vs unknown)
     * 2. Check "apply to all" flags for fast-path skip
     * 3. If no issue handler, throw wrapped exception
     * 4. Create appropriate PathActionIssue
     * 5. Resolve via user callback
     * 6. Execute resolution action
     *
     * @param P Path type
     * @param PL Path lookup type
     * @param error The exception that occurred
     * @param sourceLookup Source path lookup (what we're copying/moving from, or deleting)
     * @param destinationPath Destination path (for copy/move operations, null for delete)
     * @param issueResolver Resolver for user decisions and "apply to all" flags
     * @param progressTracker Progress tracker to update on skip/complete
     * @param onSkip Callback when error is skipped (receives the source lookup)
     * @param onRetry Callback when error should be retried (null if retry not supported)
     * @param canRetry Whether retry is supported for this operation
     * @param onIssue User callback for resolving issues (null if no handler)
     * @param tag Logging tag for debug messages
     * @throws Exception if no issue handler and error not auto-skipped
     * @throws kotlin.coroutines.cancellation.CancellationException if user cancels
     */
    suspend fun <P : APath<P>, PL : APathLookup<P>> handleError(
        error: Exception,
        sourceLookup: PL,
        destinationPath: APath<*>? = null,
        issueResolver: PathOperationIssueResolver,
        progressTracker: PathOperationProgressTracker,
        onSkip: (PL) -> Unit,
        onRetry: (() -> Unit)?,
        canRetry: Boolean = onRetry != null,
        onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?,
        tag: String
    ) {
        val errorPath = destinationPath ?: sourceLookup.lookedUp
        log(tag, ERROR) { "Operation failed: $errorPath - $error" }

        // Fast path: Check "apply to all" flags
        if (checkApplyToAllErrorFlags(error, sourceLookup, issueResolver, onSkip, progressTracker::completeItem, tag)) {
            return
        }

        // No issue handler? Re-throw original exception
        if (onIssue == null) throw error

        // Create appropriate issue based on error type
        val isPermissionError = error.isPermissionError()
        val issue = if (isPermissionError) {
            PathActionIssue.InsufficientPermission(
                source = sourceLookup,
                destinationPath = errorPath,
                exception = eu.darken.butler.common.files.errors.WriteException(
                    path = errorPath,
                    cause = error
                ),
                canSkip = true
            )
        } else {
            PathActionIssue.UnknownError(
                source = sourceLookup,
                destinationPath = errorPath,
                exception = eu.darken.butler.common.files.errors.WriteException(
                    path = errorPath,
                    cause = error
                ),
                canRetry = canRetry,
                canSkip = true
            )
        }

        // Resolve issue with user callback (may throw CancellationException)
        val resolution = issueResolver.resolveIssue(issue)

        // Handle resolution
        when (resolution) {
            is PathActionIssue.InsufficientPermission.Resolution.Skip,
            is PathActionIssue.UnknownError.Resolution.Skip -> {
                log(tag, INFO) { "User chose to skip: ${sourceLookup.lookedUp}" }
                onSkip(sourceLookup)
                progressTracker.completeItem()
            }

            is PathActionIssue.UnknownError.Resolution.Retry -> {
                if (onRetry != null) {
                    log(tag, INFO) { "Retrying operation: ${sourceLookup.lookedUp}" }
                    onRetry()
                } else {
                    log(tag, WARN) { "Retry requested but not supported, skipping: ${sourceLookup.lookedUp}" }
                    onSkip(sourceLookup)
                    progressTracker.completeItem()
                }
            }

            else -> {
                // Cancel is handled by issueResolver.resolveIssue() throwing CancellationException
            }
        }
    }

    /**
     * Handles scan errors specifically.
     *
     * Scan errors use UnknownError even for permission issues because:
     * 1. InsufficientPermission doesn't support Retry resolution
     * 2. Scan errors can potentially be retried if permissions are fixed externally
     *
     * @param P Path type
     * @param PL Path lookup type
     * @param error The exception that occurred
     * @param lookup Lookup information for the path that failed
     * @param issueResolver Resolver for user decisions and "apply to all" flags
     * @param onSkip Callback when error is skipped (receives the lookup)
     * @param onRetry Callback when error should be retried
     * @param onIssue User callback for resolving issues (null if no handler)
     * @param tag Logging tag for debug messages
     * @throws Exception if no issue handler and error not auto-skipped
     * @throws kotlin.coroutines.cancellation.CancellationException if user cancels
     */
    suspend fun <P : APath<P>, PL : APathLookup<P>> handleScanError(
        error: Exception,
        lookup: PL,
        issueResolver: PathOperationIssueResolver,
        onSkip: (PL) -> Unit,
        onRetry: () -> Unit,
        onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?,
        tag: String
    ) {
        log(tag, ERROR) { "Scan error: ${lookup.lookedUp} - $error" }

        // Fast path: Check "apply to all" flags (with isScanError=true for proper flag checking)
        if (checkApplyToAllErrorFlags(
                error,
                lookup,
                issueResolver,
                onSkip,
                onComplete = null,
                tag,
                isScanError = true
            )
        ) {
            return
        }

        // No issue handler? Throw exception
        if (onIssue == null) throw error

        // For scan errors, always use UnknownError (even for permission errors)
        // because InsufficientPermission doesn't support Retry, but scan errors
        // can potentially be retried if permissions are fixed externally
        val issue = PathActionIssue.UnknownError(
            destinationPath = lookup.lookedUp,
            exception = error,
            canRetry = true,
            canSkip = true
        )

        // Resolve issue with user callback (may throw CancellationException)
        val resolution = issueResolver.resolveIssue(issue)

        // Handle resolution (only UnknownError for scan errors)
        when (resolution) {
            is PathActionIssue.UnknownError.Resolution.Skip -> {
                log(tag, INFO) { "User chose to skip scan: ${lookup.lookedUp}" }
                onSkip(lookup)
            }

            is PathActionIssue.UnknownError.Resolution.Retry -> {
                log(tag, INFO) { "Retrying scan operation: ${lookup.lookedUp}" }
                onRetry()
            }

            is PathActionIssue.UnknownError.Resolution.Cancel -> {
                // Already thrown by resolveIssue
            }
        }
    }
}
