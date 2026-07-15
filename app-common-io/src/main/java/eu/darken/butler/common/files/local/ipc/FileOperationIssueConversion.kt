package eu.darken.butler.common.files.local.ipc

import android.content.Context
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
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
 */
fun PathActionIssue.toFileOperationIssue(context: Context): FileOperationIssue {
    return when (this) {
        is PathActionIssue.InsufficientPermission -> FileOperationIssue(
            issueId = id.id.toString(),
            issueType = FileOperationIssue.IssueType.PERMISSION_DENIED,
            sourcePath = source as? LocalPathLookup,
            destinationPath = destinationPath as LocalPath,
            errorMessage = exception?.toString(),
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
            errorMessage = errorMessage.get(context),
            canSkip = canSkip,
            canRetry = canRetry,
        )

        is PathActionIssue.TrashSizeLimitExceeded -> throw IllegalArgumentException(
            "TrashSizeLimitExceeded is not an IPC-transportable issue type"
        )

        is PathActionIssue.TrashMoveFailed -> throw IllegalArgumentException(
            "TrashMoveFailed is not an IPC-transportable issue type"
        )

        is PathActionIssue.ArchivePasswordRequired -> throw IllegalArgumentException(
            "ArchivePasswordRequired is not an IPC-transportable issue type"
        )
    }
}

/**
 * Convert from IPC FileOperationIssue to domain PathActionIssue.
 * Used on client side when receiving issue from host.
 */
fun FileOperationIssue.toPathActionIssue(): PathActionIssue {
    val issueId = Issue.Id(id = Uuid.parse(issueId))

    return when (issueType) {
        FileOperationIssue.IssueType.PERMISSION_DENIED -> PathActionIssue.InsufficientPermission(
            id = issueId,
            source = sourcePath,
            destinationPath = destinationPath,
            canSkip = canSkip,
            exception = errorMessage?.let { WriteException(path = destinationPath, cause = IOException(it)) },
        )

        FileOperationIssue.IssueType.PATH_ALREADY_EXISTS -> PathActionIssue.PathAlreadyExists(
            id = issueId,
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
            id = issueId,
            source = sourcePath!!,  // Source must be present for this issue type
            destinationPath = destinationPath,
        )

        FileOperationIssue.IssueType.UNKNOWN_ERROR -> PathActionIssue.UnknownError(
            id = issueId,
            source = sourcePath,
            destinationPath = destinationPath,
            exception = IOException(errorMessage ?: "Unknown error"),
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
fun PathActionIssue.Resolution.toFileOperationIssueResolution(): FileOperationIssueResolution {
    return when (val resolution = this) {
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
                error = resolution.error?.message
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
                error = resolution.error?.message
            )

        is PathActionIssue.InsufficientSpace.Resolution.Retry ->
            FileOperationIssueResolution(
                resolutionType = FileOperationIssueResolution.ResolutionType.RETRY
            )

        is PathActionIssue.InsufficientSpace.Resolution.Cancel ->
            FileOperationIssueResolution(
                resolutionType = FileOperationIssueResolution.ResolutionType.CANCEL,
                cancelled = true,
                error = resolution.error?.message
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
                error = resolution.error?.message
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
fun FileOperationIssueResolution.toPathActionIssueResolution(
    issue: PathActionIssue
): PathActionIssue.Resolution {
    if (cancelled) {
        val error = error?.let { IOException(it) }
        return when (issue) {
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
            is PathActionIssue.ArchivePasswordRequired ->
                throw IllegalArgumentException("ArchivePasswordRequired is not an IPC-transportable issue type")
        }
    }

    return when (issue) {
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

        is PathActionIssue.ArchivePasswordRequired ->
            throw IllegalArgumentException("ArchivePasswordRequired is not an IPC-transportable issue type")
    }
}
