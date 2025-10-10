package eu.darken.butler.common.files.saf.location

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.SAFPath
import kotlin.time.Instant

/**
 * Represents a granted SAF location with user preferences.
 *
 * Each location corresponds to a persisted URI permission granted by
 * the Android system via the Storage Access Framework.
 */
data class SAFLocation(
    /**
     * Stable identifier for this location.
     * Based on the tree URI to ensure consistency.
     */
    val id: String,

    /**
     * Tree URI as string (e.g., "content://com.android.externalstorage.documents/tree/primary")
     */
    val treeUri: String,

    /**
     * The SAF path representing this location's root
     */
    val path: SAFPath,

    /**
     * Whether we have read permission
     */
    val hasReadPermission: Boolean,

    /**
     * Whether we have write permission
     */
    val hasWritePermission: Boolean,

    /**
     * When the permission was first granted
     */
    val grantedAt: Instant,

    // --- User Preferences ---

    /**
     * User-provided custom label (e.g., "My SD Card", "Work Files")
     */
    val userLabel: String? = null,

    /**
     * Whether this location is hidden from the UI
     */
    val isHidden: Boolean = false,

    /**
     * Whether this location is pinned to the top of the list
     */
    val isPinned: Boolean = false,
) {
    /**
     * Display name for UI: user label if set, otherwise path's readable name
     */
    val displayName: CaString
        get() = userLabel?.toCaString() ?: path.userReadableName

    /**
     * Whether this location has both read and write access
     */
    val hasFullAccess: Boolean
        get() = hasReadPermission && hasWritePermission
}