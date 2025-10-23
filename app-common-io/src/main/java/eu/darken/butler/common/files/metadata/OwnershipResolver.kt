package eu.darken.butler.common.files.metadata

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.pkgs.pkgops.LibcoreTool
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves Android UIDs and GIDs to human-readable names.
 *
 * Android UID/GID system:
 * - System UIDs/GIDs (0-9999): Hardcoded constants like "system", "radio", "bluetooth"
 * - App UIDs (10000+): Dynamically assigned per app, resolved via PackageManager
 *
 * Resolution strategies (in order):
 * 1. Session-level cache lookup (fastest)
 * 2. LibcoreTool reflection (may work on some devices)
 * 3. Hardcoded Android system ID mapping (for UIDs/GIDs 0-9999)
 * 4. PackageManager resolution (for app UIDs 10000+)
 * 5. Return null (will display numeric IDs in UI)
 *
 * Results are cached in-memory for the app session to improve performance.
 */
@Singleton
class OwnershipResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val libcoreTool: LibcoreTool,
) {
    private val tag = logTag("OwnershipResolver")
    private val packageManager: PackageManager = context.packageManager

    // Session-level caches
    private val userCache = ConcurrentHashMap<Int, String?>()
    private val groupCache = ConcurrentHashMap<Int, String?>()
    private val appUidCache = ConcurrentHashMap<Int, String?>()  // Shared cache for app UIDs/GIDs

    /**
     * Resolves a UID and GID to an Ownership object with names.
     * Names will be null if all resolution strategies fail.
     */
    fun resolve(userId: Int, groupId: Int): Ownership {
        val userName = resolveUserName(userId)
        val groupName = resolveGroupName(groupId)

        return Ownership(
            userId = userId.toLong(),
            groupId = groupId.toLong(),
            userName = userName,
            groupName = groupName,
        )
    }

    /**
     * Resolves a UID to a username using multiple strategies.
     */
    private fun resolveUserName(uid: Int): String? {
        // Strategy 1: Check cache
        if (userCache.containsKey(uid)) {
            val cachedName = userCache[uid]
            log(tag, VERBOSE) { "resolveUserName($uid): cache hit -> $cachedName" }
            return cachedName
        }

        // Strategy 2: Try LibcoreTool reflection
        libcoreTool.getNameForUid(uid)?.let { name ->
            log(tag, VERBOSE) { "resolveUserName($uid): LibcoreTool -> $name" }
            userCache[uid] = name
            return name
        }

        // Strategy 3: Check Android system UID mapping (0-9999)
        if (AndroidSystemIds.isSystemUid(uid)) {
            AndroidSystemIds.SYSTEM_UIDS[uid]?.let { name ->
                log(tag, VERBOSE) { "resolveUserName($uid): system ID -> $name" }
                userCache[uid] = name
                return name
            }
        }

        // Strategy 4: Resolve app UID via PackageManager (10000+)
        if (AndroidSystemIds.isAppUid(uid) || AndroidSystemIds.isIsolatedUid(uid)) {
            resolveAppUid(uid)?.let { name ->
                log(tag, VERBOSE) { "resolveUserName($uid): PackageManager -> $name" }
                userCache[uid] = name
                return name
            }
        }

        // Strategy 5: Fallback to null (will display numeric UID)
        // Don't cache null to avoid unnecessary memory usage
        log(tag, VERBOSE) { "resolveUserName($uid): all strategies failed, returning null" }
        return null
    }

    /**
     * Resolves a GID to a group name using multiple strategies.
     */
    private fun resolveGroupName(gid: Int): String? {
        // Strategy 1: Check cache
        if (groupCache.containsKey(gid)) {
            val cachedName = groupCache[gid]
            log(tag, VERBOSE) { "resolveGroupName($gid): cache hit -> $cachedName" }
            return cachedName
        }

        // Strategy 2: Try LibcoreTool reflection
        libcoreTool.getNameForGid(gid)?.let { name ->
            log(tag, VERBOSE) { "resolveGroupName($gid): LibcoreTool -> $name" }
            groupCache[gid] = name
            return name
        }

        // Strategy 3: Check Android system GID mapping (0-9999)
        if (AndroidSystemIds.isSystemUid(gid)) {  // GIDs use same range as UIDs
            AndroidSystemIds.SYSTEM_GIDS[gid]?.let { name ->
                log(tag, VERBOSE) { "resolveGroupName($gid): system ID -> $name" }
                groupCache[gid] = name
                return name
            }
        }

        // Strategy 4: For app GIDs, try resolving as UID (they often match)
        if (AndroidSystemIds.isAppUid(gid) || AndroidSystemIds.isIsolatedUid(gid)) {
            resolveAppUid(gid)?.let { name ->
                log(tag, VERBOSE) { "resolveGroupName($gid): PackageManager (as UID) -> $name" }
                groupCache[gid] = name
                return name
            }
        }

        // Strategy 5: Fallback to null (will display numeric GID)
        // Don't cache null to avoid unnecessary memory usage
        log(tag, VERBOSE) { "resolveGroupName($gid): all strategies failed, returning null" }
        return null
    }

    /**
     * Resolves an app UID to a package name using PackageManager.
     * Returns the first package name if multiple packages share the UID.
     * Uses a shared cache since app UIDs and GIDs are typically the same.
     */
    private fun resolveAppUid(uid: Int): String? {
        // Check shared app UID cache first
        if (appUidCache.containsKey(uid)) {
            val cachedName = appUidCache[uid]
            log(tag, VERBOSE) { "resolveAppUid($uid): cache hit -> $cachedName" }
            return cachedName
        }

        // Query PackageManager and cache the result
        return try {
            val packages = packageManager.getPackagesForUid(uid)
            val packageName = packages?.firstOrNull()
            // Only cache non-null values (ConcurrentHashMap doesn't allow null)
            packageName?.let { appUidCache[uid] = it }
            packageName?.also {
                log(tag, VERBOSE) { "resolveAppUid($uid): found package -> $it" }
            }
        } catch (e: Exception) {
            log(tag, WARN) { "resolveAppUid($uid) failed: ${e.asLog()}" }
            // Don't cache exceptions to allow retries on next lookup
            null
        }
    }

    /**
     * Clears the session-level cache.
     * Useful for testing or if ownership data changes during the session.
     */
    fun clearCache() {
        userCache.clear()
        groupCache.clear()
        appUidCache.clear()
        log(tag, VERBOSE) { "Cache cleared" }
    }
}
