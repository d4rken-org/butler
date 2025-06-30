package eu.darken.butler.explorer.core.engine

import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.FileType
import java.util.Locale

class FileTypeClassifier {

    fun classify(lookup: APathLookup<*>): ExplorerPathItem {
        return when (lookup.fileType) {
            FileType.DIRECTORY -> ExplorerPathItem.Directory(
                lookup = lookup,
                mimeType = "inode/directory"
            )
            FileType.SYMBOLIC_LINK -> ExplorerPathItem.SymbolicLink(
                lookup = lookup,
                mimeType = getMimeType(lookup.name),
                targetPath = lookup.target?.path,
                isBroken = lookup.target == null
            )
            FileType.FILE -> classifyFile(lookup)
            else -> ExplorerPathItem.RegularFile(
                lookup = lookup,
                mimeType = getMimeType(lookup.name)
            )
        }
    }

    private fun classifyFile(lookup: APathLookup<*>): ExplorerPathItem {
        val mimeType = getMimeType(lookup.name)

        return when {
            mimeType.startsWith("image/") -> ExplorerPathItem.ImageFile(
                lookup = lookup,
                mimeType = mimeType
            )
            mimeType.startsWith("video/") || mimeType.startsWith("audio/") -> ExplorerPathItem.MediaFile(
                lookup = lookup,
                mimeType = mimeType
            )
            mimeType == "application/vnd.android.package-archive" -> ExplorerPathItem.ApkFile(
                lookup = lookup,
                mimeType = mimeType
            )
            isArchiveType(mimeType) -> ExplorerPathItem.ArchiveFile(
                lookup = lookup,
                mimeType = mimeType
            )
            isDocumentType(mimeType) -> ExplorerPathItem.DocumentFile(
                lookup = lookup,
                mimeType = mimeType
            )
            else -> ExplorerPathItem.RegularFile(
                lookup = lookup,
                mimeType = mimeType
            )
        }
    }

    private fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)

        return when (extension) {
            // Images
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"

            // Videos
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"

            // Audio
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "ogg" -> "audio/ogg"
            "m4a" -> "audio/mp4"

            // Archives
            "zip" -> "application/zip"
            "tar" -> "application/x-tar"
            "gz" -> "application/gzip"
            "7z" -> "application/x-7z-compressed"
            "rar" -> "application/vnd.rar"

            // Documents
            "pdf" -> "application/pdf"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "txt" -> "text/plain"
            "md" -> "text/markdown"

            // Android
            "apk" -> "application/vnd.android.package-archive"

            else -> "application/octet-stream"
        }
    }

    private fun isArchiveType(mimeType: String): Boolean {
        return mimeType in setOf(
            "application/zip",
            "application/x-tar",
            "application/gzip",
            "application/x-7z-compressed",
            "application/vnd.rar"
        )
    }

    private fun isDocumentType(mimeType: String): Boolean {
        return mimeType in setOf(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/plain",
            "text/markdown"
        )
    }
}