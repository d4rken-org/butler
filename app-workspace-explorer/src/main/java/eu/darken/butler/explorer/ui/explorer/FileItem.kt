package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.FileType

sealed interface FileItem {
    val lookup: APathLookup<*>
    val mimeType: String
    val isSelected: Boolean

    val displayName: String get() = lookup.name
    val displaySize: String get() = _root_ide_package_.eu.darken.butler.explorer.ui.explorer.formatFileSize(lookup.size)
    val displayDate: String get() = _root_ide_package_.eu.darken.butler.explorer.ui.explorer.formatDate(lookup.modifiedAt.toEpochMilli())
    val isDirectory: Boolean get() = lookup.fileType == FileType.DIRECTORY

    data class Directory(
        override val lookup: APathLookup<*>,
        override val mimeType: String = "inode/directory",
        override val isSelected: Boolean = false,
        val childCount: Int? = null
    ) : FileItem

    data class RegularFile(
        override val lookup: APathLookup<*>,
        override val mimeType: String,
        override val isSelected: Boolean = false
    ) : FileItem

    data class SymbolicLink(
        override val lookup: APathLookup<*>,
        override val mimeType: String,
        override val isSelected: Boolean = false,
        val targetPath: String? = null,
        val isBroken: Boolean = false
    ) : FileItem

    data class MediaFile(
        override val lookup: APathLookup<*>,
        override val mimeType: String,
        override val isSelected: Boolean = false,
        val duration: String? = null,
        val resolution: String? = null
    ) : FileItem {
        val isVideo: Boolean get() = mimeType.startsWith("video/")
        val isAudio: Boolean get() = mimeType.startsWith("audio/")
    }

    data class ApkFile(
        override val lookup: APathLookup<*>,
        override val mimeType: String = "application/vnd.android.package-archive",
        override val isSelected: Boolean = false,
        val packageName: String? = null,
        val versionName: String? = null,
        val appName: String? = null
    ) : FileItem {
        override val displayName: String get() = appName ?: lookup.name
    }

    data class ArchiveFile(
        override val lookup: APathLookup<*>,
        override val mimeType: String,
        override val isSelected: Boolean = false,
        val compressionRatio: Float? = null,
        val entryCount: Int? = null
    ) : FileItem

    data class ImageFile(
        override val lookup: APathLookup<*>,
        override val mimeType: String,
        override val isSelected: Boolean = false,
        val dimensions: String? = null
    ) : FileItem

    data class DocumentFile(
        override val lookup: APathLookup<*>,
        override val mimeType: String,
        override val isSelected: Boolean = false,
        val pageCount: Int? = null,
        val author: String? = null
    ) : FileItem

//    fun copyWithSelection(selected: Boolean): FileItem {
//        return when (this) {
//            is Directory -> FileItem.Directory(isSelected = selected)
//            is RegularFile -> FileItem.RegularFile(isSelected = selected)
//            is SymbolicLink -> FileItem.SymbolicLink(isSelected = selected)
//            is MediaFile -> FileItem.MediaFile(isSelected = selected)
//            is ApkFile -> FileItem.ApkFile(isSelected = selected)
//            is ArchiveFile -> FileItem.ArchiveFile(isSelected = selected)
//            is ImageFile -> FileItem.ImageFile(isSelected = selected)
//            is DocumentFile -> FileItem.DocumentFile(isSelected = selected)
//        }
//    }
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

private fun formatDate(timestamp: Long): String {
    // This would use a proper date formatter in a real implementation
    return java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
        .format(java.util.Date(timestamp))
}