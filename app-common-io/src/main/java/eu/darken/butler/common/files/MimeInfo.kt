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
            "application/x-httpd-php",
            "application/sql",
            "application/x-yaml",
            "application/yaml",
            "application/toml",
            "application/x-perl",
            "application/x-ruby",
        )

        /** Text files that carry no extension at all, so they can only be matched on the whole name. */
        private val TEXT_FILE_NAMES = setOf(
            "makefile",
            "dockerfile",
            "license",
            "readme",
            "changelog",
        )

        fun fromFileName(fileName: String): MimeInfo {
            if (fileName.lowercase(Locale.ROOT) in TEXT_FILE_NAMES) return MimeInfo("text/plain")

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
                // Text on the wire, but the Viewer renders it as a vector image - calling it text
                // would route it to the Editor instead.
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
                "md", "markdown" -> "text/markdown"

                // Text
                "json" -> "application/json"
                "xml" -> "application/xml"
                "html", "htm" -> "text/html"
                "css" -> "text/css"
                "csv" -> "text/csv"
                // "mjs"/"cjs" and "ksh" below are not in any of the tables this one replaced, they
                // come from Language.EXTENSION_MAP - the Editor already highlights them, so leaving
                // them out would be a fresh inconsistency.
                "js", "mjs", "cjs" -> "text/javascript"
                // ".ts" is read as TypeScript, not as an MPEG transport stream: an MPEG-TS video
                // therefore gets a text thumbnail of its leading bytes and an "Open in editor" row
                // that lands on the Editor's read-only binary banner. A content sniff is not an
                // option, this function does no I/O and runs per row while a list renders.
                "ts",
                "yml", "yaml", "toml", "ini", "cfg", "conf", "config", "properties", "env",
                "log", "rst", "adoc", "tex", "tsv",
                "jsx", "tsx", "scss", "sass", "less",
                "kt", "kts", "java", "py", "c", "cpp", "cc", "cxx", "h", "hpp",
                "cs", "php", "rb", "go", "rs", "swift", "m", "mm", "sql",
                "sh", "bash", "zsh", "fish", "ksh", "bat", "cmd", "ps1",
                "gradle", "cmake", "make", "mk", "lua", "dart", "pro",
                "gitignore", "gitattributes", "editorconfig", "bashrc", "zshrc", "profile",
                -> "text/plain"

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
