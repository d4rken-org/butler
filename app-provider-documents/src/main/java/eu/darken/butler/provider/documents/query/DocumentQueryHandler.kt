package eu.darken.butler.provider.documents.query

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document.*
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.provider.documents.core.DocumentIdCodec
import eu.darken.butler.provider.documents.core.ProviderLocation
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles queryDocument() and queryChildDocuments() calls from Android's DocumentsProvider API.
 *
 * Responsibilities:
 * - Query single document metadata (queryDocument)
 * - List directory contents (queryChildDocuments)
 * - Handle virtual documents ("butler", "device|self")
 * - Enumerate storage locations dynamically
 * - Use GatewaySwitch for filesystem access
 */
@Singleton
class DocumentQueryHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val codec: DocumentIdCodec,
    private val gatewaySwitch: GatewaySwitch,
) {

    /**
     * Query metadata for a single document.
     *
     * @param documentId Document ID to query
     * @param projection Columns to return (null = all columns)
     * @return Cursor with single row containing document metadata
     */
    suspend fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        log(TAG, INFO) { "queryDocument($documentId)" }

        val resolvedProjection = projection ?: DEFAULT_DOCUMENT_PROJECTION
        val cursor = MatrixCursor(resolvedProjection)

        try {
            when {
                documentId == ProviderLocation.Root.Butler.rootDocumentId -> {
                    // Virtual: Butler root
                    cursor.addVirtualDocument(
                        documentId = documentId,
                        displayName = ProviderLocation.Root.Butler.title.get(context),
                        mimeType = MIME_TYPE_DIR,
                        flags = ProviderLocation.Root.Butler.flags,
                        icon = ProviderLocation.Root.Butler.icon,
                    )
                }

                documentId == ProviderLocation.Home.Device.documentId -> {
                    // Virtual: Device home
                    cursor.addVirtualDocument(
                        documentId = documentId,
                        displayName = ProviderLocation.Home.Device.title.get(context),
                        mimeType = MIME_TYPE_DIR,
                        flags = ProviderLocation.Home.Device.flags,
                        icon = ProviderLocation.Home.Device.icon,
                    )
                }

                else -> {
                    // Real filesystem path - decode and lookup
                    val path = codec.decode(documentId)
                    val lookup = gatewaySwitch.lookup(path, LookupOptions())
                    cursor.addFilesystemDocument(documentId, lookup)
                }
            }
        } catch (e: Exception) {
            log(TAG, WARN) { "queryDocument($documentId) failed: ${e.asLog()}" }
            // Return empty cursor on error
        }

        return cursor
    }

    /**
     * Query children of a directory/virtual document.
     *
     * @param parentDocumentId Parent document ID
     * @param projection Columns to return (null = all columns)
     * @param sortOrder Sort order (unused - Phase 2+)
     * @return Cursor with child documents
     */
    suspend fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?
    ): Cursor {
        log(TAG, INFO) { "queryChildDocuments($parentDocumentId)" }

        val resolvedProjection = projection ?: DEFAULT_DOCUMENT_PROJECTION
        val cursor = MatrixCursor(resolvedProjection)

        try {
            when {
                parentDocumentId == ProviderLocation.Root.Butler.rootDocumentId -> {
                    // Butler root → return Device home
                    cursor.addVirtualDocument(
                        documentId = ProviderLocation.Home.Device.documentId,
                        displayName = ProviderLocation.Home.Device.title.get(context),
                        mimeType = MIME_TYPE_DIR,
                        flags = ProviderLocation.Home.Device.flags,
                        icon = ProviderLocation.Home.Device.icon,
                    )
                }

                parentDocumentId == ProviderLocation.Home.Device.documentId -> {
                    // Device home → enumerate storage locations dynamically
                    enumerateStorageLocations(cursor)
                }

                else -> {
                    // Real filesystem path - decode and list directory
                    val path = codec.decode(parentDocumentId)
                    val children = gatewaySwitch.lookupFiles(path, LookupOptions())

                    children.forEach { childLookup ->
                        val childDocumentId = codec.encode(childLookup.lookedUp)
                        cursor.addFilesystemDocument(childDocumentId, childLookup)
                    }
                }
            }
        } catch (e: Exception) {
            log(TAG, WARN) { "queryChildDocuments($parentDocumentId) failed: ${e.asLog()}" }
            // Return empty cursor on error
        }

        log(TAG, INFO) { "queryChildDocuments($parentDocumentId) returning ${cursor.count} children" }
        return cursor
    }

    /**
     * Enumerate available storage locations under Device home.
     * Phase 1: Only root filesystem ("/").
     * Phase 2+: Auto-detect internal storage, SD cards, SAF trees.
     */
    private fun enumerateStorageLocations(cursor: MatrixCursor) {
        // Phase 1: Root filesystem only
        val rootPath = LocalPath.build("/")
        val rootDocumentId = codec.encode(rootPath)

        cursor.addVirtualDocument(
            documentId = rootDocumentId,
            displayName = "Root Filesystem", // TODO: Use string resource
            mimeType = MIME_TYPE_DIR,
            flags = FLAG_DIR_SUPPORTS_CREATE,
            icon = android.R.drawable.ic_menu_view,
        )

        // Phase 2+: Auto-detect storage volumes
        // - Internal storage: /storage/emulated/0
        // - SD cards: /storage/{uuid}
        // - SAF trees: User-granted locations
    }

    /**
     * Add a virtual document row to the cursor.
     * Virtual documents are navigation nodes without real filesystem paths.
     */
    private fun MatrixCursor.addVirtualDocument(
        documentId: String,
        displayName: String,
        mimeType: String,
        flags: Int,
        icon: Int,
    ) {
        newRow().apply {
            add(COLUMN_DOCUMENT_ID, documentId)
            add(COLUMN_DISPLAY_NAME, displayName)
            add(COLUMN_MIME_TYPE, mimeType)
            add(COLUMN_FLAGS, flags)
            add(COLUMN_ICON, icon)
            add(COLUMN_SIZE, null)
            add(COLUMN_LAST_MODIFIED, null)
        }
    }

    /**
     * Add a filesystem document row to the cursor.
     * Uses APathLookup metadata from GatewaySwitch.
     */
    private fun MatrixCursor.addFilesystemDocument(
        documentId: String,
        lookup: APathLookup<*>
    ) {
        val mimeType = when (lookup.fileType) {
            FileType.DIRECTORY -> MIME_TYPE_DIR
            FileType.FILE -> getMimeType(lookup.name)
            FileType.SYMBOLIC_LINK -> getMimeType(lookup.name) // TODO: Resolve symlink type
            FileType.UNKNOWN -> "application/octet-stream"
        }

        val flags = when (lookup.fileType) {
            FileType.DIRECTORY -> FLAG_DIR_SUPPORTS_CREATE
            FileType.FILE -> FLAG_SUPPORTS_WRITE or FLAG_SUPPORTS_DELETE // Phase 3
            FileType.SYMBOLIC_LINK -> 0
            FileType.UNKNOWN -> 0
        }

        newRow().apply {
            add(COLUMN_DOCUMENT_ID, documentId)
            add(COLUMN_DISPLAY_NAME, lookup.name)
            add(COLUMN_MIME_TYPE, mimeType)
            add(COLUMN_FLAGS, flags)
            add(COLUMN_SIZE, lookup.size)
            add(COLUMN_LAST_MODIFIED, lookup.modifiedAt?.toEpochMilliseconds())
            add(COLUMN_ICON, null) // Use system default icon for mime type
        }
    }

    /**
     * Get MIME type for a file based on its extension.
     * Phase 1: Simple extension-based detection.
     * Phase 2+: Use MimeInfo from app-common-io.
     */
    private fun getMimeType(filename: String): String {
        val extension = filename.substringAfterLast('.', "")
        return when (extension.lowercase()) {
            "txt" -> "text/plain"
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "mp4" -> "video/mp4"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    companion object {
        private val TAG = logTag("Provider", "Documents", "DocumentQuery")

        /**
         * Default projection when none specified.
         */
        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            COLUMN_DOCUMENT_ID,
            COLUMN_DISPLAY_NAME,
            COLUMN_MIME_TYPE,
            COLUMN_FLAGS,
            COLUMN_SIZE,
            COLUMN_LAST_MODIFIED,
            COLUMN_ICON,
        )
    }
}
