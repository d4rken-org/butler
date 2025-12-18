package eu.darken.butler.provider.documents.core.writer

import android.content.Context
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.provider.documents.core.ButlerDocumentsProvider
import eu.darken.butler.provider.documents.core.DocumentIdCodec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles createDocument() calls from Android's DocumentsProvider API.
 *
 * Responsibilities:
 * - Create new files or directories in existing parent directories
 * - Handle name conflicts by generating unique names ("file (1).txt")
 * - Support both LocalPath and SAFPath parents
 * - Delegate file operations to GatewaySwitch
 */
@Singleton
class DocumentCreator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val codec: DocumentIdCodec,
    private val gatewaySwitch: GatewaySwitch,
) {

    /**
     * Create a new file or directory.
     *
     * @param parentDocumentId Document ID of parent directory
     * @param mimeType MIME type (MIME_TYPE_DIR for directories, anything else for files)
     * @param displayName Desired file/directory name
     * @return Document ID of created file/directory
     * @throws FileNotFoundException If parent doesn't exist
     * @throws IllegalArgumentException If parent is not a directory
     * @throws IOException If creation fails
     */
    suspend fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String {
        log(TAG, INFO) { "createDocument(parent=$parentDocumentId, mime=$mimeType, name=$displayName)" }

        try {
            val parentPath = codec.decode(parentDocumentId)
            log(TAG, INFO) { "Parent path: $parentPath" }

            val destinationPath = parentPath.child(displayName)

            val finalPath = if (gatewaySwitch.exists(destinationPath)) {
                log(TAG, INFO) { "Name conflict detected, generating unique name" }
                generateUniqueName(parentPath, displayName)
            } else {
                destinationPath
            }

            val isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR

            if (isDirectory) {
                log(TAG, INFO) { "Creating directory: $finalPath" }
                gatewaySwitch.createDir(finalPath, createParents = false)
            } else {
                log(TAG, INFO) { "Creating file: $finalPath" }
                gatewaySwitch.createFile(finalPath, createParents = false)
            }

            val documentId = codec.encode(finalPath)
            log(TAG, INFO) { "Created document: $documentId" }

            notifyParentChanged(parentDocumentId)

            return documentId

        } catch (e: Exception) {
            log(TAG, ERROR) { "createDocument failed: ${e.asLog()}" }
            throw e
        }
    }

    /**
     * Notify the system that the parent directory's children have changed.
     * This triggers an automatic UI refresh in the file picker.
     *
     * Uses buildChildDocumentsUri to notify about changes to the children collection,
     * which matches the URI pattern registered via setNotificationUri() in queryChildDocuments().
     */
    private fun notifyParentChanged(parentDocumentId: String) {
        val childrenUri = DocumentsContract.buildChildDocumentsUri(
            ButlerDocumentsProvider.AUTHORITY,
            parentDocumentId
        )
        context.contentResolver.notifyChange(childrenUri, null)
        log(TAG, VERBOSE) { "Notified children changed for parent: $childrenUri" }
    }

    /**
     * Generate a unique name by appending (1), (2), etc. until no conflict exists.
     *
     * Examples:
     * - "file.txt" exists → returns child path with "file (1).txt"
     * - "file (1).txt" exists → returns child path with "file (2).txt"
     */
    private suspend fun generateUniqueName(parentPath: APath<*>, originalName: String): APath<*> {
        val lastDotIndex = originalName.lastIndexOf('.')
        val baseName: String
        val extension: String

        if (lastDotIndex > 0 && lastDotIndex < originalName.length - 1) {
            baseName = originalName.take(lastDotIndex)
            extension = originalName.substring(lastDotIndex)
        } else {
            baseName = originalName
            extension = ""
        }

        var counter = 1
        var candidatePath: APath<*>
        do {
            val candidateName = "$baseName ($counter)$extension"
            candidatePath = parentPath.child(candidateName)
            counter++
        } while (gatewaySwitch.exists(candidatePath))

        log(TAG, INFO) { "Generated unique name: ${candidatePath.name}" }
        return candidatePath
    }

    companion object {
        private val TAG = logTag("Provider", "Documents", "Creator")
    }
}
