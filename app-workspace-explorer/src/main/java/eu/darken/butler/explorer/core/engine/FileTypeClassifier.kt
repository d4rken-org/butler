package eu.darken.butler.explorer.core.engine

import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.FileType
import java.util.Locale

class FileTypeClassifier {

    fun classify(lookup: APathLookup<*>): ExplorerItem.PathItem {
        return when (lookup.fileType) {
            FileType.DIRECTORY -> ExplorerItem.RegularDirectory(
                lookup = lookup,
            )
            FileType.SYMBOLIC_LINK -> ExplorerItem.SymbolicLink(
                lookup = lookup,
                mimeType = getMimeType(lookup.name),
                targetPath = lookup.target?.path,
                isBroken = lookup.target == null
            )
            FileType.FILE -> classifyFile(lookup)
            else -> ExplorerItem.RegularFile(
                lookup = lookup,
                mimeType = getMimeType(lookup.name)
            )
        }
    }

    private fun classifyFile(lookup: APathLookup<*>): ExplorerItem.PathItem {
        val mimeType = getMimeType(lookup.name)

        return when {
            else -> ExplorerItem.RegularFile(
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