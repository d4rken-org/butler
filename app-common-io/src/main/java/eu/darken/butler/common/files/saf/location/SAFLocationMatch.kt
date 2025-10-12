package eu.darken.butler.common.files.saf.location

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