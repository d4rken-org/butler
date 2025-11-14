package eu.darken.butler.provider.documents.writer

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.debug.logging.Logging.Priority.ERROR
import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.provider.documents.ButlerDocumentsProvider
import eu.darken.butler.provider.documents.core.DocumentIdCodec
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles deleteDocument() and renameDocument() calls from Android's DocumentsProvider API.
 *
 * Responsibilities:
 * - Delete files and directories recursively
 * - Revoke URI permissions for deleted documents (critical security requirement)
 * - Rename files and directories (via move operation)
 * - Handle name conflicts during rename
 * - Support both LocalPath and SAFPath
 */
@Singleton
class DocumentModifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val codec: DocumentIdCodec,
    private val gatewaySwitch: GatewaySwitch,
) {

    /**
     * Rename a file or directory.
     *
     * @param documentId Document ID to rename
     * @param displayName New name (can be same directory or different for move)
     * @return New document ID with updated path
     * @throws IllegalStateException If destination already exists
     * @throws IllegalArgumentException If renaming virtual document
     * @throws IOException If rename fails
     */
    suspend fun renameDocument(documentId: String, displayName: String): String {
        log(TAG, INFO) { "renameDocument(doc=$documentId, newName=$displayName)" }

        try {
            // Decode source document ID
            val sourcePath = codec.decode(documentId)
            log(TAG, INFO) { "Source path: $sourcePath" }

            // Check if name actually changed
            if (sourcePath.name == displayName) {
                log(TAG, INFO) { "Name unchanged, returning same document ID" }
                return documentId
            }

            // Build destination path
            val parentPath = sourcePath.parent
                ?: throw IllegalArgumentException("Cannot rename root path")

            val destinationPath = parentPath.child(displayName)

            // Check for conflicts
            if (gatewaySwitch.exists(destinationPath)) {
                throw IllegalStateException("Destination already exists: $destinationPath")
            }

            // Perform rename (via move operation)
            log(TAG, INFO) { "Renaming $sourcePath -> $destinationPath" }
            gatewaySwitch.move(sourcePath, destinationPath)

            // Return new document ID
            val newDocumentId = codec.encode(destinationPath)
            log(TAG, INFO) { "Renamed successfully, new ID: $newDocumentId" }

            // Notify system about the rename:
            // 1. Parent directory's children changed (file list needs refresh)
            val parentDocumentId = codec.encode(parentPath)
            notifyChildrenChanged(parentDocumentId)

            // 2. Optionally notify about the specific document changes (metadata)
            notifyDocumentChanged(documentId) // Old location removed
            notifyDocumentChanged(newDocumentId) // New location created

            return newDocumentId

        } catch (e: Exception) {
            log(TAG, ERROR) { "renameDocument failed: ${e.asLog()}" }
            throw e
        }
    }

    /**
     * Delete a file or directory.
     *
     * CRITICAL: Must revoke URI permissions for the deleted document and all children.
     * This prevents security issues where apps retain access to deleted files.
     *
     * @param documentId Document ID to delete
     * @throws IllegalArgumentException If deleting virtual document
     * @throws IOException If delete fails
     */
    suspend fun deleteDocument(documentId: String) {
        log(TAG, INFO) { "deleteDocument(doc=$documentId)" }

        try {
            // Decode document ID
            val path = codec.decode(documentId)
            log(TAG, INFO) { "Deleting path: $path" }

            // Lookup to check if it exists and get file type
            val lookup = try {
                gatewaySwitch.lookup(path, LookupOptions())
            } catch (e: FileNotFoundException) {
                log(TAG, WARN) { "Path doesn't exist, delete is idempotent: $path" }
                return
            }

            // Revoke permissions BEFORE deleting
            // This ensures URIs become invalid atomically with deletion
            revokePermissionsRecursively(path, lookup)

            // Delete file/directory
            log(TAG, INFO) { "Deleting ${lookup.fileType}: $path" }
            gatewaySwitch.delete(path, recursive = true)

            log(TAG, INFO) { "Deleted successfully: $path" }

            // Notify system about the deletion
            // Notify parent directory's children collection so it refreshes the file list
            val parentPath = path.parent
            if (parentPath != null) {
                val parentDocumentId = codec.encode(parentPath)
                notifyChildrenChanged(parentDocumentId)
            }

        } catch (e: FileNotFoundException) {
            // Idempotent - deleting non-existent file succeeds
            log(TAG, WARN) { "Delete target not found (idempotent): ${e.message}" }
        } catch (e: IllegalArgumentException) {
            // Virtual documents should not be deleted
            log(TAG, ERROR) { "Cannot delete virtual document: ${e.asLog()}" }
            throw e
        } catch (e: Exception) {
            log(TAG, ERROR) { "deleteDocument failed: ${e.asLog()}" }
            throw e
        }
    }

    /**
     * Recursively revoke URI permissions for a path and all its children.
     *
     * For files: Revoke single URI permission
     * For directories: Recursively revoke permissions for all children first, then parent
     *
     * Uses context.revokeUriPermission() which is system-wide and multi-process safe.
     */
    private suspend fun revokePermissionsRecursively(path: APath<*>, lookup: APathLookup<*>) {
        log(TAG, INFO) { "Revoking permissions for: $path (${lookup.fileType})" }

        // If directory, recursively revoke children first
        if (lookup.fileType == FileType.DIRECTORY) {
            try {
                val children = gatewaySwitch.lookupFiles(path, LookupOptions())
                children.forEach { childLookup ->
                    revokePermissionsRecursively(childLookup.lookedUp, childLookup)
                }
            } catch (e: Exception) {
                log(TAG, WARN) { "Failed to list children for permission revocation: ${e.asLog()}" }
            }
        }

        // Revoke permissions for this path
        val documentId = codec.encode(path)
        val uri = DocumentsContract.buildDocumentUri(ButlerDocumentsProvider.AUTHORITY, documentId)

        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        context.revokeUriPermission(uri, flags)
        log(TAG, INFO) { "Revoked permissions for: $uri" }
    }

    /**
     * Notify the system that a directory's children have changed.
     * This triggers an automatic UI refresh in the file picker.
     *
     * Uses buildChildDocumentsUri to notify about changes to the children collection,
     * which matches the URI pattern registered via setNotificationUri() in queryChildDocuments().
     */
    private fun notifyChildrenChanged(parentDocumentId: String) {
        val childrenUri = DocumentsContract.buildChildDocumentsUri(
            ButlerDocumentsProvider.AUTHORITY,
            parentDocumentId
        )
        context.contentResolver.notifyChange(childrenUri, null)
        log(TAG, VERBOSE) { "Notified children changed: $childrenUri" }
    }

    /**
     * Notify the system that a document itself has changed (for rename operations).
     * This is for the document's metadata, not its children.
     */
    private fun notifyDocumentChanged(documentId: String) {
        val uri = DocumentsContract.buildDocumentUri(
            ButlerDocumentsProvider.AUTHORITY,
            documentId
        )
        context.contentResolver.notifyChange(uri, null)
        log(TAG, VERBOSE) { "Notified document changed: $uri" }
    }

    companion object {
        private val TAG = logTag("Provider", "Documents", "Modifier")
    }
}
