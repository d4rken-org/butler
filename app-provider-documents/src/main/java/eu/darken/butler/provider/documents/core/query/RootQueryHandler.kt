package eu.darken.butler.provider.documents.core.query

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.storage.StorageManager2
import eu.darken.butler.provider.documents.core.DocumentsProviderSettings
import eu.darken.butler.provider.documents.core.ProviderLocation
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles queryRoots() calls from Android's DocumentsProvider API.
 *
 * Returns the single Butler root that appears in the file picker drawer.
 */
@Singleton
class RootQueryHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: DocumentsProviderSettings,
    private val storageManager2: StorageManager2,
) {

    /**
     * Query available roots shown in the file picker drawer.
     *
     * @param projection Columns to return (null = all columns)
     * @return Cursor with root metadata
     */
    suspend fun queryRoots(projection: Array<String>?): Cursor {
        log(TAG, INFO) { "queryRoots() called with projection: ${projection?.contentToString()}" }

        if (!settings.isEnabled.value()) {
            log(TAG, WARN) { "Provider is disabled - returning empty cursor" }
            return MatrixCursor(DEFAULT_ROOT_PROJECTION)
        }

        val roots = listOf(ProviderLocation.Root.Butler)

        // Always use full projection: RowBuilder.add(String, Object) throws if column is missing.
        // SAF clients handle extra columns gracefully. TODO: Consider per-column safe-add instead.
        val resolvedProjection = DEFAULT_ROOT_PROJECTION
        val cursor = MatrixCursor(resolvedProjection)

        roots.forEach { root ->
            cursor.newRow().apply {
                // Required columns
                add(DocumentsContract.Root.COLUMN_ROOT_ID, root.apiRootId)
                add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, root.rootDocumentId)
                add(DocumentsContract.Root.COLUMN_ICON, root.icon)
                add(DocumentsContract.Root.COLUMN_TITLE, root.title.get(context))
                add(DocumentsContract.Root.COLUMN_FLAGS, root.flags)

                // Optional columns
                add(DocumentsContract.Root.COLUMN_SUMMARY, root.summary.get(context))
                val availableBytes = storageManager2.storageVolumes
                    .firstOrNull { it.isPrimary && it.isMounted }
                    ?.directory
                    ?.usableSpace
                add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, availableBytes)
            }
        }

        log(TAG, INFO) { "Returning ${roots.size} root(s)" }
        return cursor
    }

    companion object {
        private val TAG = logTag("Provider", "Documents", "RootQuery")

        /**
         * Default projection for queryRoots() when none specified.
         * Includes all standard root columns.
         */
        internal val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
        )
    }
}
