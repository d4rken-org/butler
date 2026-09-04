package eu.darken.butler.common.files.local.ipc

import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.ipc.IpcClientModule
import eu.darken.butler.common.ipc.IpcErrorCodec
import eu.darken.butler.common.ipc.IpcHostModule
import eu.darken.butler.common.issue.Issue
import java.io.IOException
import kotlin.uuid.Uuid

/**
 * Conversion utilities between domain types (PathActionIssue) and IPC types (FileOperationIssue).
 * Shared across all file operations (Delete, Copy, Move).
 */

/**
 * Convert from domain PathActionIssue to IPC FileOperationIssue.
 * Used on host side before calling callback.
 *
 * Nothing here resolves a [eu.darken.butler.common.ca.CaString]: the host process may hold a context
 * whose resources are not ours (the root host runs on the framework's system context), so the text
 * the user sees is built on the client from the transported exception.
 */
fun PathActionIssue.toFileOperationIssue(): FileOperationIssue {
    return when (this) {
        is PathActionIssue.InsufficientPermission -> FileOperationIssue(
            issueId = id.id.toString(),
            issueType = FileOperationIssue.IssueType.PERMISSION_DENIED,
            sourcePath = source as? LocalPathLookup,
            destinationPath = destinationPath as LocalPath,
            // Compact: nothing renders a stack for a permission issue.
            error = exception?.let { IpcErrorCodec.encodeCompact(it) },
            canSkip = canSkip,
        )

        is PathActionIssue.PathAlreadyExists -> FileOperationIssue(
            issueId = id.id.toString(),
            issueType = FileOperationIssue.IssueType.PATH_ALREADY_EXISTS,
            sourcePath = source as? LocalPathLookup,
            destinationPath = destination.lookedUp as LocalPath,
            destinationLookup = destination as LocalPathLookup,
            canSkip = canSkip,
            canOverwrite = canOverwrite,
            canMerge = canMerge,
            canRenameSource = canRenameSource,
            canRenameDestination = canRenameDestination,
            suggestedName = suggestedName,
        )

        is PathActionIssue.InsufficientSpace -> FileOperationIssue(
            issueId = id.id.toString(),
            issueType = FileOperationIssue.IssueType.INSUFFICIENT_SPACE,
            sourcePath = source as LocalPathLookup,
            destinationPath = destinationPath as LocalPath,
            canRetry = true,
        )

        is PathActionIssue.UnknownError -> FileOperationIssue(
            issueId = id.id.toString(),
            issueType = FileOperationIssue.IssueType.UNKNOWN_ERROR,
            sourcePath = source as? LocalPathLookup,
            destinationPath = (destinationPath as? LocalPath) ?: (source?.lookedUp as? LocalPath)
                ?: throw IllegalArgumentException("UnknownError must have at least source or destinationPath"),
            // Full payload: the unknown-error sheet renders the host stack.
            error = IpcErrorCodec.encode(exception),
            canSkip = canSkip,
            canRetry = canRetry,
        )

        is PathActionIssue.TrashSizeLimitExceeded -> throw IllegalArgumentException(
            "TrashSizeLimitExceeded is not an IPC-transportable issue type"
        )

        is PathActionIssue.TrashMoveFailed -> throw IllegalArgumentException(
            "TrashMoveFailed is not an IPC-transportable issue type"
        )

        is PathActionIssue.TrashNotSupported -> throw IllegalArgumentException(
            "TrashNotSupported is not an IPC-transportable issue type"
        )

        is PathActionIssue.ArchivePasswordRequired -> throw IllegalArgumentException(
            "ArchivePasswordRequired is not an IPC-transportable issue type"
        )
    }
}

/**
 * Convert from IPC FileOperationIssue to domain PathActionIssue.
 * Used on client side when receiving issue from host.
 *
 * [PathActionIssue.UnknownError.errorMessage] is left at its default, so the description is derived
 * here from the rebuilt exception, in the user's locale. A producer-supplied `errorMessage` does not
 * survive the trip and would be silently replaced by that default.
 */
