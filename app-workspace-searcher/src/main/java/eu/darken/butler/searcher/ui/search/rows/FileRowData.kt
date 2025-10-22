package eu.darken.butler.searcher.ui.search.rows

import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import kotlin.time.Instant
import java.io.File

data class FileRowData(
    val lookup: APathLookup<*>,
    val metadata: Map<String, String> = emptyMap(),
    val matchContext: MatchContext? = null
) {
    val name: String get() = lookup.name
    val path: String get() = lookup.path
    val fileType: FileType get() = lookup.fileType
    val size: Long? get() = lookup.size
    val modifiedAt: Instant? get() = lookup.modifiedAt

    data class MatchContext(
        val lineNumber: Int?,
        val matchedLine: String?
    )

    companion object {
        fun forPreview(
            name: String,
            path: String,
            fileType: FileType,
            size: Long? = null,
            modifiedAt: Instant? = null,
            metadata: Map<String, String> = emptyMap(),
            matchContext: MatchContext? = null
        ): FileRowData = FileRowData(
            lookup = PreviewAPathLookup(
                name = name,
                path = path,
                fileType = fileType,
                size = size,
                modifiedAt = modifiedAt
            ),
            metadata = metadata,
            matchContext = matchContext
        )
    }
}

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

/**
 * Stub implementation of APathLookup for preview/test purposes only.
 * Provides minimal data without actual file system interaction.
 */
private data class PreviewAPathLookup(
    private val localPath: LocalPath,
    override val fileType: FileType,
    override val size: Long? = null,
    override val modifiedAt: Instant? = null
) : APathLookup<LocalPath> {
    constructor(
        name: String,
        path: String,
        fileType: FileType,
        size: Long? = null,
        modifiedAt: Instant? = null
    ) : this(
        localPath = LocalPath(File(path)),
        fileType = fileType,
        size = size,
        modifiedAt = modifiedAt
    )

    override val lookedUp: LocalPath get() = localPath
    override val target: LocalPath? = null
    override val ownership: Ownership? = null
    override val permissions: Permissions? = null
    override val createdAt: Instant? = null
    override val error: Throwable? = null
}