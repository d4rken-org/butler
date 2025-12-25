package eu.darken.butler.common.storage

import android.net.Uri
import android.provider.DocumentsContract
import dagger.Reusable
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import java.io.File
import javax.inject.Inject

/**
 * Resolves external storage document URIs to local file paths.
 *
 * Handles URIs like:
 * - content://com.android.externalstorage.documents/root/primary
 * - content://com.android.externalstorage.documents/root/FD76-F8FE
 * - content://com.android.externalstorage.documents/document/primary:Download
 * - content://com.android.externalstorage.documents/document/FD76-F8FE:DCIM/Camera
 */
@Reusable
class DocumentUriResolver @Inject constructor(
    private val storageManager2: StorageManager2,
) {

    /**
     * Resolves a document URI to a local file path.
     *
     * @param uri The document URI to resolve
     * @return The resolved [LocalPath], or null if the URI cannot be resolved
     */
    fun resolve(uri: Uri): LocalPath? {
        if (uri.authority != EXTERNAL_STORAGE_AUTHORITY) {
            log(TAG, WARN) { "Unsupported document authority: ${uri.authority}" }
            return null
        }

        val docId = extractDocumentId(uri)
        if (docId == null) {
            log(TAG, WARN) { "No document ID found in URI: $uri" }
            return null
        }

        log(TAG) { "Document ID: $docId" }

        val (volumeId, subPath) = parseDocumentId(docId)
        log(TAG) { "Parsed volumeId=$volumeId, subPath=$subPath" }

        val volume = findVolume(volumeId)
        if (volume == null) {
            log(TAG, WARN) { "No matching volume found for volumeId: $volumeId" }
            return null
        }

        val directory = volume.directory
        if (directory == null) {
            log(TAG, WARN) { "Volume has no directory: ${volume.uuid}" }
            return null
        }

        return if (subPath != null) {
            LocalPath(File(directory, subPath))
        } else {
            LocalPath(directory)
        }
    }

    /**
     * Extracts the document ID from a URI.
     * Tries DocumentsContract APIs first, falls back to URI path parsing.
     */
    private fun extractDocumentId(uri: Uri): String? {
        // Try to get document ID first (for /document/ URIs)
        try {
            val docId = DocumentsContract.getDocumentId(uri)
            if (docId != null) return docId
        } catch (_: Exception) {
            // Not a document URI, try root
        }

        // Try to get root ID (for /root/ URIs)
        try {
            val rootId = DocumentsContract.getRootId(uri)
            if (rootId != null) return "root:$rootId"
        } catch (_: Exception) {
            // Not a root URI either
        }

        // Fall back to last path segment
        return uri.lastPathSegment
    }

    /**
     * Parses a document ID into volume ID and optional sub-path.
     *
     * Document ID formats:
     * - "root:primary" -> ("primary", null)
     * - "root:FD76-F8FE" -> ("FD76-F8FE", null)
     * - "primary" -> ("primary", null)
     * - "primary:Download" -> ("primary", "Download")
     * - "FD76-F8FE:DCIM/Camera" -> ("FD76-F8FE", "DCIM/Camera")
     */
    private fun parseDocumentId(docId: String): Pair<String, String?> {
        // Handle "root:xxx" format
        if (docId.startsWith(ROOT_PREFIX)) {
            val volumeId = docId.removePrefix(ROOT_PREFIX)
            return volumeId to null
        }

        // Handle "volumeId:path" format
        if (docId.contains(":")) {
            val volumeId = docId.substringBefore(":")
            val subPath = docId.substringAfter(":")
            return volumeId to subPath.takeIf { it.isNotEmpty() }
        }

        // Just a volume ID
        return docId to null
    }

    /**
     * Finds a storage volume by its ID.
     *
     * @param volumeId The volume ID ("primary" for internal storage, or UUID for external)
     * @return The matching [StorageVolumeX], or null if not found
     */
    private fun findVolume(volumeId: String): StorageVolumeX? {
        val volumes = storageManager2.storageVolumes

        return if (volumeId == PRIMARY_VOLUME_ID) {
            volumes.firstOrNull { it.isPrimary }
        } else {
            volumes.firstOrNull { it.uuid.equals(volumeId, ignoreCase = true) }
        }
    }

    companion object {
        private val TAG = logTag("Storage", "DocumentUriResolver")
        private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        private const val PRIMARY_VOLUME_ID = "primary"
        private const val ROOT_PREFIX = "root:"
    }
}
