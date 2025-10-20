package eu.darken.butler.common.files

import androidx.annotation.Keep
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.files.extensions.Segments
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import kotlin.time.Instant

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
data class LookupOptions(
    val fetchOwnership: Boolean = false,
    val fetchPermissions: Boolean = false,
    val fetchCreatedAt: Boolean = false,
) {
    companion object {
        /** Fast path: Only basic metadata (fileType, size, modifiedAt) */
        val BASIC = LookupOptions()

        /** Full metadata: All available fields including ownership/permissions/createdAt */
        val EXTENDED = LookupOptions(
            fetchOwnership = true,
            fetchPermissions = true,
            fetchCreatedAt = true
        )
    }
}

@Keep
interface APathLookup<T : APath<T>> {
    val lookedUp: T
    val fileType: FileType

    val size: Long?
    val modifiedAt: Instant?
    val target: APath<*>?

    val ownership: Ownership?
    val permissions: Permissions?
    val createdAt: Instant?

    val error: Throwable?

    val path: String
        get() = lookedUp.path
    val name: String
        get() = lookedUp.name
    val userReadablePath: CaString
        get() = lookedUp.userReadablePath
    val userReadableName: CaString
        get() = lookedUp.userReadableName

    val segments: Segments
        get() = lookedUp.segments

    fun child(vararg segments: String): T = lookedUp.child(*segments)
}