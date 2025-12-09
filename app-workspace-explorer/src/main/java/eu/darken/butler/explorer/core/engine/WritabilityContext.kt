package eu.darken.butler.explorer.core.engine

import eu.darken.butler.common.files.saf.location.SAFLocation

/**
 * Context for evaluating item writability during directory loading.
 * Captures access method availability and SAF permissions.
 */
data class WritabilityContext(
    /** Whether root access is currently available */
    val hasRoot: Boolean,
    /** Whether ADB/Shizuku access is currently available */
    val hasAdb: Boolean,
    /** Current app's UID for permission checking */
    val appUid: Int,
    /** SAF location if browsing SAF path, null for local paths */
    val safLocation: SAFLocation? = null,
) {
    /** Returns true if elevated access (root or ADB) is available */
    val hasElevatedAccess: Boolean get() = hasRoot || hasAdb

    /** Returns SAF write permission if applicable */
    val safCanWrite: Boolean? get() = safLocation?.hasWritePermission
}
