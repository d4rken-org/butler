package eu.darken.butler.provider.documents.core.query

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document.*
import android.webkit.MimeTypeMap
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.formatFileSize
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
import eu.darken.butler.provider.documents.R
import eu.darken.butler.provider.documents.core.ButlerDocumentsProvider
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

        // Always use full projection: RowBuilder.add(String, Object) throws if column is missing.
        // Ignoring the requested projection is intentional and DocumentsProvider-contract-compliant:
        // clients read columns by name and tolerate extra columns, so returning a superset is allowed.
        val cursor = MatrixCursor(DEFAULT_DOCUMENT_PROJECTION)

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
                    val lookup = gatewaySwitch.lookup(path, LookupOptions(fetchSize = true, fetchModifiedAt = true))
                    cursor.addFilesystemDocument(documentId, lookup)
                }
            }
        } catch (e: Exception) {
            log(TAG, WARN) { "queryDocument($documentId) failed: ${e.asLog()}" }
            return ErrorMatrixCursor(
                DEFAULT_DOCUMENT_PROJECTION,
                context.getString(R.string.provider_documents_error_generic),
            )
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

        // Always use full projection: RowBuilder.add(String, Object) throws if column is missing.
        // Ignoring the requested projection is intentional and DocumentsProvider-contract-compliant:
        // clients read columns by name and tolerate extra columns, so returning a superset is allowed.
        val cursor = MatrixCursor(DEFAULT_DOCUMENT_PROJECTION)

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
                        return ErrorMatrixCursor(DEFAULT_DOCUMENT_PROJECTION, errorMessage)
                    } else {
                        val children = gatewaySwitch.lookupFiles(path, LookupOptions(fetchSize = true, fetchModifiedAt = true))

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
                DEFAULT_DOCUMENT_PROJECTION,
                context.getString(R.string.provider_documents_error_generic)
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
            val rootSummary = getStorageSummary("/")
            cursor.addVirtualDocument(
                documentId = codec.encode(rootPath),
                displayName = context.getString(R.string.provider_documents_storage_root_label),
                mimeType = MIME_TYPE_DIR,
                flags = FLAG_DIR_SUPPORTS_CREATE,
                icon = R.drawable.ic_folder_lock_24,
                summary = rootSummary,
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
                        0 -> context.getString(R.string.provider_documents_storage_internal_label)
                        else -> context.getString(R.string.provider_documents_storage_sd_card_label)
                    }

                val storagePath = volume.directory?.absolutePath ?: volume.path
                val summary = storagePath?.let { getStorageSummary(it) }

                log(TAG, INFO) { "Storage volume accessible: $displayName ($path), summary=$summary" }
                cursor.addVirtualDocument(
                    documentId = codec.encode(path),
                    displayName = displayName,
                    mimeType = MIME_TYPE_DIR,
                    flags = FLAG_DIR_SUPPORTS_CREATE,
                    icon = R.drawable.ic_folder,
                    summary = summary,
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
                icon = R.drawable.ic_folder_open_24,
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
        summary: String? = null,
    ) {
        newRow().apply {
            add(COLUMN_DOCUMENT_ID, documentId)
            add(COLUMN_DISPLAY_NAME, displayName)
            add(COLUMN_SUMMARY, summary)
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
    private suspend fun MatrixCursor.addFilesystemDocument(
        documentId: String,
        lookup: APathLookup<*>
    ) {
        val mimeType: String
        val flags: Int

        when (lookup.fileType) {
            FileType.DIRECTORY -> {
                mimeType = MIME_TYPE_DIR
                flags = DIR_FLAGS
            }
            FileType.FILE -> {
                mimeType = getMimeType(lookup.name)
                flags = FILE_FLAGS
            }
            FileType.SYMBOLIC_LINK -> {
                val resolution = resolveSymlink(lookup)
                mimeType = resolution.mimeType
                flags = when (resolution.resolvedFileType) {
                    FileType.DIRECTORY -> DIR_FLAGS
                    FileType.FILE, FileType.SYMBOLIC_LINK -> FILE_FLAGS
                    FileType.UNKNOWN, null -> MANAGE_ONLY_FLAGS
                }
            }
            FileType.UNKNOWN -> {
                mimeType = "application/octet-stream"
                flags = MANAGE_ONLY_FLAGS
            }
        }

        newRow().apply {
            add(COLUMN_DOCUMENT_ID, documentId)
            add(COLUMN_DISPLAY_NAME, lookup.name)
            add(COLUMN_SUMMARY, null)
            add(COLUMN_MIME_TYPE, mimeType)
            add(COLUMN_FLAGS, flags)
            add(COLUMN_SIZE, lookup.size)
            add(COLUMN_LAST_MODIFIED, lookup.modifiedAt?.toEpochMilliseconds())
            add(COLUMN_ICON, null) // Use system default icon for mime type
        }
    }

    private fun getMimeType(filename: String): String {
        val extension = filename.substringAfterLast('.', "").lowercase()
        return if (extension.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?: "application/octet-stream"
        } else {
            "application/octet-stream"
        }
    }

    private data class SymlinkResolution(
        val mimeType: String,
        val resolvedFileType: FileType?,
    )

    /**
     * Resolve symlink target to determine both MIME type and effective file type.
     * Returns null resolvedFileType for broken/unresolvable symlinks.
     */
    private suspend fun resolveSymlink(lookup: APathLookup<*>): SymlinkResolution {
        val target = lookup.target
        if (target == null) {
            log(TAG, VERBOSE) { "Symlink ${lookup.path} has no target, using symlink name for MIME type" }
            return SymlinkResolution(
                mimeType = getMimeType(lookup.name),
                resolvedFileType = null,
            )
        }

        return try {
            val targetLookup = gatewaySwitch.lookup(target, LookupOptions())
            val mimeType = when (targetLookup.fileType) {
                FileType.DIRECTORY -> MIME_TYPE_DIR
                FileType.FILE -> getMimeType(targetLookup.name)
                FileType.SYMBOLIC_LINK -> {
                    log(TAG, VERBOSE) { "Symlink ${lookup.path} target is also a symlink, using target name" }
                    getMimeType(targetLookup.name)
                }
                FileType.UNKNOWN -> "application/octet-stream"
            }
            SymlinkResolution(
                mimeType = mimeType,
                resolvedFileType = targetLookup.fileType,
            )
        } catch (e: Exception) {
            log(TAG, WARN) { "Failed to resolve symlink ${lookup.path} target: ${e.asLog()}" }
            SymlinkResolution(
                mimeType = getMimeType(lookup.name),
                resolvedFileType = null,
            )
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
                context.getString(R.string.provider_documents_error_requires_root)
            }
            SetupModule.Type.SHIZUKU in allTypes && SetupModule.Type.ROOT !in allTypes -> {
                context.getString(R.string.provider_documents_error_requires_adb)
            }
            SetupModule.Type.STORAGE in allTypes -> {
                context.getString(R.string.provider_documents_error_requires_storage)
            }
            else -> {
                context.getString(R.string.provider_documents_error_requires_permissions)
            }
        }
    }

    private fun getStorageSummary(path: String): String? = try {
        val statFs = android.os.StatFs(path)
        val freeBytes = statFs.availableBlocksLong * statFs.blockSizeLong
        log(TAG, INFO) { "getStorageSummary($path): freeBytes=$freeBytes" }
        if (freeBytes > 0) {
            formatFileSize(context, freeBytes) + " " + context.getString(R.string.provider_documents_storage_free_suffix)
        } else {
            null
        }
    } catch (e: Exception) {
        log(TAG, WARN) { "getStorageSummary($path) failed: ${e.asLog()}" }
        null
    }

    companion object {
        private val TAG = logTag("Provider", "Documents", "DocumentQuery")

        private const val DIR_FLAGS = FLAG_DIR_SUPPORTS_CREATE or FLAG_SUPPORTS_DELETE or
            FLAG_SUPPORTS_RENAME or FLAG_SUPPORTS_COPY or FLAG_SUPPORTS_MOVE

        private const val FILE_FLAGS = FLAG_SUPPORTS_WRITE or FLAG_SUPPORTS_DELETE or
            FLAG_SUPPORTS_RENAME or FLAG_SUPPORTS_COPY or FLAG_SUPPORTS_MOVE

        private const val MANAGE_ONLY_FLAGS = FLAG_SUPPORTS_DELETE or FLAG_SUPPORTS_RENAME or
            FLAG_SUPPORTS_COPY or FLAG_SUPPORTS_MOVE

        internal val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            COLUMN_DOCUMENT_ID,
            COLUMN_DISPLAY_NAME,
            COLUMN_SUMMARY,
            COLUMN_MIME_TYPE,
            COLUMN_FLAGS,
            COLUMN_SIZE,
            COLUMN_LAST_MODIFIED,
            COLUMN_ICON,
        )
    }
}
