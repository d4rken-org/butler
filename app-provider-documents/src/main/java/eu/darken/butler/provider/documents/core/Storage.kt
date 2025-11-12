package eu.darken.butler.provider.documents.core

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.provider.documents.R

/**
 * Represents a storage location in Butler's DocumentsProvider hierarchy (Level 3).
 *
 * Storage locations are shown as children of Device connection.
 * Each storage represents an actual filesystem location that can be browsed:
 * - Root Filesystem: "/" (all files on device)
 * - Internal Storage: "/storage/emulated/0" (primary storage - Phase 2+)
 * - SD Cards: "/storage/{uuid}" (external storage - Phase 2+)
 * - SAF Locations: User-granted SAF trees (Phase 2+)
 *
 * Users navigate: Butler → Device → Storage → Files
 */
sealed interface Storage {
    /**
     * The real filesystem path this storage points to.
     * This is encoded into document IDs for files within this storage.
     */
    val path: APath<*>

    /**
     * Icon resource ID for this storage type.
     */
    val icon: Int

    /**
     * Localized display name.
     */
    val displayName: CaString

    /**
     * Root filesystem storage (Phase 1).
     *
     * Points to "/" - allows browsing entire filesystem.
     * Useful for advanced users who want access to /system, /data, etc.
     */
    data object RootFilesystem : Storage {
        override val path: LocalPath = LocalPath.build("/")
        override val icon = android.R.drawable.ic_menu_view  // TODO: Create folder icon
        override val displayName = R.string.documents_storage_root_label.toCaString()
    }

    /**
     * Internal storage (Phase 2+).
     *
     * Points to "/storage/emulated/0" or first storage volume.
     * Primary storage location for most users.
     */
    data class InternalStorage(
        val volumePath: LocalPath,
        val label: String?,
    ) : Storage {
        override val path = volumePath
        override val icon = android.R.drawable.ic_menu_view  // TODO: Create storage icon
        override val displayName = (label ?: "Internal Storage").toCaString()
    }

    /**
     * SD card storage (Phase 2+).
     *
     * Points to "/storage/{uuid}" for external volumes.
     */
    data class SDCard(
        val volumeId: String,
        val volumePath: LocalPath,
        val label: String?,
    ) : Storage {
        override val path = volumePath
        override val icon = android.R.drawable.ic_menu_view  // TODO: Create SD card icon
        override val displayName = (label ?: "SD Card").toCaString()
    }

    /**
     * SAF tree location (Phase 2+).
     *
     * User-granted SAF access to specific directories.
     */
    data class SAFLocation(
        val locationId: String,
        val treeRoot: eu.darken.butler.common.files.SAFPath,
        val label: String,
    ) : Storage {
        override val path = treeRoot
        override val icon = android.R.drawable.ic_menu_view  // TODO: Create SAF icon
        override val displayName = label.toCaString()
    }
}
