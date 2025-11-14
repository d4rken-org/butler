package eu.darken.butler.provider.documents.core

import android.provider.DocumentsContract.Document.*
import android.provider.DocumentsContract.Root.*
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.provider.documents.R

interface ProviderLocation {
    val icon: Int
    val title: CaString

    /**
     * Represents a DocumentsProvider root entry shown in the file picker drawer.
     *
     * Butler exposes a single root ("Butler") to keep the picker drawer clean and consistent
     * with Butler's navigation hierarchy.
     */
    sealed interface Root : ProviderLocation {
        /**
         * Android API root ID (for COLUMN_ROOT_ID).
         * Must be unique across all roots in the provider.
         */
        val apiRootId: String

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

        data object Butler : Root {
            override val apiRootId = "butler"
            override val icon = R.drawable.ic_folder_home_24
            override val title = R.string.documents_root_butler_title.toCaString()
            override val summary = R.string.documents_root_butler_summary.toCaString()
            override val flags = FLAG_SUPPORTS_CREATE or FLAG_SUPPORTS_IS_CHILD or FLAG_LOCAL_ONLY
            override val rootDocumentId = "butler"
        }
    }

    sealed interface Home : ProviderLocation {

        val documentId: String
        val summary: CaString?
        val flags: Int

        data object Device : Home {
            override val documentId = "device|self"
            override val icon = android.R.drawable.stat_sys_data_bluetooth
            override val title = R.string.documents_connection_device_title.toCaString()
            override val summary = R.string.documents_connection_device_summary.toCaString()
            override val flags = FLAG_DIR_SUPPORTS_CREATE  // Directory that can contain new items
        }
    }

    sealed interface Location : ProviderLocation {

        val path: APath<*>

        data class Local(
            override val path: LocalPath,
            override val icon: Int = android.R.drawable.ic_menu_view,
            override val title: CaString = path.name.toCaString(),
        ) : Location

        data class SAF(
            override val path: eu.darken.butler.common.files.SAFPath,
            override val icon: Int = android.R.drawable.ic_menu_view,
            override val title: CaString = path.name.toCaString(),
        ) : Location
    }


}