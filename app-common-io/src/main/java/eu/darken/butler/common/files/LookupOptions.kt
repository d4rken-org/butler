package eu.darken.butler.common.files

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Options for controlling which metadata is fetched during lookup operations.
 *
 * This allows granular control over performance vs completeness tradeoff:
 * - Basic metadata (fileType, size, modifiedAt) is always fetched (cheap)
 * - Extended metadata (ownership, permissions, createdAt) is optional (expensive syscalls)
 *
 * @param fetchOwnership Fetch Unix ownership (UID/GID). Requires extra syscall (lstat/fstat).
 * @param fetchPermissions Fetch Unix permissions (mode bits). Requires extra syscall (lstat/fstat).
 * @param fetchCreatedAt Fetch creation timestamp. Requires extra syscall (readAttributes).
 */
@Parcelize
data class LookupOptions(
    val fetchOwnership: Boolean = false,
    val fetchPermissions: Boolean = false,
    val fetchCreatedAt: Boolean = false,
) : Parcelable {
    companion object {
        val EXTENDED = LookupOptions(
            fetchOwnership = true,
            fetchPermissions = true,
            fetchCreatedAt = true
        )
    }
}