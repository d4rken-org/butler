package eu.darken.butler.common.files.metadata

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.pkgs.pkgops.LibcoreTool
import eu.darken.butler.common.pkgs.pkgops.PackagesListParser
import eu.darken.butler.common.shell.ShellOps
import eu.darken.butler.common.shell.ipc.ShellOpsCmd
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves Android UIDs and GIDs to human-readable names.
 *
 * Android UID/GID system:
 * - System UIDs/GIDs (0-9999): Hardcoded constants like "system", "radio", "bluetooth"
 * - App UIDs (10000+): Dynamically assigned per app, resolved via /data/system/packages.list
 *
 * Resolution strategies (in order):
 * 1. Session-level cache lookup (fastest, includes cached failures for system UIDs)
 * 2. LibcoreTool reflection (may work on some devices)
 * 3. Hardcoded Android system ID mapping (for UIDs/GIDs 0-9999)
 * 4. Shell command fallback (for system UIDs 0-9999, device-accurate)
 * 5. packages.list parsing (for app UIDs 10000+, lazy loaded)
 * 6. PackageManager fallback (for newly installed apps)
 * 7. Return null (will display numeric IDs in UI)
 *
 * Results are cached in-memory for the app session to improve performance.
 * Negative results (null) are cached for system UIDs only, as they don't change.
 */
