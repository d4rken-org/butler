package eu.darken.butler.common.files.saf

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.UriPermission
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.rngString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
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
    private val contentResolver: ContentResolver,
    private val preferences: SAFLocationPreferences,
    @AppScope private val appScope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider,
) : SAFLocationManager {

    private val refreshTrigger = MutableStateFlow(rngString)

    override fun getGrantedLocations(): Flow<List<SAFLocation>> = combine(
        refreshTrigger,
        preferences.locations.flow,
    ) { _, prefs ->
        log(TAG) { "Refreshing granted locations" }

        val locations = contentResolver.persistedUriPermissions
            .mapNotNull { permission ->
                try {
                    createSAFLocation(permission, prefs)
                } catch (e: Exception) {
                    log(TAG, WARN) { "Failed to create SAFLocation from $permission: $e" }
                    null
                }
            }
            .filterNot { it.isHidden }
            .sortedWith(
                compareByDescending<SAFLocation> { it.isPinned }
                    .thenBy { it.grantedAt }
            )

        log(TAG) { "Found ${locations.size} granted locations (${prefs.size} with custom prefs)" }
        locations
    }

    override fun findPermissionFor(path: SAFPath): SAFPermissionMatch? {
        val locations = contentResolver.persistedUriPermissions
            .mapNotNull { perm ->
                try {
                    createSAFLocation(perm, emptyMap())
                } catch (e: Exception) {
                    null
                }
            }

        return findMatchingLocation(path, locations)
    }

    override fun hasAccessTo(path: SAFPath): Boolean {
        return findPermissionFor(path)?.location?.hasFullAccess == true
    }

    override fun getDocFileFor(path: SAFPath): SAFDocFile? {
        val match = findPermissionFor(path) ?: return null

        val treeUri = Uri.parse(match.location.treeUri)
        val targetTreeUri = SAFDocFile.buildTreeUri(treeUri, match.missingSegments)
        return SAFDocFile.fromTreeUri(context, contentResolver, targetTreeUri)
    }

    override suspend fun grantPermission(treeUri: Uri) = withContext(dispatcherProvider.IO) {
        log(TAG) { "grantPermission(treeUri=$treeUri)" }

        try {
            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            log(TAG) { "Successfully granted permission for $treeUri" }
            refresh()
        } catch (e: SecurityException) {
            log(TAG, ERROR) { "Failed to take persistable URI permission: $e" }
            throw e
        }
    }

    override suspend fun revokePermission(locationId: String) = withContext(dispatcherProvider.IO) {
        log(TAG) { "revokePermission(locationId=$locationId)" }

        // Find the URI for this location ID
        val permission = contentResolver.persistedUriPermissions.find { perm ->
            generateLocationId(perm.uri) == locationId
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

        // Remove user preferences
        preferences.removeLocationPreference(locationId)
        refresh()
    }

    override suspend fun setLocationLabel(locationId: String, label: String?) {
        log(TAG, VERBOSE) { "setLocationLabel(locationId=$locationId, label=$label)" }
        preferences.updateLocationPreference(locationId) { it.copy(userLabel = label) }
    }

    override suspend fun setLocationHidden(locationId: String, hidden: Boolean) {
        log(TAG, VERBOSE) { "setLocationHidden(locationId=$locationId, hidden=$hidden)" }
        preferences.updateLocationPreference(locationId) { it.copy(isHidden = hidden) }
    }

    override suspend fun setLocationPinned(locationId: String, pinned: Boolean) {
        log(TAG, VERBOSE) { "setLocationPinned(locationId=$locationId, pinned=$pinned)" }
        preferences.updateLocationPreference(locationId) { it.copy(isPinned = pinned) }
    }

    override suspend fun refresh() {
        log(TAG) { "refresh()" }
        refreshTrigger.value = rngString
    }

    // --- Private Helpers ---

    /**
     * Create a SAFLocation from a UriPermission and user preferences
     */
    private fun createSAFLocation(
        permission: UriPermission,
        prefs: Map<String, LocationPreference>,
    ): SAFLocation {
        val locationId = generateLocationId(permission.uri)
        val userPrefs = prefs[locationId]

        return SAFLocation(
            id = locationId,
            treeUri = permission.uri.toString(),
            path = SAFPath.build(permission.uri),
            hasReadPermission = permission.isReadPermission,
            hasWritePermission = permission.isWritePermission,
            grantedAt = Instant.fromEpochMilliseconds(permission.persistedTime),
            userLabel = userPrefs?.userLabel,
            isHidden = userPrefs?.isHidden ?: false,
            isPinned = userPrefs?.isPinned ?: false,
        )
    }

    /**
     * Generate a stable location ID from a tree URI
     */
    private fun generateLocationId(uri: Uri): String {
        return uri.toString().hashCode().toString()
    }

    /**
     * Find the best matching location for a given SAF path.
     *
     * Adapted from SAFPathExtensions.findPermission()
     */
    private fun findMatchingLocation(
        path: SAFPath,
        locations: List<SAFLocation>,
    ): SAFPermissionMatch? {
        val targetSegments = path.segments.toMutableList()
        val missingSegments = mutableListOf<String>()

        // Convert locations to permission-like structure for matching
        val availablePermissions = locations
            .filter { it.hasReadPermission && it.hasWritePermission }
            .map { location ->
                val uri = Uri.parse(location.treeUri)
                val segments = uri.path!!.split(":").last().split(File.separator)
                location to segments.filter { it.isNotEmpty() }
            }
            .sortedByDescending { it.second.size }

        // Try to find matching permission by walking up the path hierarchy
        while (true) {
            for ((location, permSegments) in availablePermissions) {
                val uri = Uri.parse(location.treeUri)
                val samePrefix = path.pathUri.path!!.split(":").first() == uri.path!!.split(":").first()

                if (samePrefix && permSegments == targetSegments) {
                    return SAFPermissionMatch(
                        location = location,
                        missingSegments = missingSegments,
                    )
                }
            }

            targetSegments.removeLastOrNull()?.also { missingSegments.add(0, it) } ?: break
        }

        return null
    }

    companion object {
        private val TAG = logTag("SAF", "LocationManager")
    }
}
