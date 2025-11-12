package eu.darken.butler.provider.documents.core

import android.provider.DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.provider.documents.R
import eu.darken.butler.provider.documents.core.DocumentIdCodec.Companion.DEVICE_DOCUMENT_ID

/**
 * Represents a connection type in Butler's DocumentsProvider hierarchy (Level 2).
 *
 * Connections are virtual documents shown as children of the Butler root.
 * Each connection type represents a different method of accessing files:
 * - Device: Local device storage (local paths, SAF)
 * - SSH: Remote SSH/SFTP servers (future)
 * - FTP: Remote FTP servers (future)
 *
 * Users navigate: Butler (root) → Connection → Storage → Files
 */
sealed interface Connection {
    /**
     * Document ID for this connection.
     * Format: "{type}|{identifier}" (e.g., "device|self", "ssh|server1")
     */
    val documentId: String

    /**
     * Icon resource ID shown for this connection.
     */
    val icon: Int

    /**
     * Localized display title.
     */
    val title: CaString

    /**
     * Optional localized subtitle/description.
     */
    val summary: CaString?

    /**
     * Document flags indicating capabilities.
     * Connections are directories, so use FLAG_DIR_* flags.
     */
    val flags: Int

    /**
     * Local device connection.
     *
     * Phase 1: Only connection type. Shows local device storage (/, internal, SD cards, SAF).
     * Document ID: "device|self"
     */
    data object Device : Connection {
        override val documentId = DEVICE_DOCUMENT_ID
        override val icon = android.R.drawable.ic_menu_manage  // TODO: Create device icon
        override val title = R.string.documents_connection_device_title.toCaString()
        override val summary = R.string.documents_connection_device_summary.toCaString()
        override val flags = FLAG_DIR_SUPPORTS_CREATE  // Directory that can contain new items
    }

    /**
     * SSH/SFTP server connection (Phase 2+).
     *
     * Document ID: "ssh|{serverId}"
     */
    data class SSH(
        val serverId: String,
        val serverName: String,
        val hostName: String,
    ) : Connection {
        override val documentId = "ssh|$serverId"
        override val icon = android.R.drawable.ic_menu_upload  // TODO: Create SSH icon
        override val title = serverName.toCaString()
        override val summary = hostName.toCaString()
        override val flags = FLAG_DIR_SUPPORTS_CREATE
    }

    /**
     * FTP server connection (Phase 2+).
     *
     * Document ID: "ftp|{serverId}"
     */
    data class FTP(
        val serverId: String,
        val serverName: String,
        val hostName: String,
    ) : Connection {
        override val documentId = "ftp|$serverId"
        override val icon = android.R.drawable.ic_menu_upload  // TODO: Create FTP icon
        override val title = serverName.toCaString()
        override val summary = hostName.toCaString()
        override val flags = FLAG_DIR_SUPPORTS_CREATE
    }
}
