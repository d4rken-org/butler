package eu.darken.butler.explorer.core

import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.FileType
import eu.darken.butler.explorer.ui.explorer.FileItem

object FileTypeClassifier {

    fun classifyFileItem(lookup: APathLookup<*>, mimeType: String): FileItem {
        return when {
            // Directory
            lookup.fileType == FileType.DIRECTORY -> FileItem.Directory(
                lookup = lookup,
                mimeType = mimeType
            )

            // Symbolic Link
            lookup.fileType == FileType.SYMBOLIC_LINK -> FileItem.SymbolicLink(
                lookup = lookup,
                mimeType = mimeType,
                targetPath = lookup.target?.path
            )

            // APK Files
            mimeType == "application/vnd.android.package-archive" ||
            lookup.name.lowercase().endsWith(".apk") -> FileItem.ApkFile(
                lookup = lookup,
                mimeType = mimeType
            )

            // Media Files (Video/Audio)
            isMediaType(mimeType) -> FileItem.MediaFile(
                lookup = lookup,
                mimeType = mimeType
            )

            // Image Files
            isImageType(mimeType) -> FileItem.ImageFile(
                lookup = lookup,
                mimeType = mimeType
            )

            // Archive Files
            isArchiveType(mimeType) -> FileItem.ArchiveFile(
                lookup = lookup,
                mimeType = mimeType
            )

            // Document Files
            isDocumentType(mimeType) -> FileItem.DocumentFile(
                lookup = lookup,
                mimeType = mimeType
            )

            // Regular File (fallback)
            else -> FileItem.RegularFile(
                lookup = lookup,
                mimeType = mimeType
            )
        }
    }

    private fun isMediaType(mimeType: String): Boolean {
        return mimeType.startsWith("video/") ||
               mimeType.startsWith("audio/") ||
               isMediaExtension(mimeType)
    }

    private fun isImageType(mimeType: String): Boolean {
        return mimeType.startsWith("image/") ||
               isImageExtension(mimeType)
    }

    private fun isArchiveType(mimeType: String): Boolean {
        return when (mimeType) {
            "application/zip",
            "application/x-zip-compressed",
            "application/x-rar-compressed",
            "application/x-7z-compressed",
            "application/x-tar",
            "application/gzip",
            "application/x-bzip2",
            "application/x-xz" -> true
            else -> isArchiveExtension(mimeType)
        }
    }

    private fun isDocumentType(mimeType: String): Boolean {
        return when (mimeType) {
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "text/plain",
            "text/html",
            "text/rtf",
            "application/rtf" -> true
            else -> isDocumentExtension(mimeType)
        }
    }

    private fun isMediaExtension(mimeType: String): Boolean {
        // Fallback for when MIME type detection fails
        return when {
            mimeType.contains("mp4", ignoreCase = true) -> true
            mimeType.contains("avi", ignoreCase = true) -> true
            mimeType.contains("mkv", ignoreCase = true) -> true
            mimeType.contains("webm", ignoreCase = true) -> true
            mimeType.contains("mp3", ignoreCase = true) -> true
            mimeType.contains("wav", ignoreCase = true) -> true
            mimeType.contains("flac", ignoreCase = true) -> true
            mimeType.contains("ogg", ignoreCase = true) -> true
            mimeType.contains("m4a", ignoreCase = true) -> true
            else -> false
        }
    }

    private fun isImageExtension(mimeType: String): Boolean {
        return when {
            mimeType.contains("jpg", ignoreCase = true) -> true
            mimeType.contains("jpeg", ignoreCase = true) -> true
            mimeType.contains("png", ignoreCase = true) -> true
            mimeType.contains("gif", ignoreCase = true) -> true
            mimeType.contains("webp", ignoreCase = true) -> true
            mimeType.contains("bmp", ignoreCase = true) -> true
            mimeType.contains("svg", ignoreCase = true) -> true
            else -> false
        }
    }

    private fun isArchiveExtension(mimeType: String): Boolean {
        return when {
            mimeType.contains("zip", ignoreCase = true) -> true
            mimeType.contains("rar", ignoreCase = true) -> true
            mimeType.contains("7z", ignoreCase = true) -> true
            mimeType.contains("tar", ignoreCase = true) -> true
            mimeType.contains("gz", ignoreCase = true) -> true
            mimeType.contains("bz2", ignoreCase = true) -> true
            mimeType.contains("xz", ignoreCase = true) -> true
            else -> false
        }
    }

    private fun isDocumentExtension(mimeType: String): Boolean {
        return when {
            mimeType.contains("pdf", ignoreCase = true) -> true
            mimeType.contains("doc", ignoreCase = true) -> true
            mimeType.contains("docx", ignoreCase = true) -> true
            mimeType.contains("xls", ignoreCase = true) -> true
            mimeType.contains("xlsx", ignoreCase = true) -> true
            mimeType.contains("ppt", ignoreCase = true) -> true
            mimeType.contains("pptx", ignoreCase = true) -> true
            mimeType.contains("txt", ignoreCase = true) -> true
            mimeType.contains("rtf", ignoreCase = true) -> true
            else -> false
        }
    }
}