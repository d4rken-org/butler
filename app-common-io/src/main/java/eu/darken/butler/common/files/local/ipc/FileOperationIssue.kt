package eu.darken.butler.common.files.local.ipc

import android.os.Parcelable
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import kotlinx.parcelize.Parcelize

/**
 * AIDL-compatible representation of PathActionIssue.
 * Supports all issue types across all operations.
 *
 * Design note: Uses flat structure with capability flags rather than sealed class
 * because AIDL doesn't support inheritance/polymorphism well.
 */
@Parcelize
data class FileOperationIssue(
    val issueId: String,
    val issueType: IssueType,

    // Paths involved
    val sourcePath: LocalPathLookup? = null,        // For copy/move operations
    val destinationPath: LocalPath,                 // Always present (just the path)
    val destinationLookup: LocalPathLookup? = null, // For PathAlreadyExists/InsufficientSpace (with metadata)
    /**
     * The failure that caused the issue, encoded as an [eu.darken.butler.common.ipc.IpcErrorCodec]
     * carrier. A value without the codec's marker is a plain message and is used as one.
     */
    val error: String? = null,

    // Resolution capability flags
    // These tell the client what actions are valid for this specific issue
    val canSkip: Boolean = false,
    val canRetry: Boolean = false,
    val canOverwrite: Boolean = false,
    val canMerge: Boolean = false,
    val canRenameSource: Boolean = false,
    val canRenameDestination: Boolean = false,
    val suggestedName: String? = null,              // For rename conflicts
) : Parcelable {

    enum class IssueType {
        PERMISSION_DENIED,      // AccessDeniedException, SecurityException
        PATH_ALREADY_EXISTS,    // Target file/directory already exists
        INSUFFICIENT_SPACE,     // Not enough disk space
        UNKNOWN_ERROR          // Any other error
    }
}
