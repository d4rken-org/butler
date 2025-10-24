package eu.darken.butler.searcher.ui.search.rows

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.twotone.Audiotrack
import androidx.compose.material.icons.twotone.Code
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material.icons.twotone.FolderOpen
import androidx.compose.material.icons.twotone.Image
import androidx.compose.material.icons.twotone.Movie
import androidx.compose.material.icons.twotone.PictureAsPdf
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.searcher.core.SearchItem

@Composable
fun getFileIconAndTint(result: SearchItem): Pair<ImageVector, Color> {
    // Type-based dispatch for directories
    when (result) {
        is SearchItem.Directory ->
            return Icons.TwoTone.Folder to MaterialTheme.colorScheme.primary
        is SearchItem.File -> {
            // Check for symbolic links
            if (result.fileType == FileType.SYMBOLIC_LINK) {
                return Icons.TwoTone.FolderOpen to MaterialTheme.colorScheme.primary
            }

            // Extension-based dispatch for files (will become type-based with ApkFile, ImageFile, etc.)
            val extension = result.name.substringAfterLast('.', "").lowercase()

            return when (extension) {
                // Images
                in listOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "svg") ->
                    Icons.TwoTone.Image to MaterialTheme.colorScheme.tertiary

                // Videos
                in listOf("mp4", "avi", "mkv", "mov", "wmv", "flv", "webm") ->
                    Icons.TwoTone.Movie to MaterialTheme.colorScheme.tertiary

                // Audio
                in listOf("mp3", "wav", "flac", "aac", "ogg", "m4a") ->
                    Icons.TwoTone.Audiotrack to MaterialTheme.colorScheme.tertiary

                // Documents
                in listOf("pdf", "doc", "docx", "txt", "rtf", "xls", "xlsx", "ppt", "pptx") ->
                    Icons.TwoTone.PictureAsPdf to MaterialTheme.colorScheme.error

                // Code files
                in listOf("kt", "java", "py", "js", "ts", "html", "css", "xml", "json",
                         "cpp", "c", "h", "swift", "go", "rs", "php", "rb") ->
                    Icons.TwoTone.Code to MaterialTheme.colorScheme.surfaceTint

                // Default
                else -> Icons.AutoMirrored.Filled.InsertDriveFile to MaterialTheme.colorScheme.secondary
            }
        }
    }
}