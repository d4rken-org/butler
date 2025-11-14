package eu.darken.butler.provider.documents

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsProvider
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import eu.darken.butler.common.debug.logging.Logging.Priority.ERROR
import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.provider.documents.query.DocumentQueryHandler
import eu.darken.butler.provider.documents.query.RootQueryHandler
import eu.darken.butler.provider.documents.reader.DocumentReader
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
 *
 * Phase 1: Read-only support (query + open)
 * Phase 3: Write support (create, delete, rename)
 */
class ButlerDocumentsProvider : DocumentsProvider() {

    // Manual Hilt injection required for ContentProvider
    @Inject lateinit var rootQueryHandler: RootQueryHandler
    @Inject lateinit var documentQueryHandler: DocumentQueryHandler
    @Inject lateinit var documentReader: DocumentReader

    override fun onCreate(): Boolean {
        val context = context ?: return false

        // Perform manual Hilt injection
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            DocumentsProviderEntryPoint::class.java
        ).inject(this)

        log(TAG, INFO) { "ButlerDocumentsProvider initialized" }
        return true
    }

    /**
     * Return available storage roots shown in the file picker drawer.
     * Phase 1: Single "Butler" root.
     */
    override fun queryRoots(projection: Array<String>?): Cursor {
        return runBlocking {
            try {
                rootQueryHandler.queryRoots(projection)
            } catch (e: Exception) {
                log(TAG, ERROR) { "queryRoots failed: ${e.asLog()}" }
                MatrixCursor(projection ?: arrayOf())
            }
        }
    }

    /**
     * Return metadata for a single document (file or virtual document).
     */
    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        return runBlocking {
            try {
                documentQueryHandler.queryDocument(documentId, projection)
            } catch (e: Exception) {
                log(TAG, ERROR) { "queryDocument($documentId) failed: ${e.asLog()}" }
                MatrixCursor(projection ?: arrayOf())
            }
        }
    }

    /**
     * Return child documents for a directory or virtual document.
     */
    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?
    ): Cursor {
        return runBlocking {
            try {
                documentQueryHandler.queryChildDocuments(parentDocumentId, projection, sortOrder)
            } catch (e: Exception) {
                log(TAG, ERROR) { "queryChildDocuments($parentDocumentId) failed: ${e.asLog()}" }
                MatrixCursor(projection ?: arrayOf())
            }
        }
    }

    /**
     * Open a document for reading/writing.
     * Phase 1: Read-only (mode "r").
     * Phase 3: Write support (modes: w, wa, rw, rwt, wt).
     */
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

    // ========== Phase 3: Write Operations (Not Yet Supported) ==========

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String? {
        throw UnsupportedOperationException("Write operations not yet supported (Phase 3)")
    }

    override fun deleteDocument(documentId: String) {
        throw UnsupportedOperationException("Write operations not yet supported (Phase 3)")
    }

    override fun renameDocument(documentId: String, displayName: String): String? {
        throw UnsupportedOperationException("Write operations not yet supported (Phase 3)")
    }

    companion object {
        private val TAG = logTag("Provider", "Documents")
    }
}

/**
 * Hilt EntryPoint for manual dependency injection.
 * ContentProviders don't support constructor injection, so we use this pattern.
 */
@InstallIn(SingletonComponent::class)
@EntryPoint
interface DocumentsProviderEntryPoint {
    fun inject(provider: ButlerDocumentsProvider)
}
