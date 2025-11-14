package eu.darken.butler.provider.documents.query

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document.*
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.saf.location.SAFLocationManager
import eu.darken.butler.common.storage.StorageManager2
import eu.darken.butler.permissions.core.PathPermissionCheck
import eu.darken.butler.permissions.core.PathRequirements
import eu.darken.butler.provider.documents.ButlerDocumentsProvider
import eu.darken.butler.provider.documents.R
import eu.darken.butler.provider.documents.core.DocumentIdCodec
import eu.darken.butler.provider.documents.core.ProviderLocation
import eu.darken.butler.setup.core.SetupModule
import kotlinx.coroutines.flow.first
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
    private val storageManager2: StorageManager2,
    private val safLocationManager: SAFLocationManager,
    private val pathPermissionCheck: PathPermissionCheck,
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
                    cursor.addVirtualDocument(
                        documentId = documentId,
                        displayName = ProviderLocation.Root.Butler.title.get(context),
                        mimeType = MIME_TYPE_DIR,
                        flags = ProviderLocation.Root.Butler.flags,
                        icon = ProviderLocation.Root.Butler.icon,
                    )
                }

                documentId == ProviderLocation.Home.Device.documentId -> {
                    cursor.addVirtualDocument(
                        documentId = documentId,
                        displayName = ProviderLocation.Home.Device.title.get(context),
                        mimeType = MIME_TYPE_DIR,
                        flags = ProviderLocation.Home.Device.flags,
                        icon = ProviderLocation.Home.Device.icon,
                    )
                }

                else -> {
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
     * @param sortOrder Sort order (unused)
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

        val notificationUri = DocumentsContract.buildChildDocumentsUri(
            ButlerDocumentsProvider.AUTHORITY,
            parentDocumentId
        )
        cursor.setNotificationUri(context.contentResolver, notificationUri)
        log(TAG, VERBOSE) { "Set notification URI: $notificationUri" }

        try {
            when {
                parentDocumentId == ProviderLocation.Root.Butler.rootDocumentId -> {
                    cursor.addVirtualDocument(
                        documentId = ProviderLocation.Home.Device.documentId,
                        displayName = ProviderLocation.Home.Device.title.get(context),
                        mimeType = MIME_TYPE_DIR,
                        flags = ProviderLocation.Home.Device.flags,
                        icon = ProviderLocation.Home.Device.icon,
                    )
                }

                parentDocumentId == ProviderLocation.Home.Device.documentId -> {
                    enumerateStorageLocations(cursor)
                }

                else -> {
                    val path = codec.decode(parentDocumentId)
                    val requirements = pathPermissionCheck.monitor(path).first()

                    if (requirements.needsAction) {
                        log(TAG, WARN) { "Path $path requires permissions (${requirements.combos}), returning error" }
                        val errorMessage = buildPermissionErrorMessage(requirements)
                        return ErrorMatrixCursor(resolvedProjection, errorMessage)
                    } else {
                        val children = gatewaySwitch.lookupFiles(path, LookupOptions())

                        children.forEach { childLookup ->
                            val childDocumentId = codec.encode(childLookup.lookedUp)
                            cursor.addFilesystemDocument(childDocumentId, childLookup)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log(TAG, WARN) { "queryChildDocuments($parentDocumentId) failed: ${e.asLog()}" }
            // Return error cursor for user feedback
            return ErrorMatrixCursor(
                resolvedProjection,
                context.getString(R.string.documents_error_generic)
            )
        }

        log(TAG, INFO) { "queryChildDocuments($parentDocumentId) returning ${cursor.count} children" }
        return cursor
    }

    /**
     * Enumerate available storage locations under Device home.
     * Mirrors Explorer's DeviceLocationLoader logic:
     * - Root filesystem ("/") - only if accessible
     * - Storage volumes (internal storage + SD cards) - only if accessible
     * - SAF locations (user-granted trees) - always shown (permissions granted)
     */
    private suspend fun enumerateStorageLocations(cursor: MatrixCursor) {
        val rootPath = LocalPath.build("/")
        val rootRequirements = pathPermissionCheck.monitor(rootPath).first()

        if (!rootRequirements.needsAction) {
            log(TAG, INFO) { "Root filesystem accessible, adding to list" }
            cursor.addVirtualDocument(
                documentId = codec.encode(rootPath),
                displayName = context.getString(R.string.documents_storage_root_label),
                mimeType = MIME_TYPE_DIR,
                flags = FLAG_DIR_SUPPORTS_CREATE,
                icon = android.R.drawable.ic_menu_view,
            )
        } else {
            log(TAG, INFO) { "Root filesystem requires permissions (${rootRequirements.combos}), filtering out" }
        }

        storageManager2.storageVolumes.forEachIndexed { index, volume ->
            val path = volume.directory?.let { LocalPath.build(it) }
                ?: volume.path?.let { LocalPath.build(it) }
                ?: return@forEachIndexed

            val requirements = pathPermissionCheck.monitor(path).first()

            if (!requirements.needsAction) {
                val displayName = volume.userLabel?.takeIf { it.isNotBlank() }
                    ?: when (index) {
                        0 -> context.getString(R.string.documents_storage_internal_label)
                        else -> context.getString(R.string.documents_storage_sd_card_label)
                    }

                log(TAG, INFO) { "Storage volume accessible: $displayName ($path)" }
                cursor.addVirtualDocument(
                    documentId = codec.encode(path),
                    displayName = displayName,
                    mimeType = MIME_TYPE_DIR,
                    flags = FLAG_DIR_SUPPORTS_CREATE,
                    icon = android.R.drawable.ic_menu_view,
                )
            } else {
                log(TAG, INFO) { "Storage volume requires permissions: $path (${requirements.combos}), filtering out" }
            }
        }

        val safLocations = safLocationManager.locations.first()
        safLocations.forEach { location ->
            log(TAG, INFO) { "SAF location accessible: ${location.displayName.get(context)}" }
            cursor.addVirtualDocument(
                documentId = codec.encode(location.path),
                displayName = location.displayName.get(context),
                mimeType = MIME_TYPE_DIR,
                flags = FLAG_DIR_SUPPORTS_CREATE,
                icon = android.R.drawable.ic_menu_view,
            )
        }
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
            FileType.DIRECTORY -> FLAG_DIR_SUPPORTS_CREATE or FLAG_SUPPORTS_DELETE or FLAG_SUPPORTS_RENAME or
                    FLAG_SUPPORTS_COPY or FLAG_SUPPORTS_MOVE
            FileType.FILE -> FLAG_SUPPORTS_WRITE or FLAG_SUPPORTS_DELETE or FLAG_SUPPORTS_RENAME or
                    FLAG_SUPPORTS_COPY or FLAG_SUPPORTS_MOVE
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

    /**
     * Build an appropriate error message based on permission requirements.
     * Returns a user-friendly message indicating what access is needed.
     */
    private fun buildPermissionErrorMessage(requirements: PathRequirements): String {
        val allTypes = requirements.combos.flatten().distinct()

        return when {
            SetupModule.Type.ROOT in allTypes && SetupModule.Type.SHIZUKU !in allTypes -> {
                context.getString(R.string.documents_error_requires_root)
            }
            SetupModule.Type.SHIZUKU in allTypes && SetupModule.Type.ROOT !in allTypes -> {
                context.getString(R.string.documents_error_requires_adb)
            }
            SetupModule.Type.STORAGE in allTypes -> {
                context.getString(R.string.documents_error_requires_storage)
            }
            else -> {
                context.getString(R.string.documents_error_requires_permissions)
            }
        }
    }

    companion object {
        private val TAG = logTag("Provider", "Documents", "DocumentQuery")

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
