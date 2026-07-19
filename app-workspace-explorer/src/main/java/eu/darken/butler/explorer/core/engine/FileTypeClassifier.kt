package eu.darken.butler.explorer.core.engine

import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.metadata.FileMetadata
import eu.darken.butler.common.files.metadata.FileType
import java.util.Locale

class FileTypeClassifier {

    fun classify(lookup: APathLookup<*>, metadata: FileMetadata? = null): ExplorerItem.Lookup = when (lookup.fileType) {
        FileType.DIRECTORY -> ExplorerItem.RegularDirectory(
            lookup = lookup,
            metadata = metadata,
        )
        FileType.SYMBOLIC_LINK -> ExplorerItem.SymbolicLink(
            lookup = lookup,
            mimeType = getMimeType(lookup.name),
            targetPath = lookup.target?.path,
            isBroken = lookup.target == null,
            metadata = metadata,
        )
        FileType.FILE -> classifyFile(lookup, metadata)
        else -> ExplorerItem.RegularFile(
            lookup = lookup,
            mimeType = getMimeType(lookup.name),
            metadata = metadata,
        )
    }

    private fun classifyFile(lookup: APathLookup<*>, metadata: FileMetadata?): ExplorerItem.Lookup {
        val mimeType = getMimeType(lookup.name)

        return when {
            else -> ExplorerItem.RegularFile(
                lookup = lookup,
                mimeType = mimeType,
                metadata = metadata,
            )
        }
    }

    internal fun getMimeType(fileName: String): MimeInfo {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)

        val rawType = when (extension) {
            // Images
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            "avif" -> "image/avif"

            // Videos
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "3gp" -> "video/3gpp"
            "m4v" -> "video/x-m4v"

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

        return MimeInfo(rawType)
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