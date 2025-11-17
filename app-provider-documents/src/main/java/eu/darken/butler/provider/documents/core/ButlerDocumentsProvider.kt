package eu.darken.butler.provider.documents.core

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsProvider
import dagger.hilt.android.EntryPointAccessors
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.extensions.isAncestorOf
import eu.darken.butler.provider.documents.core.query.DocumentQueryHandler
import eu.darken.butler.provider.documents.core.query.RootQueryHandler
import eu.darken.butler.provider.documents.core.reader.DocumentReader
import eu.darken.butler.provider.documents.core.writer.DocumentCreator
import eu.darken.butler.provider.documents.core.writer.DocumentModifier
import eu.darken.butler.provider.documents.core.writer.DocumentMover
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * Butler's DocumentsProvider implementation for system file picker integration.
 *
 * Architecture:
 * - This class is a thin integration layer delegating to specialized handlers
 * - RootQueryHandler: Handles queryRoots() calls
 * - DocumentQueryHandler: Handles document/child queries
 * - DocumentReader: Handles file opening
 *
 * Threading:
 * - DocumentsProvider methods run on Binder thread pool
 * - Handlers use coroutines, so we wrap calls in runBlocking
 * - All handlers are thread-safe singletons
 */
class ButlerDocumentsProvider : DocumentsProvider() {

    @Inject lateinit var codec: DocumentIdCodec
    @Inject lateinit var rootQueryHandler: RootQueryHandler
    @Inject lateinit var documentQueryHandler: DocumentQueryHandler
    @Inject lateinit var documentReader: DocumentReader
    @Inject lateinit var documentCreator: DocumentCreator
    @Inject lateinit var documentModifier: DocumentModifier
    @Inject lateinit var documentMover: DocumentMover

    override fun onCreate(): Boolean {
        val context = context ?: return false

        EntryPointAccessors.fromApplication(
            context.applicationContext,
            DocumentsProviderEntryPoint::class.java
        ).inject(this)

        log(TAG, INFO) { "ButlerDocumentsProvider initialized" }
        return true
    }