fun IpcClientModule.toPathActionIssue(issue: FileOperationIssue): PathActionIssue = with(issue) {
    val id = Issue.Id(id = Uuid.parse(issueId))

    when (issueType) {
        FileOperationIssue.IssueType.PERMISSION_DENIED -> PathActionIssue.InsufficientPermission(
            id = id,
            source = sourcePath,
            destinationPath = destinationPath,
            canSkip = canSkip,
            exception = decodeStreamError(error)
                ?: error?.let { WriteException(path = destinationPath, cause = IOException(it)) },
        )

        FileOperationIssue.IssueType.PATH_ALREADY_EXISTS -> PathActionIssue.PathAlreadyExists(
            id = id,
            source = sourcePath,
            destination = destinationLookup
                ?: throw IllegalArgumentException("PathAlreadyExists must have destinationLookup"),
            canSkip = canSkip,
            canOverwrite = canOverwrite,
            canMerge = canMerge,
            canRenameSource = canRenameSource,
            canRenameDestination = canRenameDestination,
            suggestedName = suggestedName,
        )

        FileOperationIssue.IssueType.INSUFFICIENT_SPACE -> PathActionIssue.InsufficientSpace(
            id = id,
            source = sourcePath!!,  // Source must be present for this issue type
            destinationPath = destinationPath,
        )

        FileOperationIssue.IssueType.UNKNOWN_ERROR -> PathActionIssue.UnknownError(
            id = id,
            source = sourcePath,
            destinationPath = destinationPath,
            exception = decodeStreamError(error) ?: IOException(error ?: "Unknown error"),
            canSkip = canSkip,
            canRetry = canRetry,
        )
    }
}

/**
 * Convert from domain PathActionIssue.Resolution to IPC FileOperationIssueResolution.
 * Used on client side before returning resolution from callback.
 */
@Suppress("CyclomaticComplexMethod", "ComplexMethod")
fun IpcClientModule.toFileOperationIssueResolution(
    resolution: PathActionIssue.Resolution,
): FileOperationIssueResolution {
    return when (resolution) {
        is PathActionIssue.PathAlreadyExists.Resolution.Skip ->
            FileOperationIssueResolution(
                resolutionType = FileOperationIssueResolution.ResolutionType.SKIP,
                applyToAll = resolution.applyToAll
            )

        is PathActionIssue.PathAlreadyExists.Resolution.Overwrite ->
            FileOperationIssueResolution(
                resolutionType = FileOperationIssueResolution.ResolutionType.OVERWRITE,
                applyToAll = resolution.applyToAll
            )

        is PathActionIssue.PathAlreadyExists.Resolution.Merge ->
            FileOperationIssueResolution(
                resolutionType = FileOperationIssueResolution.ResolutionType.MERGE,
                applyToAll = resolution.applyToAll
            )

        is PathActionIssue.PathAlreadyExists.Resolution.RenameSource ->
            FileOperationIssueResolution(
                resolutionType = FileOperationIssueResolution.ResolutionType.RENAME_SOURCE,
                applyToAll = resolution.applyToAll,
                newName = resolution.newName
            )

        is PathActionIssue.PathAlreadyExists.Resolution.RenameDestination ->
            FileOperationIssueResolution(
                resolutionType = FileOperationIssueResolution.ResolutionType.RENAME_DESTINATION,
                newName = resolution.newName
            )

        is PathActionIssue.PathAlreadyExists.Resolution.Cancel ->
            FileOperationIssueResolution(
                resolutionType = FileOperationIssueResolution.ResolutionType.CANCEL,
                cancelled = true,
                error = resolution.error?.encodeToPropagate(),
            )

        is PathActionIssue.InsufficientPermission.Resolution.Skip ->
            FileOperationIssueResolution(
                resolutionType = FileOperationIssueResolution.ResolutionType.SKIP,
                applyToAll = resolution.applyToAll
            )

        is PathActionIssue.InsufficientPermission.Resolution.Cancel ->
            FileOperationIssueResolution(
                resolutionType = FileOperationIssueResolution.ResolutionType.CANCEL,
                cancelled = true,
                error = resolution.error?.encodeToPropagate(),
            )

        is PathActionIssue.InsufficientSpace.Resolution.Retry ->
            FileOperationIssueResolution(
                resolutionType = FileOperationIssueResolution.ResolutionType.RETRY
            )

        is PathActionIssue.InsufficientSpace.Resolution.Cancel ->
            FileOperationIssueResolution(
                resolutionType = FileOperationIssueResolution.ResolutionType.CANCEL,
                cancelled = true,
                error = resolution.error?.encodeToPropagate(),
            )

        is PathActionIssue.UnknownError.Resolution.Skip ->
            FileOperationIssueResolution(
                resolutionType = FileOperationIssueResolution.ResolutionType.SKIP,
                applyToAll = resolution.applyToAll
            )

        is PathActionIssue.UnknownError.Resolution.Retry ->
            FileOperationIssueResolution(
                resolutionType = FileOperationIssueResolution.ResolutionType.RETRY
            )

        is PathActionIssue.UnknownError.Resolution.Cancel ->
            FileOperationIssueResolution(
                resolutionType = FileOperationIssueResolution.ResolutionType.CANCEL,
                cancelled = true,
                error = resolution.error?.encodeToPropagate(),
            )

        else -> throw IllegalArgumentException("Unknown resolution type: $resolution")
    }
}

