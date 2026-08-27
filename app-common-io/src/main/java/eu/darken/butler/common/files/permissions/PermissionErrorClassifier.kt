package eu.darken.butler.common.files.permissions

import eu.darken.butler.common.ElevatedAccessUnavailableException
import eu.darken.butler.common.error.causeChain
import eu.darken.butler.common.files.errors.PathException
import eu.darken.butler.common.files.errors.PathNotFoundException
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
        // Pass 3: Generic permission-style exception types without a more specific message.
        for (t in error.causeChain) {
            when (t) {
                // A path that isn't there is not a path we were kept out of.
                is PathNotFoundException -> return null
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
        // For IOException we trust the message directly (kernel-level errno strings).
        // For other types we only match if the message looks like a flattened cause chain
        // (e.g. RemoteException from Binder includes "Caused by: java.io.IOException: ...").
        val msg = message.orEmpty()
        val isFlattenedIo = msg.contains("Caused by:") &&
            msg.contains("java.io.IOException", ignoreCase = true)
        if (this !is IOException && !isFlattenedIo) return null

        val haystack = msg.lowercase()
        return when {
            "read-only file system" in haystack -> Reason.READONLY_FILESYSTEM
            "operation not permitted" in haystack -> Reason.NOT_PERMITTED
            "permission" in haystack -> Reason.ACCESS_DENIED
            else -> null
        }
    }
}
