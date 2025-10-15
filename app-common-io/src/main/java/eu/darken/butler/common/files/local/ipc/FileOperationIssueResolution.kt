package eu.darken.butler.common.files.local.ipc

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * AIDL-compatible representation of PathActionIssue.Resolution.
 * Universal resolution type for all issue types and operations.
 */
@Parcelize
data class FileOperationIssueResolution(
    val resolutionType: ResolutionType,
    val applyToAll: Boolean = false,               // "Apply to all similar issues"
    val newName: String? = null,                   // For RENAME_SOURCE/DESTINATION
    val cancelled: Boolean = false,                // User cancelled operation
    val error: String? = null,                     // Optional error on cancel
) : Parcelable {

    enum class ResolutionType {
        SKIP,                   // Skip this file/directory
        RETRY,                  // Try operation again
        OVERWRITE,              // Overwrite existing file
        MERGE,                  // Merge directory contents
        RENAME_SOURCE,          // Rename source file to avoid conflict
        RENAME_DESTINATION,     // Rename destination file to avoid conflict
        CANCEL                  // Cancel entire operation
    }
}