/**
 * Convert from IPC FileOperationIssueResolution to domain PathActionIssue.Resolution.
 * Used on host side after receiving resolution from callback.
 *
 * @param issue Original issue (needed for type-specific resolution creation)
 */
@Suppress("CyclomaticComplexMethod")
fun IpcHostModule.toPathActionIssueResolution(
    resolution: FileOperationIssueResolution,
    issue: PathActionIssue,
): PathActionIssue.Resolution = with(resolution) {
    if (cancelled) {
        // Cancel takes an Exception, so a decoded Throwable that is none falls back to its text.
        val error = (decodeCallbackError(resolution.error) as? Exception)
            ?: resolution.error?.let { IOException(it) }
        return@with when (issue) {
            is PathActionIssue.PathAlreadyExists ->
                PathActionIssue.PathAlreadyExists.Resolution.Cancel(error)
            is PathActionIssue.InsufficientPermission ->
                PathActionIssue.InsufficientPermission.Resolution.Cancel(error)
            is PathActionIssue.InsufficientSpace ->
                PathActionIssue.InsufficientSpace.Resolution.Cancel(error)
            is PathActionIssue.UnknownError ->
                PathActionIssue.UnknownError.Resolution.Cancel(error)
            is PathActionIssue.TrashSizeLimitExceeded ->
                throw IllegalArgumentException("TrashSizeLimitExceeded is not an IPC-transportable issue type")
            is PathActionIssue.TrashMoveFailed ->
                throw IllegalArgumentException("TrashMoveFailed is not an IPC-transportable issue type")
            is PathActionIssue.TrashNotSupported ->
                throw IllegalArgumentException("TrashNotSupported is not an IPC-transportable issue type")
            is PathActionIssue.ArchivePasswordRequired ->
                throw IllegalArgumentException("ArchivePasswordRequired is not an IPC-transportable issue type")
        }
    }

    when (issue) {
        is PathActionIssue.PathAlreadyExists -> when (resolutionType) {
            FileOperationIssueResolution.ResolutionType.SKIP ->
                PathActionIssue.PathAlreadyExists.Resolution.Skip(applyToAll)
            FileOperationIssueResolution.ResolutionType.OVERWRITE ->
                PathActionIssue.PathAlreadyExists.Resolution.Overwrite(applyToAll)
            FileOperationIssueResolution.ResolutionType.MERGE ->
                PathActionIssue.PathAlreadyExists.Resolution.Merge(applyToAll)
            FileOperationIssueResolution.ResolutionType.RENAME_SOURCE ->
                PathActionIssue.PathAlreadyExists.Resolution.RenameSource(newName!!, applyToAll)
            FileOperationIssueResolution.ResolutionType.RENAME_DESTINATION ->
                PathActionIssue.PathAlreadyExists.Resolution.RenameDestination(newName!!)
            else -> throw IllegalArgumentException("Invalid resolution $resolutionType for PathAlreadyExists")
        }

        is PathActionIssue.InsufficientPermission -> when (resolutionType) {
            FileOperationIssueResolution.ResolutionType.SKIP ->
                PathActionIssue.InsufficientPermission.Resolution.Skip(applyToAll)
            else -> throw IllegalArgumentException("Invalid resolution $resolutionType for InsufficientPermission")
        }

        is PathActionIssue.InsufficientSpace -> when (resolutionType) {
            FileOperationIssueResolution.ResolutionType.RETRY ->
                PathActionIssue.InsufficientSpace.Resolution.Retry
            else -> throw IllegalArgumentException("Invalid resolution $resolutionType for InsufficientSpace")
        }

        is PathActionIssue.UnknownError -> when (resolutionType) {
            FileOperationIssueResolution.ResolutionType.SKIP ->
                PathActionIssue.UnknownError.Resolution.Skip(applyToAll)
            FileOperationIssueResolution.ResolutionType.RETRY ->
                PathActionIssue.UnknownError.Resolution.Retry
            else -> throw IllegalArgumentException("Invalid resolution $resolutionType for UnknownError")
        }

        is PathActionIssue.TrashSizeLimitExceeded ->
            throw IllegalArgumentException("TrashSizeLimitExceeded is not an IPC-transportable issue type")

        is PathActionIssue.TrashMoveFailed ->
            throw IllegalArgumentException("TrashMoveFailed is not an IPC-transportable issue type")

        is PathActionIssue.TrashNotSupported ->
            throw IllegalArgumentException("TrashNotSupported is not an IPC-transportable issue type")

        is PathActionIssue.ArchivePasswordRequired ->
            throw IllegalArgumentException("ArchivePasswordRequired is not an IPC-transportable issue type")
    }
}
