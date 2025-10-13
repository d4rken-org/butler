package eu.darken.butler.common.files.local.operations.core

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.errors.WriteException
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException

/**
 * Unified error handling for path operations.
 *
 * Handles common I/O errors (permission denied, file not found, etc.) and delegates
 * to the IssueResolver for user interaction when needed.
 *
 * Supports "apply to all" behavior to avoid repeated prompts for similar errors.
 */
class PathOperationErrorHandler(
    private val issueResolver: PathOperationIssueResolver,
    private val onItemSkipped: (suspend (APathLookup<*>) -> Unit)? = null
) {

    /**
     * Context for error handling operations
     */
    sealed class ErrorContext {
        abstract val lookup: APathLookup<*>
        abstract val operation: String

        data class Read(
            override val lookup: APathLookup<*>,
            override val operation: String,
        ) : ErrorContext()

        data class Write(
            override val lookup: APathLookup<*>,
            val destPath: APath<*>,
            override val operation: String,
        ) : ErrorContext()
    }

    /**
     * Handles permission-related errors (SecurityException, AccessDeniedException).
     *
     * If "skip all permission issues" is enabled, automatically skips the item.
     * Otherwise, shows a permission denied dialog to the user.
     *
     * @param error The permission error that occurred
     * @param context Context about the operation that failed
     * @throws WriteException if no issue handler is configured
     */
    suspend fun handlePermissionError(
        error: Exception,
        context: ErrorContext,
    ) {
        val path = when (context) {
            is ErrorContext.Read -> context.lookup.lookedUp
            is ErrorContext.Write -> context.destPath
        }

        log(TAG, ERROR) { "${context.operation} - Permission denied: $path - $error" }

        if (issueResolver.shouldSkipPermission()) {
            log(TAG, INFO) { "Skipping permission issue (apply-to-all): $path" }
            onItemSkipped?.invoke(context.lookup)
            return
        }

        val exception = when (context) {
            is ErrorContext.Read -> ReadException(context.operation, context.lookup.lookedUp, error)
            is ErrorContext.Write -> WriteException(path = context.destPath, cause = error)
        }

        val issue = PathActionIssue.InsufficientPermission(
            destination = context.lookup,
            exception = exception,
            canSkip = true,
        )

        issueResolver.resolveIssue(issue)
    }

    /**
     * Handles unknown I/O errors.
     *
     * If "skip all unknown errors" is enabled, automatically skips the item.
     * Otherwise, shows an error dialog with retry/skip options.
     *
     * @param error The unknown error that occurred
     * @param context Context about the operation that failed
     * @param canRetry Whether the operation can be retried
     * @throws WriteException if no issue handler is configured
     */
    suspend fun handleUnknownError(
        error: Exception,
        context: ErrorContext,
        canRetry: Boolean = true,
    ) {
        val path = when (context) {
            is ErrorContext.Read -> context.lookup.lookedUp
            is ErrorContext.Write -> context.destPath
        }

        log(TAG, ERROR) { "${context.operation} failed: $path - $error" }

        // Special handling for missing files
        if (error is NoSuchFileException) {
            log(TAG, WARN) { "File disappeared: $path" }
            // Caller may want to ignore missing files, so don't automatically skip
        }

        if (issueResolver.shouldSkipUnknown()) {
            log(TAG, INFO) { "Skipping unknown issue (apply-to-all): $path" }
            onItemSkipped?.invoke(context.lookup)
            return
        }

        val exception = when (context) {
            is ErrorContext.Read -> ReadException(context.operation, context.lookup.lookedUp, error)
            is ErrorContext.Write -> WriteException(path = context.destPath, cause = error)
        }

        val destLookup = when (context) {
            is ErrorContext.Read -> context.lookup
            is ErrorContext.Write -> {
                // Try to get actual destination lookup if it exists
                try {
                    // This would need to be provided by caller if we want accurate info
                    context.lookup
                } catch (e: Exception) {
                    context.lookup
                }
            }
        }

        val issue = PathActionIssue.UnknownError(
            destination = destLookup,
            exception = exception,
            canRetry = canRetry,
            canSkip = true
        )

        issueResolver.resolveIssue(issue)
    }

    /**
     * Executes a block with automatic error handling.
     *
     * Catches common I/O exceptions and delegates to appropriate handlers.
     *
     * @param operation Description of the operation for logging
     * @param context Error context (read/write, paths, etc.)
     * @param block The operation to execute
     * @return Result of the operation (Success or Failure)
     */
    suspend fun <T> handleErrors(
        operation: String,
        context: ErrorContext,
        canRetry: Boolean = true,
        block: suspend () -> T
    ): Result<T> {
        return try {
            Result.success(block())
        } catch (e: SecurityException) {
            handlePermissionError(e, context)
            Result.failure(e)
        } catch (e: AccessDeniedException) {
            handlePermissionError(e, context)
            Result.failure(e)
        } catch (e: Exception) {
            handleUnknownError(e, context, canRetry)
            Result.failure(e)
        }
    }

    companion object {
        private val TAG = logTag("PathOperation", "ErrorHandler")
    }
}
