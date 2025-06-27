package eu.darken.butler.searcher.ui.search.rows

import java.time.Instant

enum class FileType {
    FILE, DIRECTORY, SYMBOLIC_LINK, UNKNOWN
}

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

fun formatFileSize(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var size = bytes.toDouble()
    var unitIndex = 0
    
    while (size >= 1024 && unitIndex < units.size - 1) {
        size /= 1024
        unitIndex++
    }
    
    return if (unitIndex == 0) {
        "${size.toInt()} ${units[unitIndex]}"
    } else {
        "%.1f %s".format(size, units[unitIndex])
    }
}

fun formatRelativeTime(instant: Instant): String {
    val now = java.time.Instant.now()
    val duration = java.time.Duration.between(instant, now)
    
    return when {
        duration.toDays() > 0 -> "${duration.toDays()} days ago"
        duration.toHours() > 0 -> "${duration.toHours()} hours ago"
        duration.toMinutes() > 0 -> "${duration.toMinutes()} minutes ago"
        else -> "Just now"
    }
}