    override fun queryRoots(projection: Array<String>?): Cursor = runBlocking {
        try {
            rootQueryHandler.queryRoots(projection)
        } catch (e: Exception) {
            log(TAG, ERROR) { "queryRoots failed: ${e.asLog()}" }
            MatrixCursor(projection ?: arrayOf())
        }
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor = runBlocking {
        try {
            documentQueryHandler.queryDocument(documentId, projection)
        } catch (e: Exception) {
            log(TAG, ERROR) { "queryDocument($documentId) failed: ${e.asLog()}" }
            MatrixCursor(projection ?: arrayOf())
        }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?
    ): Cursor = runBlocking {
        try {
            documentQueryHandler.queryChildDocuments(parentDocumentId, projection, sortOrder)
        } catch (e: Exception) {
            log(TAG, ERROR) { "queryChildDocuments($parentDocumentId) failed: ${e.asLog()}" }
            MatrixCursor(projection ?: arrayOf())
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor = runBlocking {
        try {
            documentReader.openDocument(documentId, mode, signal)
        } catch (e: Exception) {
            log(TAG, ERROR) { "openDocument($documentId, $mode) failed: ${e.asLog()}" }
            throw e // Re-throw for proper client-side error handling
        }
    }

    /**
     * Create a new file or directory.
     * Handles name conflicts by generating unique names ("file (1).txt").
     */
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String = runBlocking {
        try {
            log(TAG, INFO) { "createDocument(parent=$parentDocumentId, mime=$mimeType, name=$displayName)" }
            documentCreator.createDocument(parentDocumentId, mimeType, displayName)
        } catch (e: Exception) {
            log(TAG, ERROR) { "createDocument failed: ${e.asLog()}" }
            throw e // Re-throw for proper client-side error handling
        }
    }

    /**
     * Delete a file or directory recursively.
     * Revokes URI permissions for deleted documents and all children.
     */
    override fun deleteDocument(documentId: String) = runBlocking {
        try {
            log(TAG, INFO) { "deleteDocument($documentId)" }
            documentModifier.deleteDocument(documentId)
        } catch (e: Exception) {
            log(TAG, ERROR) { "deleteDocument failed: ${e.asLog()}" }
            throw e
        }
    }

    /**
     * Rename a file or directory.
     * Returns new document ID with updated path.
     */
    override fun renameDocument(documentId: String, displayName: String): String = runBlocking {
        try {
            log(TAG, INFO) { "renameDocument($documentId, $displayName)" }
            documentModifier.renameDocument(documentId, displayName)
        } catch (e: Exception) {
            log(TAG, ERROR) { "renameDocument failed: ${e.asLog()}" }
            throw e
        }
    }

    /**
     * Test if documentId is a descendant (child, grandchild, etc.) of parentDocumentId.
     * Required for ACTION_OPEN_DOCUMENT_TREE to enable copy/move destination selection.
     *
     * Performance critical - called frequently during file picker operations.
     *
     * @param parentDocumentId Parent document ID
     * @param documentId Document ID to test
     * @return true if documentId is any descendant of parentDocumentId, false otherwise
     */
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        return try {
            log(TAG, VERBOSE) { "isChildDocument(parent=$parentDocumentId, child=$documentId)" }

            if (codec.isVirtualDocument(parentDocumentId)) {
                log(TAG, VERBOSE) { "Parent is virtual document, returning false" }
                return false
            }
            if (codec.isVirtualDocument(documentId)) {
                log(TAG, VERBOSE) { "Child is virtual document, returning false" }
                return false
            }

            if (parentDocumentId == documentId) {
                log(TAG, VERBOSE) { "Same document, returning false" }
                return false
            }

            val parentPath = codec.decode(parentDocumentId)
            val childPath = codec.decode(documentId)

            val isChild = parentPath.isAncestorOf(childPath)

            log(TAG, VERBOSE) { "isChildDocument result: $isChild (${parentPath.path} -> ${childPath.path})" }
            isChild
        } catch (e: Exception) {
            log(TAG, ERROR) { "isChildDocument($parentDocumentId, $documentId) failed: ${e.asLog()}" }
            false // Safe default: deny relationship on error
        }
    }

    /**
     * Copy a document to a new parent directory.
     * Uses native filesystem operations for optimal performance (10x-100x faster than byte-by-byte).
     *
     * @param sourceDocumentId Document ID to copy
     * @param targetParentDocumentId Parent directory to copy into
     * @return New document ID of the copied document
     */
    override fun copyDocument(
        sourceDocumentId: String,
        targetParentDocumentId: String
    ): String = runBlocking {
        try {
            log(TAG, INFO) { "copyDocument($sourceDocumentId, $targetParentDocumentId)" }
            documentMover.copyDocument(sourceDocumentId, targetParentDocumentId)
        } catch (e: Exception) {
            log(TAG, ERROR) { "copyDocument failed: ${e.asLog()}" }
            throw e
        }
    }

    /**
     * Move a document to a new parent directory.
     * Uses atomic filesystem operations when possible (instant metadata update).
     *
     * @param sourceDocumentId Document ID to move
     * @param sourceParentDocumentId Current parent directory (for validation)
     * @param targetParentDocumentId New parent directory to move into
     * @return New document ID at the target location
     */
    override fun moveDocument(
        sourceDocumentId: String,
        sourceParentDocumentId: String,
        targetParentDocumentId: String
    ): String = runBlocking {
        try {
            log(TAG, INFO) { "moveDocument($sourceDocumentId, $sourceParentDocumentId, $targetParentDocumentId)" }
            documentMover.moveDocument(sourceDocumentId, sourceParentDocumentId, targetParentDocumentId)
        } catch (e: Exception) {
            log(TAG, ERROR) { "moveDocument failed: ${e.asLog()}" }
            throw e
        }
    }

    companion object {
        private val TAG = logTag("Provider", "Documents")

        /**
         * DocumentsProvider authority.
         * Must match the android:authorities attribute in AndroidManifest.xml.
         * Note: Cannot be const because BuildConfig is not a constant expression.
         * Using lazy initialization to avoid BuildConfigWrap initialization issues in unit tests.
         */
        val AUTHORITY: String by lazy { "${BuildConfigWrap.APPLICATION_ID}.provider.documents" }
    }
}

