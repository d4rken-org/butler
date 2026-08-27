package eu.darken.butler.common.files.saf.location

import android.net.Uri
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.saf.SAFDocFile
import kotlinx.coroutines.flow.Flow

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
 * locationManager.locations.collect { locations ->
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
 * locationManager.setLocationHidden(locationId, true)
 * ```
 */
interface SAFLocationManager {

    // --- Query Operations ---

    /**
     * All granted SAF locations with user preferences applied.
     *
     * Emits updated list when:
     * - Android system permissions change
     * - User preferences are modified
     *
     * Hidden locations are excluded from the list.
     * Results are sorted by grant time (newest first).
     */
    val locations: Flow<List<SAFLocation>>

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
    fun findPermissionFor(path: SAFPath): SAFLocationMatch?

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

    // --- Permission Lifecycle ---

    /**
     * Take persistent permission for a tree URI.
     *
     * This should be called after the user grants access via
     * Intent.ACTION_OPEN_DOCUMENT_TREE. The permission is persisted
     * across app restarts.
     *
     * @param treeUri The tree URI granted by the user
     * @return The location ID of the newly created SAFLocation
     * @throws SecurityException if permission cannot be taken
     */
    suspend fun grantPermission(treeUri: Uri): String

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
     * Set a display label only if the location doesn't have one yet.
     *
     * Location IDs are derived from the tree URI, so re-granting an already known location
     * targets the same entry, including one the user renamed. This never overwrites such a name.
     *
     * @param locationId The location ID
     * @param label Label to apply if none is stored yet
     * @return The label the location now has: the pre-existing one if there was any, otherwise [label]
     */
    suspend fun seedLocationLabel(locationId: String, label: String): String?

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
     * Manually trigger refresh of locations.
     *
     * Useful after external changes to permissions that might not
     * be automatically detected.
     */
    suspend fun refresh()

    // --- Path Mapping ---

    /**
     * Convert a LocalPath to SAFPath using granted SAF permissions.
     *
     * This creates a SAFPath ONLY when a matching permission exists.
     * Use this to check if a path can be accessed via SAF.
     *
     * **Returns null when:**
     * - No storage volume found for the path
     * - No SAF permission granted for the path
     *
     * **Example with permission:**
     * - Permission: `tree/primary:Android/data`
     * - LocalPath: `/storage/emulated/0/Android/data/com.app/file.txt`
     * - Result: `SAFPath(treeRoot="tree/primary:Android/data", segments=["com.app", "file.txt"])`
     *
     * **Example without permission:**
     * - LocalPath: `/storage/emulated/0/Android/data/com.app/file.txt`
     * - No permission for Android/data
     * - Result: `null`
     *
     * @param localPath The local file system path to convert
     * @return SAFPath with permission-aware treeRoot, or null if no permission exists
     */
    fun toSAFPath(localPath: LocalPath): SAFPath?

    /**
     * Convert a SAFPath back to LocalPath.
     *
     * Resolves the SAF tree URI to its corresponding storage volume
     * and constructs the local file system path.
     *
     * @param safPath The SAF path to convert
     * @return LocalPath if the storage volume is known, null otherwise
     */
    fun toLocalPath(safPath: SAFPath): LocalPath?
}