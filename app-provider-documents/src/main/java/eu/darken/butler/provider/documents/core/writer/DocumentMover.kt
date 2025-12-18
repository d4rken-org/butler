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
import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.provider.documents.core.ButlerDocumentsProvider
import eu.darken.butler.provider.documents.core.DocumentIdCodec
import kotlinx.coroutines.flow.last
import java.io.FileNotFoundException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles copyDocument() and moveDocument() calls from Android's DocumentsProvider API.
 *
 * Responsibilities:
 * - Copy files and directories to new parents (optimized native operations)
 * - Move files and directories to new parents (atomic when possible)
 * - Handle name conflicts by generating unique names ("file (1).txt")
 * - Support both LocalPath and SAFPath
 * - Delegate file operations to GatewaySwitch for cross-filesystem support
 */
@Singleton
class DocumentMover @Inject constructor(
    @ApplicationContext private val context: Context,
    private val codec: DocumentIdCodec,
    private val gatewaySwitch: GatewaySwitch,
) {

    /**
     * Copy a file or directory to a new parent directory.
     *
     * Uses native filesystem operations for 10x-100x performance vs byte-by-byte copy.
     *
     * @param sourceDocumentId Document ID to copy
     * @param targetParentDocumentId Parent directory to copy into
     * @return New document ID of the copied file/directory
     * @throws FileNotFoundException If source doesn't exist
     * @throws IllegalArgumentException If copying virtual document
     * @throws IOException If copy fails
     */
    suspend fun copyDocument(
        sourceDocumentId: String,
        targetParentDocumentId: String
    ): String {
        log(TAG, INFO) { "copyDocument(source=$sourceDocumentId, target=$targetParentDocumentId)" }

        try {
            // Validate: cannot copy virtual documents (check before decoding)
            if (codec.isVirtualDocument(sourceDocumentId)) {
                throw IllegalArgumentException("Cannot copy virtual document: $sourceDocumentId")
            }
            if (codec.isVirtualDocument(targetParentDocumentId)) {
                throw IllegalArgumentException("Cannot copy to virtual document: $targetParentDocumentId")
            }

            // Decode source and target paths
            val sourcePath = codec.decode(sourceDocumentId)
            val targetParentPath = codec.decode(targetParentDocumentId)
            log(TAG, INFO) { "Source: $sourcePath, Target parent: $targetParentPath" }

            // Calculate destination path
            val destinationPath = targetParentPath.child(sourcePath.name)

            // Handle name conflicts (generate unique name if needed)
            val finalPath = if (gatewaySwitch.exists(destinationPath)) {
                log(TAG, INFO) { "Name conflict detected, generating unique name" }
                generateUniqueName(targetParentPath, sourcePath.name)
            } else {
                destinationPath
            }

            // Perform copy operation using GatewaySwitch
            log(TAG, INFO) { "Copying $sourcePath -> $finalPath" }
            val copyState = gatewaySwitch.copy(
                sources = setOf(sourcePath),
                destination = targetParentPath,
                onIssue = null,  // Use default conflict handling
                options = CopyAction.Options(preserveAttributes = true)
            ).last()  // Wait for completion

            // Extract copied path from result
            when (copyState) {
                is CopyAction.State.Completed -> {
                    val copiedPair = copyState.copied.firstOrNull()
                        ?: throw IOException("Copy succeeded but no copied path returned")
                    val copiedPath = copiedPair.second.lookedUp

                    // Return new document ID
                    val newDocumentId = codec.encode(copiedPath)
                    log(TAG, INFO) { "Copied successfully, new ID: $newDocumentId" }

                    // Notify system about the new child in target parent
                    notifyChildrenChanged(targetParentDocumentId)

                    return newDocumentId
                }
                else -> throw IOException("Copy operation did not complete successfully")
            }

        } catch (e: Exception) {
            log(TAG, ERROR) { "copyDocument failed: ${e.asLog()}" }
            throw e
        }
    }

    /**
     * Move a file or directory to a new parent directory.
     *
     * Uses atomic filesystem operations when possible (instant metadata update).
     * Falls back to copy+delete for cross-filesystem moves.
     *
     * @param sourceDocumentId Document ID to move
     * @param sourceParentDocumentId Current parent directory (for validation)
     * @param targetParentDocumentId New parent directory to move into
     * @return New document ID at the target location
     * @throws FileNotFoundException If source doesn't exist
     * @throws IllegalArgumentException If moving virtual document
     * @throws IOException If move fails
     */
    suspend fun moveDocument(
        sourceDocumentId: String,
        sourceParentDocumentId: String,
        targetParentDocumentId: String
    ): String {
        log(
            TAG,
            INFO
        ) { "moveDocument(source=$sourceDocumentId, sourceParent=$sourceParentDocumentId, target=$targetParentDocumentId)" }

        try {
            // Optimization: if source and target parent are the same, no move needed
            if (sourceParentDocumentId == targetParentDocumentId) {
                log(TAG, INFO) { "Source and target parent are same, no-op" }
                return sourceDocumentId
            }

            // Validate: cannot move virtual documents (check before decoding)
            if (codec.isVirtualDocument(sourceDocumentId)) {
                throw IllegalArgumentException("Cannot move virtual document: $sourceDocumentId")
            }
            if (codec.isVirtualDocument(targetParentDocumentId)) {
                throw IllegalArgumentException("Cannot move to virtual document: $targetParentDocumentId")
            }

            // Decode paths
            val sourcePath = codec.decode(sourceDocumentId)
            val targetParentPath = codec.decode(targetParentDocumentId)
            log(TAG, INFO) { "Source: $sourcePath, Target parent: $targetParentPath" }

            // Calculate destination path
            val destinationPath = targetParentPath.child(sourcePath.name)

            // Handle name conflicts
            val finalPath = if (gatewaySwitch.exists(destinationPath)) {
                log(TAG, INFO) { "Name conflict detected, generating unique name" }
                generateUniqueName(targetParentPath, sourcePath.name)
            } else {
                destinationPath
            }

            // Perform move operation using GatewaySwitch
            log(TAG, INFO) { "Moving $sourcePath -> $finalPath" }
            val moveState = gatewaySwitch.move(
                sources = setOf(sourcePath),
                destination = targetParentPath,
                onIssue = null,  // Use default conflict handling
                options = MoveAction.Options(
                    attemptAtomicMove = true,  // Use instant metadata move when possible
                    preserveAttributes = true
                )
            ).last()  // Wait for completion

            // Extract moved path from result
            when (moveState) {
                is MoveAction.State.Completed -> {
                    val movedPair = moveState.movedFiles.firstOrNull()
                        ?: throw IOException("Move succeeded but no moved path returned")
                    val movedPath = movedPair.second.lookedUp

                    // Return new document ID
                    val newDocumentId = codec.encode(movedPath)
                    log(TAG, INFO) { "Moved successfully, new ID: $newDocumentId" }

                    // Notify system about changes to BOTH parents
                    notifyChildrenChanged(sourceParentDocumentId)  // Lost a child
                    notifyChildrenChanged(targetParentDocumentId)  // Gained a child

                    return newDocumentId
                }
                else -> throw IOException("Move operation did not complete successfully")
            }

        } catch (e: Exception) {
            log(TAG, ERROR) { "moveDocument failed: ${e.asLog()}" }
            throw e
        }
    }

    /**
     * Generate a unique name by appending (1), (2), etc. until no conflict exists.
     *
     * Examples:
     * - "file.txt" exists → returns child path with "file (1).txt"
     * - "file (1).txt" exists → returns child path with "file (2).txt"
     */
    private suspend fun generateUniqueName(parentPath: APath<*>, originalName: String): APath<*> {
        // Split into base name and extension
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

        // Try numbered variants until we find one that doesn't exist
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

    companion object {
        private val TAG = logTag("Provider", "Documents", "Mover")
    }
}
