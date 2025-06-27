package eu.darken.butler.searcher.ui.search.rows

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

@Composable
fun getFileIconAndTint(data: FileRowData): Pair<ImageVector, Color> {
    val rowType = determineFileRowType(data.name)
    
    return when {
        data.fileType == FileType.DIRECTORY -> Icons.Default.Folder to MaterialTheme.colorScheme.primary
        data.fileType == FileType.SYMBOLIC_LINK -> Icons.Default.FolderOpen to MaterialTheme.colorScheme.primary
        
        rowType == FileRowType.Media -> {
            val extension = data.name.substringAfterLast('.', "").lowercase()
            when {
                extension in listOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "svg") -> 
                    Icons.Default.Image to MaterialTheme.colorScheme.tertiary
                extension in listOf("mp4", "avi", "mkv", "mov", "wmv", "flv", "webm") -> 
                    Icons.Default.Movie to MaterialTheme.colorScheme.tertiary
                extension in listOf("mp3", "wav", "flac", "aac", "ogg", "m4a") -> 
                    Icons.Default.Audiotrack to MaterialTheme.colorScheme.tertiary
                else -> Icons.AutoMirrored.Filled.InsertDriveFile to MaterialTheme.colorScheme.secondary
            }
        }
        
        rowType == FileRowType.Document -> Icons.Default.PictureAsPdf to MaterialTheme.colorScheme.error
        rowType == FileRowType.Code -> Icons.Default.Code to MaterialTheme.colorScheme.surfaceTint
        
        else -> Icons.AutoMirrored.Filled.InsertDriveFile to MaterialTheme.colorScheme.secondary
    }
}