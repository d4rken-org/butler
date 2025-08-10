package eu.darken.butler.explorer.core.engine

import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.FileType
import eu.darken.butler.common.files.Ownership
import eu.darken.butler.common.files.Permissions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface ExplorerPathItem {
    val lookup: APathLookup<*>
    val mimeType: String
    val isSelected: Boolean
    val ownership: Ownership?
    val permissions: Permissions?

    val displayName: String get() = lookup.name
    val displaySize: String get() = formatFileSize(lookup.size)
    val displayDate: String get() = formatDate(lookup.modifiedAt.toEpochMilli())
    val isDirectory: Boolean get() = lookup.fileType == FileType.DIRECTORY

    fun withExtendedData(ownership: Ownership?, permissions: Permissions?): ExplorerPathItem

    data class Directory(
        override val lookup: APathLookup<*>,
        override val mimeType: String = "inode/directory",
        override val isSelected: Boolean = false,
        override val ownership: Ownership? = null,
        override val permissions: Permissions? = null,
        val childCount: Int? = null
    ) : ExplorerPathItem {
        override fun withExtendedData(ownership: Ownership?, permissions: Permissions?) = copy(
            ownership = ownership,
            permissions = permissions
        )
    }

    data class RegularFile(
        override val lookup: APathLookup<*>,
        override val mimeType: String,
        override val isSelected: Boolean = false,
        override val ownership: Ownership? = null,
        override val permissions: Permissions? = null
    ) : ExplorerPathItem {
        override fun withExtendedData(ownership: Ownership?, permissions: Permissions?) = copy(
            ownership = ownership,
            permissions = permissions
        )
    }

    data class SymbolicLink(
        override val lookup: APathLookup<*>,
        override val mimeType: String,
        override val isSelected: Boolean = false,
        override val ownership: Ownership? = null,
        override val permissions: Permissions? = null,
        val targetPath: String? = null,
        val isBroken: Boolean = false
    ) : ExplorerPathItem {
        override fun withExtendedData(ownership: Ownership?, permissions: Permissions?) = copy(
            ownership = ownership,
            permissions = permissions
        )
    }

    data class MediaFile(
        override val lookup: APathLookup<*>,
        override val mimeType: String,
        override val isSelected: Boolean = false,
        override val ownership: Ownership? = null,
        override val permissions: Permissions? = null,
        val duration: String? = null,
        val resolution: String? = null
    ) : ExplorerPathItem {
        val isVideo: Boolean get() = mimeType.startsWith("video/")
        val isAudio: Boolean get() = mimeType.startsWith("audio/")

        override fun withExtendedData(ownership: Ownership?, permissions: Permissions?) = copy(
            ownership = ownership,
            permissions = permissions
        )
    }

    data class ApkFile(
        override val lookup: APathLookup<*>,
        override val mimeType: String = "application/vnd.android.package-archive",
        override val isSelected: Boolean = false,
        override val ownership: Ownership? = null,
        override val permissions: Permissions? = null,
        val packageName: String? = null,
        val versionName: String? = null,
        val appName: String? = null
    ) : ExplorerPathItem {
        override val displayName: String get() = appName ?: lookup.name

        override fun withExtendedData(ownership: Ownership?, permissions: Permissions?) = copy(
            ownership = ownership,
            permissions = permissions
        )
    }

    data class ArchiveFile(
        override val lookup: APathLookup<*>,
        override val mimeType: String,
        override val isSelected: Boolean = false,
        override val ownership: Ownership? = null,
        override val permissions: Permissions? = null,
        val compressionRatio: Float? = null,
        val entryCount: Int? = null
    ) : ExplorerPathItem {
        override fun withExtendedData(ownership: Ownership?, permissions: Permissions?) = copy(
            ownership = ownership,
            permissions = permissions
        )
    }

    data class ImageFile(
        override val lookup: APathLookup<*>,
        override val mimeType: String,
        override val isSelected: Boolean = false,
        override val ownership: Ownership? = null,
        override val permissions: Permissions? = null,
        val dimensions: String? = null
    ) : ExplorerPathItem {
        override fun withExtendedData(ownership: Ownership?, permissions: Permissions?) = copy(
            ownership = ownership,
            permissions = permissions
        )
    }

    data class DocumentFile(
        override val lookup: APathLookup<*>,
        override val mimeType: String,
        override val isSelected: Boolean = false,
        override val ownership: Ownership? = null,
        override val permissions: Permissions? = null,
        val pageCount: Int? = null,
        val author: String? = null
    ) : ExplorerPathItem {
        override fun withExtendedData(ownership: Ownership?, permissions: Permissions?) = copy(
            ownership = ownership,
            permissions = permissions
        )
    }

    data class Shortcut(
        override val lookup: APathLookup<*>,
        override val mimeType: String = "inode/shortcut",
        override val isSelected: Boolean = false,
        override val ownership: Ownership? = null,
        override val permissions: Permissions? = null,
        val icon: ImageVector,
        val label: CaString,
        val target: ExplorerLocation,
    ) : ExplorerPathItem {
        override val displayName: String get() = label.toString() // This will be resolved in UI with context

        override fun withExtendedData(ownership: Ownership?, permissions: Permissions?) = copy(
            ownership = ownership,
            permissions = permissions
        )
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    val gb = mb / 1024.0
    return "%.1f GB".format(gb)
}

// TODO: This would use a proper date formatter in a real implementation
private fun formatDate(timestamp: Long): String {
    return SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(timestamp))
}