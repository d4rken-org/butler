package eu.darken.butler.common.files.saf

import android.net.Uri
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.SAFPath
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/**
 * Manages SAF (Storage Access Framework) granted permissions and user preferences.
 *
 * Abstracts away Android-specific ContentResolver and UriPermission types,
 * making SAF operations testable and providing a centralized place for
 * permission management and user customizations.
 *
 * ## Responsibilities
 *
 * 1. **Permission Query**: Check granted permissions and find matches for paths
 * 2. **Permission Lifecycle**: Grant and revoke persistent URI permissions
 * 3. **User Preferences**: Custom labels, hiding, pinning locations
 *
 * ## Usage
 *
 * ```kotlin
 * // Query locations
 * locationManager.getGrantedLocations().collect { locations ->
 *     locations.forEach { location ->
 *         println("${location.displayName}: ${location.path}")
 *     }
 * }
 *
 * // Check permission for a path
 * if (locationManager.hasAccessTo(safPath)) {
 *     // Can access this path
 * }
 *
 * // Grant new permission (after SAF picker)
 * locationManager.grantPermission(treeUri)
 *
 * // Customize location
 * locationManager.setLocationLabel(locationId, "My SD Card")
 * locationManager.setLocationPinned(locationId, true)
 * ```
 */
interface SAFLocationManager {

    // --- Query Operations ---

    /**
     * Get all granted SAF locations with user preferences applied.
     *
     * Emits updated list when:
     * - Android system permissions change
     * - User preferences are modified
     *
     * Hidden locations are excluded from the list.
     * Results are sorted with pinned locations first.
     */
    fun getGrantedLocations(): Flow<List<SAFLocation>>

    /**
     * Find the best matching permission for a given SAFPath.
     *
     * Returns the closest parent permission that covers this path,
     * along with the segments that need to be traversed from the
     * permission root to reach the target path.
     *
     * @param path The SAF path to find permission for
     * @return Permission match if found, null if no permission covers this path
     */
    fun findPermissionFor(path: SAFPath): SAFPermissionMatch?

    /**
     * Get the DocumentFile handle for a SAFPath by resolving to its permission root.
     *
     * This is the primary access method for SAF operations - it finds the closest
     * URI permission that covers the requested path and builds a usable DocumentFile
     * from it.
     *
     * @param path The SAF path to get DocumentFile for
     * @return DocumentFile if we have permission, null if no permission covers this path
     */
    fun getDocFileFor(path: SAFPath): SAFDocFile?

    /**
     * Quick check if we have read/write access to a specific path.
     *
     * @param path The SAF path to check
     * @return true if we have both read and write permission
     */
    fun hasAccessTo(path: SAFPath): Boolean

    // --- Permission Lifecycle ---

    /**
     * Take persistent permission for a tree URI.
     *
     * This should be called after the user grants access via
     * Intent.ACTION_OPEN_DOCUMENT_TREE. The permission is persisted
     * across app restarts.
     *
     * @param treeUri The tree URI granted by the user
     * @throws SecurityException if permission cannot be taken
     */
    suspend fun grantPermission(treeUri: Uri)

    /**
     * Release persistent permission for a location.
     *
     * Removes both the Android system permission and user preferences.
     *
     * @param locationId The location ID to revoke
     */
    suspend fun revokePermission(locationId: String)

    // --- User Preferences ---

    /**
     * Set custom display label for a location.
     *
     * @param locationId The location ID
     * @param label Custom label, or null to use default name
     */
    suspend fun setLocationLabel(locationId: String, label: String?)

    /**
     * Hide a location from the Device view in Explorer.
     *
     * Hidden locations are excluded from getGrantedLocations() but
     * the permission is retained and can be unhidden later.
     *
     * @param locationId The location ID
     * @param hidden true to hide, false to show
     */
    suspend fun setLocationHidden(locationId: String, hidden: Boolean)

    /**
     * Pin a location to the top of the list.
     *
     * Pinned locations appear first in getGrantedLocations().
     *
     * @param locationId The location ID
     * @param pinned true to pin, false to unpin
     */
    suspend fun setLocationPinned(locationId: String, pinned: Boolean)

    /**
     * Manually trigger refresh of locations.
     *
     * Useful after external changes to permissions that might not
     * be automatically detected.
     */
    suspend fun refresh()
}

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

/**
 * Result of finding a permission match for a SAF path.
 *
 * Contains the matching location and the path segments that need to be
 * traversed from the permission's root to reach the target path.
 */
data class SAFPermissionMatch(
    /**
     * The SAF location that provides permission for the requested path
     */
    val location: SAFLocation,

    /**
     * Path segments from the permission root to the target path.
     * Empty list if the target path is exactly at the permission root.
     */
    val missingSegments: List<String>,
)
