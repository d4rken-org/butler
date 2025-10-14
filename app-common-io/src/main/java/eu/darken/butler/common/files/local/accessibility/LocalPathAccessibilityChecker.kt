package eu.darken.butler.common.files.local.accessibility

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.hasApiLevel
import eu.darken.butler.common.storage.StorageEnvironment
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Checks if a local path is accessible via normal Java File APIs given storage permissions.
 * Used by LocalGateway AUTO mode to optimize privilege escalation.
 */
@Singleton
class LocalPathAccessibilityChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storageEnvironment: StorageEnvironment,
) {

    private val accessibleStoragePrefixes: Set<String> by lazy {
        buildSet {
            storageEnvironment.externalDirs.forEach { add(it.path) }
            // GOTCHA: Only add /sdcard symlink, NOT mount points like /storage/emulated or /storage/self
            // Those would incorrectly match paths for other users (e.g. /storage/emulated/11/)
            add("/sdcard")
        }
    }

    private val scopedStorageRestrictedPrefixes: Set<String> by lazy {
        if (!hasApiLevel(30)) return@lazy emptySet()
        storageEnvironment.publicDataDirs.toSet().map { it.path }.toSet()
    }

    /**
     * Returns true if path is definitely inaccessible, false if it might be accessible.
     * Conservative: when uncertain, returns false (try normal access first).
     */
    fun isDefinitelyInaccessible(path: LocalPath, forWriting: Boolean): Boolean {
        val pathStr = path.path

        if (pathStr == "/" ||
            pathStr.startsWith("/proc/") ||
            pathStr.startsWith("/sys/") ||
            pathStr.startsWith("/dev/")
        ) {
            return true
        }

        if (pathStr.startsWith("/system/") ||
            pathStr.startsWith("/vendor/") ||
            pathStr.startsWith("/product/") ||
            pathStr.startsWith("/system_ext/") ||
            pathStr.startsWith("/apex/")
        ) {
            return true
        }

        if (pathStr.startsWith("/sdcard/") ||
            pathStr.startsWith("/storage/emulated/0/") ||
            pathStr.startsWith("/storage/self/primary/")
        ) {
            if (scopedStorageRestrictedPrefixes.isNotEmpty()) {
                if (scopedStorageRestrictedPrefixes.any { pathStr.startsWith(it) }) {
                    return true
                }
            }
            return false
        }

        val isUnderAccessibleStorage = accessibleStoragePrefixes.any { pathStr.startsWith(it) }
        if (isUnderAccessibleStorage) {
            if (scopedStorageRestrictedPrefixes.isNotEmpty()) {
                if (scopedStorageRestrictedPrefixes.any { pathStr.startsWith(it) }) {
                    return true
                }
            }
            return false
        }

        if (pathStr.startsWith("/data/data/") || pathStr.startsWith("/data/user/")) {
            val packageSegment = pathStr
                .removePrefix("/data/data/")
                .removePrefix("/data/user/0/")
                .removePrefix("/data/user/")
                .substringBefore('/', "")

            if (packageSegment == context.packageName) {
                return false
            }
            return true
        }

        // GOTCHA: Conservative fallback - unknown paths are inaccessible
        // If actually accessible, IOException fallback in LocalGateway handles it
        return true
    }
}
