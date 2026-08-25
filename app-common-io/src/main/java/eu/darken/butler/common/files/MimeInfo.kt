package eu.darken.butler.common.files

import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
data class MimeInfo(
    val rawType: String,
) {
    val isImage: Boolean
        get() = rawType.startsWith("image/")

    val isVideo: Boolean
        get() = rawType.startsWith("video/")

    val isAudio: Boolean
        get() = rawType.startsWith("audio/")

    val isText: Boolean
        get() = rawType.startsWith("text/") || rawType in TEXT_MIME_TYPES

    val isApk: Boolean
        get() = rawType == MIME_APK

    val isPdf: Boolean
        get() = rawType == "application/pdf"

    companion object {
        const val MIME_APK = "application/vnd.android.package-archive"

        private val TEXT_MIME_TYPES = setOf(
            "application/json",
            "application/xml",
            "application/javascript",
            "application/x-sh",
            "application/x-shellscript",
        )

        fun fromFileName(fileName: String): MimeInfo {
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
                "svg" -> "image/svg+xml"

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
                "apk" -> MIME_APK
                // App-install bundles are zip containers. There is deliberately no bundle-specific
                // MIME type: "Open with" and every other intent path would resolve to nothing.
                "apks", "xapk", "apkm" -> "application/zip"

                else -> "application/octet-stream"
            }

            return MimeInfo(rawType)
        }
    }
}
