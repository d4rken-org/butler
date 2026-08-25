package eu.darken.butler.common.storage.saf

import android.content.Intent
import android.provider.DocumentsContract
import dagger.Reusable
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.storage.StorageManager2
import java.io.File
import javax.inject.Inject

/**
 * Creates SAF picker intents pre-navigated to specific paths.
 * Used for just-in-time permission grants when accessing restricted directories.
 */
@Reusable
class SAFPickerIntentBuilder @Inject constructor(
    private val storageManager2: StorageManager2,
) {

    /**
     * Build volume-based SAFPath for picker navigation.
     * Returns path regardless of permission status - use ONLY for picker intent creation.
     */
    private fun buildVolumeBasedSAFPath(localPath: LocalPath): SAFPath? {
        return try {
            log(TAG, VERBOSE) { "buildVolumeBasedSAFPath() called with: $localPath" }

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

            SAFPath.build(
                base = osStorage.treeUri,
                segs = segments.toTypedArray(),
            )
        } catch (e: Exception) {
            log(TAG, ERROR) { "Failed to build volume-based SAFPath for $localPath: ${e.asLog()}" }
            null
        }
    }

    /**
     * Builds a SAF picker intent pre-navigated to the given path.
     *
     * This allows requesting permission for a specific directory (like Android/data)
     * by opening the picker already showing that directory.
     *
     * @param targetPath The local path to request access for
     * @return Intent to launch SAF picker, or null if path cannot be mapped
     */
    suspend fun buildPickerIntent(targetPath: LocalPath): Intent? {
        return try {
            log(TAG, VERBOSE) { "Building picker intent for targetPath: $targetPath" }

            val safPath = buildVolumeBasedSAFPath(targetPath)
            if (safPath == null) {
                log(TAG, WARN) { "Cannot map $targetPath to SAF path" }
                return null
            }

            log(TAG, VERBOSE) { "Mapped to SAFPath: treeRootUri=${safPath.treeRootUri}, segments=${safPath.segments}" }

            // Build navigation URI that pre-navigates picker to target directory
            val rootTreeUri = safPath.treeRootUri.toAndroidUri()
            val authority = rootTreeUri.authority
            val rootDocumentId = DocumentsContract.getTreeDocumentId(rootTreeUri)

            // Build full document ID from root + segments (e.g., "primary" + ["Android", "data"] = "primary:Android/data")
            val fullDocumentId = if (safPath.segments.isEmpty()) {
                rootDocumentId
            } else {
                "$rootDocumentId:${safPath.segments.joinToString("/")}"
            }

            // Build the tree URI with the full path included
            // This creates: content://.../tree/primary:Android/data (not just tree/primary)
            val fullTreeUri = DocumentsContract.buildTreeDocumentUri(authority, fullDocumentId)

            log(
                TAG,
                VERBOSE
            ) { "Root tree URI: $rootTreeUri, Full tree URI: $fullTreeUri, Full document ID: $fullDocumentId" }

            // Build the final navigation URI using the full tree URI
            // This creates: content://.../tree/primary:Android/data/document/primary:Android/data
            val navTreeUri = DocumentsContract.buildDocumentUriUsingTree(fullTreeUri, fullDocumentId)

            log(TAG) { "Created picker intent for $targetPath -> navigation URI: $navTreeUri" }

            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                putExtra("android.content.extra.SHOW_ADVANCED", true)
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, navTreeUri)
            }
        } catch (e: Exception) {
            log(TAG, ERROR) { "Failed to build picker intent for $targetPath: ${e.asLog()}" }
            null
        }
    }

    /**
     * Builds a SAF picker intent pre-navigated to a provider's own storage root.
     *
     * @param authority The documents provider authority
     * @param rootId The provider's root ID
     */
    fun buildPickerIntent(authority: String, rootId: String): Intent {
        // A tree URI would presuppose a grant for this authority, which is exactly what we're asking for.
        val rootUri = DocumentsContract.buildRootUri(authority, rootId)

        log(TAG) { "Created picker intent for $authority ($rootId) -> navigation URI: $rootUri" }

        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra("android.content.extra.SHOW_ADVANCED", true)
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, rootUri)
        }
    }

    companion object {
        private val TAG = logTag("SAF", "PickerBuilder")
    }
}
