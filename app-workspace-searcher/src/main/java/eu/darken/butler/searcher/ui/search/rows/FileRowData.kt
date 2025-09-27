package eu.darken.butler.searcher.ui.search.rows

import eu.darken.butler.common.files.metadata.FileType
import kotlin.time.Instant

data class FileRowData(
    val name: String,
    val path: String,
    val fileType: FileType,
    val size: Long? = null,
    val modifiedAt: Instant? = null,
    val metadata: Map<String, String> = emptyMap()
)

sealed class FileRowType {
    object Default : FileRowType()
    object Media : FileRowType()
    object Document : FileRowType()
    object Archive : FileRowType()
    object App : FileRowType()
    object Code : FileRowType()
}


fun determineFileRowType(fileName: String): FileRowType {
    val extension = fileName.substringAfterLast('.', "").lowercase()

    return when (extension) {
        "jpg", "jpeg", "png", "gif", "bmp", "webp", "svg" -> FileRowType.Media
        "mp4", "avi", "mkv", "mov", "wmv", "flv", "webm" -> FileRowType.Media
        "mp3", "wav", "flac", "aac", "ogg", "m4a" -> FileRowType.Media
        "pdf", "doc", "docx", "txt", "rtf" -> FileRowType.Document
        "xls", "xlsx", "ppt", "pptx" -> FileRowType.Document
        "zip", "rar", "7z", "tar", "gz", "bz2" -> FileRowType.Archive
        "apk", "aab" -> FileRowType.App
        "kt", "java", "py", "js", "ts", "html", "css", "xml", "json" -> FileRowType.Code
        "cpp", "c", "h", "swift", "go", "rs", "php", "rb" -> FileRowType.Code

        else -> FileRowType.Default
    }
}

// Note: formatFileSize moved to ByteFormatter in app-common module
// Note: formatRelativeTime moved to TimeFormatting in app-common module