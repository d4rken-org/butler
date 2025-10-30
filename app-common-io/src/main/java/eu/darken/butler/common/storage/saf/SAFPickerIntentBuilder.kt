package eu.darken.butler.common.storage.saf

import android.content.Intent
import android.provider.DocumentsContract
import dagger.Reusable
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.storage.PathMapper
import javax.inject.Inject

/**
 * Creates SAF picker intents pre-navigated to specific paths.
 * Used for just-in-time permission grants when accessing restricted directories.
 */
@Reusable
class SAFPickerIntentBuilder @Inject constructor(
    private val pathMapper: PathMapper,
) {

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

            val safPath = pathMapper.toSAFPath(targetPath)
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

            log(TAG, VERBOSE) { "Root tree URI: $rootTreeUri, Full tree URI: $fullTreeUri, Full document ID: $fullDocumentId" }

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

    companion object {
        private val TAG = logTag("SAF", "PickerBuilder")
    }
}
