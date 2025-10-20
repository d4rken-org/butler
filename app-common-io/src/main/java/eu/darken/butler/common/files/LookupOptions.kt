package eu.darken.butler.common.files

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Options for controlling which metadata is fetched during lookup operations.
 *
 * This allows granular control over performance vs completeness tradeoff:
 * - Basic metadata (fileType, size, modifiedAt) is always fetched (cheap)
 * - Extended metadata (ownership, permissions, createdAt) is optional (expensive syscalls)
 */
@Parcelize
data class LookupOptions(
    val continueOnError: Boolean = false,
    val fallbackToUnknown: Boolean = false,
    val fetchSize: Boolean = false,
    val fetchModifiedAt: Boolean = false,
    val fetchOwnership: Boolean = false,
    val fetchPermissions: Boolean = false,
    val fetchCreatedAt: Boolean = false,
) : Parcelable {
    companion object {
        val BASE = LookupOptions(
            continueOnError = false,
            fetchSize = true,
            fetchModifiedAt = true,
            fetchOwnership = true,
            fetchPermissions = true,
            fetchCreatedAt = true,
        )
        val MAX = LookupOptions(
            continueOnError = true,
            fetchSize = true,
            fetchModifiedAt = true,
            fetchOwnership = true,
            fetchPermissions = true,
            fetchCreatedAt = true,
        )
    }
}