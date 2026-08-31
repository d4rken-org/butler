package eu.darken.butler.common.files.permissions

import eu.darken.butler.common.ElevatedAccessUnavailableException
import eu.darken.butler.common.error.causeChain
import eu.darken.butler.common.files.errors.PathAlreadyExistsException
import eu.darken.butler.common.files.errors.PathException
import eu.darken.butler.common.files.errors.PathGoneError
import eu.darken.butler.common.files.errors.PathPermissionDeniedException
import eu.darken.butler.common.files.errors.PathPermissionDeniedException.Reason
import java.io.IOException

/**
 * Centralised "is this a permission failure?" classification.
 *
 * Single source of truth — replaces the duplicated `isPermissionError()` checks in
 * `LocalGateway`, `TransferErrorHandler`, `SaveFilesOperation`, and the ad-hoc
 * `error.message?.contains("permissions")` check in `SearcherWorkspaceViewModel`.
 *
 * Walks the entire `causeChain` so wrapper exceptions like
 * `WriteException(cause = IOException("Read-only file system"))` are recognised, and matches
 * both `message` and `toString()` so IPC-flattened `RemoteException` text (which appends the
 * cause as `Caused by: ...` lines) is also picked up.
 */
object PermissionErrorClassifier {

    fun classify(error: Throwable): Reason? {
        // Pass 1: Our own typed exceptions are authoritative — their reason is set deliberately
        // and message-matching against them would re-classify (their message contains "permission").
        for (t in error.causeChain) {
            if (t is PathPermissionDeniedException) return t.reason
            if (t is ElevatedAccessUnavailableException) return Reason.NO_MECHANISM
        }
        // Pass 2: Specific kernel-level reason found anywhere in the chain. Wins over generic
        // wrappers like WriteException that would otherwise mask an inner EROFS as ACCESS_DENIED.
        for (t in error.causeChain) {
            val r = t.matchMessage()
            if (r != null) return r
        }
        // Pass 3: Positive evidence of a different failure beats pass 4 inferring a denial from a
        // generic wrapper. A named non-permission kernel error anywhere in the chain settles it,
        // and so does the PathAlreadyExistsException type: "the target is already there" is as
        // plain a non-denial as the errno strings are. Escalation changes only for failures root
        // access cannot fix anyway: it frees no disk space and unmakes no existing file.
        for (t in error.causeChain) {
            if (t is PathAlreadyExistsException) return null
            if (t.matchesNonPermissionError()) return null
        }
        // Pass 4: Generic permission-style exception types without a more specific message.
        for (t in error.causeChain) {
            when (t) {
                // A path that isn't there is not a path we were kept out of. Keyed on the marker
                // rather than one concrete type, so a gone-error that is also a PathException does
                // not fall through to the denial below. Like every branch in this loop it answers
                // for the first chain link that matches, so a marker nested UNDER a generic
                // PathException wrapper does not veto - the wrapper is reached first. No producer
                // wraps one today; both are thrown at the top level.
                is PathGoneError -> return null
                is PathException -> return Reason.ACCESS_DENIED
                is SecurityException -> return Reason.ACCESS_DENIED
                is java.nio.file.AccessDeniedException -> return Reason.ACCESS_DENIED
                is kotlin.io.AccessDeniedException -> return Reason.ACCESS_DENIED
            }
        }
        return null
    }

    fun isPermissionError(error: Throwable): Boolean = classify(error) != null

    private fun Throwable.matchMessage(): Reason? {
        val haystack = errnoHaystack(message) ?: return null
        return when {
            "read-only file system" in haystack -> Reason.READONLY_FILESYSTEM
            "operation not permitted" in haystack -> Reason.NOT_PERMITTED
            "permission" in haystack -> Reason.ACCESS_DENIED
            else -> null
        }
    }

    private fun Throwable.matchesNonPermissionError(): Boolean {
        val haystack = suppressionHaystack() ?: return false
        return NON_PERMISSION_ERRORS.any { it in haystack }
    }

    /**
     * Suppression only ever removes a verdict, so it matches on text a file name cannot reach —
     * otherwise a denial on a path called "file exists" would silence itself.
     * [java.nio.file.FileSystemException] keeps the errno in `reason`, while its message also
     * renders the file and otherFile it failed on.
     */
    private fun Throwable.suppressionHaystack(): String? = when (this) {
        is java.nio.file.FileSystemException -> reason?.lowercase()
        else -> errnoHaystack(pathlessMessage())
    }

    /** [PathException] appends ` <-> path` to its message, and no errno lives in that suffix. */
    private fun Throwable.pathlessMessage(): String? {
        val path = (this as? PathException)?.path ?: return message
        return message?.removeSuffix(" <-> ${path.path}")
    }

    private fun Throwable.errnoHaystack(text: String?): String? {
        // For IOException we trust the message directly (kernel-level errno strings).
        // For other types we only match if the message looks like a flattened cause chain
        // (e.g. RemoteException from Binder includes "Caused by: java.io.IOException: ...").
        val msg = text.orEmpty()
        val isFlattenedIo = msg.contains("Caused by:") &&
            msg.contains("java.io.IOException", ignoreCase = true)
        if (this !is IOException && !isFlattenedIo) return null

        return msg.lowercase()
    }

    /** Failures the kernel names, none of which is us being kept out. */
    private val NON_PERMISSION_ERRORS = listOf(
        "no space left on device",
        "disk quota exceeded",
        "input/output error",
        "invalid cross-device link",
        "file exists",
    )
}
