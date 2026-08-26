package eu.darken.butler.common.files.saf.location

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.UriPermission
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.SafUri
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.local.toLocalPath
import eu.darken.butler.common.files.saf.SAFDocFile
import eu.darken.butler.common.files.saf.location.db.SAFLocationDatabase
import eu.darken.butler.common.files.saf.location.db.SAFLocationEntity
import eu.darken.butler.common.rngString
import eu.darken.butler.common.storage.StorageManager2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Default implementation of SAFLocationManager.
 *
 * Combines Android's persisted URI permissions with user preferences
 * from DataStore. Automatically refreshes when permissions or preferences change.
 */
@Singleton
class SAFLocationManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    @AppScope private val appScope: CoroutineScope,
    private val contentResolver: ContentResolver,
    private val dispatcherProvider: DispatcherProvider,
    private val database: SAFLocationDatabase,
    private val storageManager2: StorageManager2,
) : SAFLocationManager {

    private val dao = database.safLocations()

    private val refreshTrigger = MutableStateFlow(rngString)

    /**
     * Cached locations (including hidden ones) updated when permissions or preferences change.
     * This cache is shared across all methods to avoid redundant location creation.
     */
    private val cachedLocations: StateFlow<List<SAFLocation>> = combine(
        refreshTrigger,
        dao.getAllPreferences(),
    ) { _, entities ->
        log(TAG) { "Refreshing location cache" }

        val entityMap = entities.associateBy { it.locationId }

        // Cleanup orphaned preferences (those without active permissions)
        val activePermissions = contentResolver.persistedUriPermissions
        val activeLocationIds = activePermissions.map { it.uri.toLocationId() }

        cleanup(activeLocationIds)

        activePermissions.mapNotNull { permission ->
            log(TAG, VERBOSE) { "Loading SAFLocation from $permission" }
            try {
                createSAFLocation(permission, entityMap)
            } catch (e: Exception) {
                log(TAG, ERROR) { "Failed to create SAFLocation from $permission: $e" }
                null
            }
        }
    }.stateIn(
        scope = appScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList()
    )

    override val locations: Flow<List<SAFLocation>> = cachedLocations
        .map { allLocations ->
            val locations = allLocations
                .filterNot { it.isHidden }
                .sortedWith(compareByDescending { it.grantedAt })

            locations.forEachIndexed { index, item -> log(TAG, INFO) { "#$index - $item" } }

            log(TAG) { "Found ${locations.size} granted locations (${allLocations.size} total, ${allLocations.size - locations.size} hidden)" }
            locations
        }
        .distinctUntilChanged()

    override fun findPermissionFor(path: SAFPath): SAFLocationMatch? {
        // Convert locations to permission-like structure for matching
        val availablePermissions = cachedLocations.value
            .map { location ->
                val uri = location.treeUri.toAndroidUri()
                val segments = uri.path!!.split(":").last().split(File.separator)
                location to segments.filter { it.isNotEmpty() }
            }
            .sortedByDescending { it.second.size }

        val targetSegments = path.segments.toMutableList()
        val missingSegments = mutableListOf<String>()

        // Try to find matching permission by walking up the path hierarchy
        while (true) {
            for ((location, permSegments) in availablePermissions) {
                val uri = location.treeUri.toAndroidUri()

                // Check for exact tree URI match first (handles root paths with empty segments)
                if (path.treeRoot == location.treeUri.toString()) {
                    return SAFLocationMatch(
                        location = location,
                        missingSegments = path.segments.toList(),
                    )
                }

                val samePrefix = path.pathUri.path!!.split(":").first() == uri.path!!.split(":").first()

                if (samePrefix && permSegments == targetSegments) {
                    return SAFLocationMatch(
                        location = location,
                        missingSegments = missingSegments,
                    )
                }
            }

            // Remove last segment and try again with parent path
            val removed = targetSegments.removeLastOrNull()
            if (removed != null) {
                missingSegments.add(0, removed)
            } else {
                break  // No more segments to check, path not found
            }
        }

        return null
    }

    override fun getDocFileFor(path: SAFPath): SAFDocFile? {
        val match = findPermissionFor(path) ?: return null

        val treeUri = match.location.treeUri.toAndroidUri()
        val targetTreeUri = SAFDocFile.buildTreeUri(treeUri, match.missingSegments)
        return SAFDocFile.fromTreeUri(context, contentResolver, targetTreeUri)
    }

    override suspend fun grantPermission(treeUri: Uri): String = withContext(dispatcherProvider.IO) {
        log(TAG) { "grantPermission(treeUri=$treeUri)" }

        val locationId = treeUri.toLocationId()
        try {
            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            log(TAG) { "Successfully granted permission for $treeUri (locationId=$locationId)" }
            refresh()
            locationId
        } catch (e: SecurityException) {
            log(TAG, ERROR) { "Failed to take persistable URI permission: $e" }
            throw e
        }
    }

    override suspend fun revokePermission(locationId: String) = withContext(dispatcherProvider.IO) {
        log(TAG) { "revokePermission(locationId=$locationId)" }

        // Find the URI for this location ID
        val permission = contentResolver.persistedUriPermissions.find { perm ->
            perm.uri.toLocationId() == locationId
        }

        if (permission != null) {
            try {
                contentResolver.releasePersistableUriPermission(
                    permission.uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                log(TAG) { "Successfully revoked permission for ${permission.uri}" }
            } catch (e: SecurityException) {
                log(TAG, WARN) { "Failed to release persistable URI permission: $e" }
            }
        } else {
            log(TAG, WARN) { "No permission found for locationId=$locationId" }
        }

        dao.delete(locationId)
        refresh()
    }

    override suspend fun setLocationLabel(locationId: String, label: String?) {
        log(TAG, VERBOSE) { "setLocationLabel(locationId=$locationId, label=$label)" }
        updateLocation(locationId) { it.copy(userLabel = label) }
    }

    override suspend fun seedLocationLabel(locationId: String, label: String): String? {
        log(TAG, VERBOSE) { "seedLocationLabel(locationId=$locationId, label=$label)" }
        // Read the entity, not `locations`: that one drops hidden locations, whose label needs the same protection.
        val existing = dao.getPreference(locationId)?.userLabel
        if (existing != null) {
            log(TAG) { "seedLocationLabel(...): Keeping existing label '$existing' for $locationId" }
            return existing
        }
        setLocationLabel(locationId, label)
        return label
    }

    override suspend fun setLocationHidden(locationId: String, hidden: Boolean) {
        log(TAG, VERBOSE) { "setLocationHidden(locationId=$locationId, hidden=$hidden)" }
        updateLocation(locationId) { it.copy(isHidden = hidden) }
    }

    override suspend fun refresh() {
        log(TAG) { "refresh()" }
        refreshTrigger.value = rngString
    }

    private suspend fun updateLocation(locationId: String, update: (SAFLocationEntity) -> SAFLocationEntity) {
        val current = dao.getPreference(locationId) ?: SAFLocationEntity(locationId = locationId)
        val updated = update(current).copy(locationId = locationId)
        dao.upsert(updated)

        // Room delivers the query invalidation asynchronously, so returning here would let callers
        // refresh from a cache that still holds the old values. Await the private cache, the public
        // one omits hidden locations and would never satisfy a hide.
        val observed = withTimeoutOrNull(CACHE_SYNC_TIMEOUT) {
            cachedLocations.first { locations ->
                locations.any {
                    it.id == locationId && it.userLabel == updated.userLabel && it.isHidden == updated.isHidden
                }
            }
        }
        if (observed == null) {
            log(TAG, WARN) { "updateLocation($locationId): Cache did not reflect $updated within $CACHE_SYNC_TIMEOUT" }
        }
    }

    suspend fun cleanup(activeLocationIds: List<String>) = withContext(dispatcherProvider.IO) {
        if (activeLocationIds.isEmpty()) {
            // Don't delete everything if the list is empty (might be a bug)
            return@withContext
        }
        dao.cleanup(activeLocationIds)
    }

    private fun createSAFLocation(
        permission: UriPermission,
        entities: Map<String, SAFLocationEntity>
    ): SAFLocation {
        val locationId = permission.uri.toLocationId()
        val entity = entities[locationId]

        return SAFLocation(
            id = locationId,
            treeUri = SafUri.fromAndroidUri(permission.uri),
            path = SAFPath.build(permission.uri),
            hasReadPermission = permission.isReadPermission,
            hasWritePermission = permission.isWritePermission,
            grantedAt = Instant.fromEpochMilliseconds(permission.persistedTime),
            userLabel = entity?.userLabel,
            isHidden = entity?.isHidden ?: false,
        )
    }

    /**
     * Generate a stable, fixed-length location ID from a URI using MD5.
     *
     * MD5 provides:
     * - Fixed 32-character length regardless of URI depth
     * - Near-zero collision probability (2^-128)
     * - Fast hashing for 128 max entries
     */
    private fun Uri.toLocationId(): String {
        val bytes = toString().toByteArray()
        val digest = MessageDigest.getInstance("MD5").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    override fun toSAFPath(localPath: LocalPath): SAFPath? {
        return try {
            log(TAG, VERBOSE) { "toSAFPath() called with: $localPath" }

            // Find the storage volume containing this path
            val osStorage = storageManager2.storageVolumes
                .onEach { log(TAG, VERBOSE) { "Checking volume: $it" } }
                .filter { it.directory != null }
                .firstOrNull { localPath.path.startsWith(it.directory!!.path) }
                ?.also { log(TAG, VERBOSE) { "Target volume for $localPath is $it" } }
                ?: return null.also { log(TAG, WARN) { "No storage volume found for $localPath" } }

            // Calculate path relative to volume root
            val prefixFreeFile = if (osStorage.directory!!.path != localPath.path) {
                localPath.path.replace("${osStorage.directory!!.path}${File.separator}", "")
            } else {
                ""
            }
            log(TAG, VERBOSE) { "Prefix-free path: '$prefixFreeFile'" }

            val segments = if (prefixFreeFile.isEmpty()) {
                emptyList()
            } else {
                prefixFreeFile.split(File.separator)
            }
            log(TAG, VERBOSE) { "Calculated segments: $segments" }

            // Create volume-based path for permission matching
            val volumeBasedPath = SAFPath.build(
                base = osStorage.treeUri,
                segs = segments.toTypedArray(),
            )

            // ONLY return if we have a matching permission
            val permissionMatch = findPermissionFor(volumeBasedPath)
            if (permissionMatch != null) {
                val permissionBasedPath = SAFPath.build(
                    base = permissionMatch.location.treeUri.toString(),
                    segs = permissionMatch.missingSegments.toTypedArray(),
                )
                log(TAG) { "toSAFPath() $localPath -> permission-based: treeRoot=${permissionBasedPath.treeRoot}, segments=${permissionBasedPath.segments}" }
                permissionBasedPath
            } else {
                log(TAG) { "toSAFPath() $localPath -> no permission found, returning null" }
                null
            }
        } catch (e: Exception) {
            log(TAG, ERROR) { "Failed to map $localPath: ${e.asLog()}" }
            null
        }
    }

    override fun toLocalPath(safPath: SAFPath): LocalPath? {
        return try {
            log(TAG, VERBOSE) { "toLocalPath() called with: $safPath" }

            // Extract path from the SafUri
            val safUri = safPath.treeRootUri
            val uriPath = safUri.path
            if (uriPath == null) {
                log(TAG, WARN) { "No path in SafUri: $safUri" }
                return null
            }
            log(TAG, VERBOSE) { "Extracted path from SafUri: $uriPath" }

            // Extract the subdirectory path from the URI
            // URI path format: "/tree/primary:Android/data" → extract "Android/data"
            // If no colon, returns empty list (document root, like "/tree/primary")
            val permissionPathSegments = try {
                val parts = uriPath.split(":", limit = 2)
                if (parts.size == 1) {
                    // No colon found, just document root (e.g., "/tree/primary")
                    emptyList()
                } else {
                    // Has subdirectory path (e.g., "Android/data" from "/tree/primary:Android/data")
                    parts[1]
                        .split(File.separator)
                        .filter { it.isNotEmpty() }
                }
            } catch (e: Exception) {
                log(TAG, WARN) { "Failed to parse path segments from $uriPath" }
                emptyList()
            }
            log(TAG, VERBOSE) { "Extracted permission path segments: $permissionPathSegments" }

            // Extract volume ID from SAF URI (e.g., "primary", "sdcard", "1234-5678")
            val volumeIdFromUri = uriPath.split("/tree/").lastOrNull()?.split(":")?.firstOrNull()
            if (volumeIdFromUri == null) {
                log(TAG, WARN) { "Cannot extract volume ID from URI: $uriPath" }
                return null
            }
            log(TAG, VERBOSE) { "Extracted volume ID from URI: $volumeIdFromUri" }

            // Find the matching storage volume by comparing volume IDs
            val volumes = storageManager2.storageVolumes
                .also { log(TAG, VERBOSE) { "Found ${it.size} volumes to check" } }

            if (volumes.isEmpty()) {
                log(TAG, WARN) { "No volumes available" }
                return null
            }

            val osStorage = volumes
                .onEach { volume ->
                    val volumeTreePath = volume.treeUri.path
                    val volumeId = volumeTreePath?.split("/tree/")?.lastOrNull()?.split(":")?.firstOrNull()
                    log(TAG, VERBOSE) { "Checking volume: ${volume.directory?.path}, volumeId=$volumeId" }
                }
                .firstOrNull { volume ->
                    val volumeTreePath = volume.treeUri.path
                    val volumeId = volumeTreePath?.split("/tree/")?.lastOrNull()?.split(":")?.firstOrNull()
                    volumeId == volumeIdFromUri
                }

            if (osStorage == null) {
                log(TAG, WARN) { "No matching volume found for volumeId=$volumeIdFromUri" }
                return null
            }
            log(TAG, VERBOSE) { "Matched volume: ${osStorage.directory?.path}" }

            if (osStorage.directory == null) {
                log(TAG, WARN) { "Matched volume has no directory!" }
                return null
            }
            val rootDir = osStorage.directory!!.toLocalPath()
            log(TAG, VERBOSE) { "Converted volume directory to LocalPath: $rootDir" }

            // Combine: volumeRoot + permissionPathSegments + safPath.segments
            val allSegments = permissionPathSegments + safPath.segments
            log(TAG, VERBOSE) { "Final path segments: root=$rootDir, segments=$allSegments" }
            val result = rootDir.child(*allSegments.toTypedArray())
            log(TAG, VERBOSE) { "Result: $result" }
            return result
        } catch (e: Exception) {
            log(TAG, ERROR) { "Failed to map $safPath: ${e.asLog()}" }
            null
        }
    }

    companion object {
        private val CACHE_SYNC_TIMEOUT = 2.seconds
        private val TAG = logTag("SAF", "Location", "Manager")
    }
}