@Singleton
class OwnershipResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val libcoreTool: LibcoreTool,
    private val packagesListParser: PackagesListParser,
    private val shellOps: ShellOps,
) {
    private val tag = logTag("OwnershipResolver")
    private val packageManager: PackageManager = context.packageManager

    // Session-level caches for successful resolutions
    private val userCache = ConcurrentHashMap<Int, String>()
    private val groupCache = ConcurrentHashMap<Int, String>()
    private val appUidCache = ConcurrentHashMap<Int, String>()  // Shared cache for app UIDs/GIDs

    // Negative caches for system UIDs/GIDs (track failed lookups that shouldn't be retried)
    private val userCacheNegative = ConcurrentHashMap.newKeySet<Int>()
    private val groupCacheNegative = ConcurrentHashMap.newKeySet<Int>()

    // Lazy-loaded packages.list cache - parsed only once on first app UID lookup
    private val packagesListCache: Map<Int, String> by lazy {
        log(tag, INFO) { "Loading packages.list for the first time..." }
        val result = packagesListParser.parse()
        log(tag, INFO) { "Loaded ${result.size} app UID mappings from packages.list" }
        result
    }

    /**
     * Resolves a UID and GID to an Ownership object with names.
     * Names will be null if all resolution strategies fail.
     */
    suspend fun resolve(userId: Int, groupId: Int): Ownership {
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
    private suspend fun resolveUserName(uid: Int): String? {
        // Strategy 1: Check positive cache
        userCache[uid]?.let { name ->
            log(tag, VERBOSE) { "resolveUserName($uid): cache hit -> $name" }
            return name
        }

        // Strategy 1b: Check negative cache (for system UIDs only)
        if (userCacheNegative.contains(uid)) {
            log(tag, VERBOSE) { "resolveUserName($uid): negative cache hit -> null" }
            return null
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

            // Strategy 4: Try shell command for system UIDs
            resolveUidViaShell(uid)?.let { name ->
                log(tag, VERBOSE) { "resolveUserName($uid): shell -> $name" }
                userCache[uid] = name
                return name
            }

            // Cache negative result for system UIDs (they won't change)
            log(tag, VERBOSE) { "resolveUserName($uid): system UID failed, caching negative result" }
            userCacheNegative.add(uid)
            return null
        }

        // Strategy 5: Resolve app UID via packages.list/PackageManager (10000+)
        if (AndroidSystemIds.isAppUid(uid) || AndroidSystemIds.isIsolatedUid(uid) || AndroidSystemIds.isSdkSandboxUid(
                uid
            )
        ) {
            resolveAppUid(uid)?.let { name ->
                log(tag, VERBOSE) { "resolveUserName($uid): app UID -> $name" }
                userCache[uid] = name
                return name
            }
        }

        // Strategy 6: Fallback to null (will display numeric UID)
        // Don't cache null for app UIDs (new apps can be installed)
        log(tag, VERBOSE) { "resolveUserName($uid): all strategies failed, returning null" }
        return null
    }

    /**
     * Resolves a GID to a group name using multiple strategies.
     */
    private suspend fun resolveGroupName(gid: Int): String? {
        // Strategy 1: Check positive cache
        groupCache[gid]?.let { name ->
            log(tag, VERBOSE) { "resolveGroupName($gid): cache hit -> $name" }
            return name
        }

        // Strategy 1b: Check negative cache (for system GIDs only)
        if (groupCacheNegative.contains(gid)) {
            log(tag, VERBOSE) { "resolveGroupName($gid): negative cache hit -> null" }
            return null
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

            // Strategy 4: Try shell command for system GIDs
            resolveGidViaShell(gid)?.let { name ->
                log(tag, VERBOSE) { "resolveGroupName($gid): shell -> $name" }
                groupCache[gid] = name
                return name
            }

            // Cache negative result for system GIDs (they won't change)
            log(tag, VERBOSE) { "resolveGroupName($gid): system GID failed, caching negative result" }
            groupCacheNegative.add(gid)
            return null
        }

        // Strategy 5: For app GIDs, try resolving as UID (they often match)
        if (AndroidSystemIds.isAppUid(gid) || AndroidSystemIds.isIsolatedUid(gid) || AndroidSystemIds.isSdkSandboxUid(
                gid
            )
        ) {
            resolveAppUid(gid)?.let { name ->
                log(tag, VERBOSE) { "resolveGroupName($gid): app GID -> $name" }
                groupCache[gid] = name
                return name
            }
        }

        // Strategy 6: Fallback to null (will display numeric GID)
        // Don't cache null for app GIDs (new apps can be installed)
        log(tag, VERBOSE) { "resolveGroupName($gid): all strategies failed, returning null" }
        return null
    }

    /**
     * Resolves an app UID to a package name.
     *
     * Strategy:
     * 1. Check runtime cache
     * 2. Check packages.list cache (lazy loaded once)
     * 3. Query PackageManager (for newly installed apps not in packages.list yet)
     *
     * Uses a shared cache since app UIDs and GIDs are typically the same.
     */
    private fun resolveAppUid(uid: Int): String? {
        // Strategy 1: Check shared app UID runtime cache first
        if (appUidCache.containsKey(uid)) {
            val cachedName = appUidCache[uid]
            log(tag, VERBOSE) { "resolveAppUid($uid): runtime cache hit -> $cachedName" }
            return cachedName
        }

        // Strategy 2: Check packages.list cache (lazy loaded)
        packagesListCache[uid]?.let { packageName ->
            log(tag, VERBOSE) { "resolveAppUid($uid): packages.list -> $packageName" }
            appUidCache[uid] = packageName
            return packageName
        }

        // Strategy 3: Query PackageManager as fallback (for newly installed apps)
        return try {
            val packages = packageManager.getPackagesForUid(uid)
            val packageName = packages?.firstOrNull()
            // Only cache non-null values (ConcurrentHashMap doesn't allow null)
            packageName?.let { appUidCache[uid] = it }
            packageName?.also {
                log(tag, VERBOSE) { "resolveAppUid($uid): PackageManager fallback -> $it" }
            }
        } catch (e: Exception) {
            log(tag, WARN) { "resolveAppUid($uid) PackageManager failed: ${e.asLog()}" }
            // Don't cache exceptions to allow retries on next lookup
            null
        }
    }

    /**
     * Resolves a system UID to a username using shell command.
     * Only works for system UIDs (0-9999).
     */
    private suspend fun resolveUidViaShell(uid: Int): String? {
        return try {
            val result = shellOps.execute(
                ShellOpsCmd("id", "-un", uid.toString()),
                ShellOps.Mode.NORMAL
            )
            result.output.firstOrNull()?.trim()?.takeIf { it.isNotBlank() && !it.startsWith("id:") }
        } catch (e: Exception) {
            log(tag, WARN) { "resolveUidViaShell($uid) failed: ${e.asLog()}" }
            null
        }
    }

    /**
     * Resolves a system GID to a group name using shell command.
     * Only works for system GIDs (0-9999).
     */
    private suspend fun resolveGidViaShell(gid: Int): String? {
        return try {
            val result = shellOps.execute(
                ShellOpsCmd("id", "-gn", gid.toString()),
                ShellOps.Mode.NORMAL
            )
            result.output.firstOrNull()?.trim()?.takeIf { it.isNotBlank() && !it.startsWith("id:") }
        } catch (e: Exception) {
            log(tag, WARN) { "resolveGidViaShell($gid) failed: ${e.asLog()}" }
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
        userCacheNegative.clear()
        groupCacheNegative.clear()
        log(tag, VERBOSE) { "Cache cleared" }
    }
}
