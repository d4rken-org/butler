package eu.darken.butler.provider.documents.core

import android.provider.DocumentsContract.Root.FLAG_LOCAL_ONLY
import android.provider.DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.provider.documents.R
import eu.darken.butler.provider.documents.core.DocumentIdCodec.Companion.ROOT_DOCUMENT_ID

/**
 * Represents a DocumentsProvider root entry shown in the file picker drawer.
 *
 * Butler exposes a single root ("Butler") to keep the picker drawer clean and consistent
 * with Butler's navigation hierarchy:
 *
 * Level 1: Butler (root) - Single picker drawer entry
 * Level 2: Connections (device|self, ssh|{id}, ftp|{id}) - Connection types
 * Level 3: Storage (local paths, SAF paths) - Actual storage locations
 * Level 4+: Files - Filesystem items
 */
sealed interface DocumentRoot {
    /**
     * Android API root ID (for COLUMN_ROOT_ID).
     * Must be unique across all roots in the provider.
     */
    val apiRootId: String

    /**
     * Icon resource ID shown in the picker drawer.
     * Uses Android drawable resources for system file picker compatibility.
     */
    val icon: Int

    /**
     * Localized title shown in the picker drawer.
     */
    val title: CaString

    /**
     * Optional localized summary/subtitle shown in the picker drawer.
     */
    val summary: CaString?

    /**
     * DocumentsContract flags indicating capabilities.
     * Common flags: FLAG_SUPPORTS_IS_CHILD, FLAG_LOCAL_ONLY, FLAG_SUPPORTS_CREATE
     */
    val flags: Int

    /**
     * Document ID of the root document (for COLUMN_DOCUMENT_ID).
     * This is the starting point when users open this root.
     */
    val rootDocumentId: String

    /**
     * Single Butler root.
     *
     * Phase 1: Only root. Shows "Butler" in picker drawer.
     * Users navigate: Butler → Device → Storage → Files
     */
    data object Butler : DocumentRoot {
        override val apiRootId = "butler"
        override val icon = android.R.drawable.ic_menu_manage  // TODO: Create custom Butler icon
        override val title = R.string.documents_root_butler_title.toCaString()
        override val summary = R.string.documents_root_butler_summary.toCaString()
        override val flags = FLAG_SUPPORTS_IS_CHILD or FLAG_LOCAL_ONLY
        override val rootDocumentId = ROOT_DOCUMENT_ID
    }
}

