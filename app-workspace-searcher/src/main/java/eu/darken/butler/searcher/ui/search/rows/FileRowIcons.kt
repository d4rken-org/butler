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

@Composable
fun getFileIconAndTint(data: FileRowData): Pair<ImageVector, Color> {
    val rowType = determineFileRowType(data.name)
    
    return when {
        data.fileType == FileType.DIRECTORY -> Icons.TwoTone.Folder to MaterialTheme.colorScheme.primary
        data.fileType == FileType.SYMBOLIC_LINK -> Icons.TwoTone.FolderOpen to MaterialTheme.colorScheme.primary
        
        rowType == FileRowType.Media -> {
            val extension = data.name.substringAfterLast('.', "").lowercase()
            when {
                extension in listOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "svg") -> 
                    Icons.TwoTone.Image to MaterialTheme.colorScheme.tertiary
                extension in listOf("mp4", "avi", "mkv", "mov", "wmv", "flv", "webm") -> 
                    Icons.TwoTone.Movie to MaterialTheme.colorScheme.tertiary
                extension in listOf("mp3", "wav", "flac", "aac", "ogg", "m4a") -> 
                    Icons.TwoTone.Audiotrack to MaterialTheme.colorScheme.tertiary
                else -> Icons.AutoMirrored.Filled.InsertDriveFile to MaterialTheme.colorScheme.secondary
            }
        }
        
        rowType == FileRowType.Document -> Icons.TwoTone.PictureAsPdf to MaterialTheme.colorScheme.error
        rowType == FileRowType.Code -> Icons.TwoTone.Code to MaterialTheme.colorScheme.surfaceTint
        
        else -> Icons.AutoMirrored.Filled.InsertDriveFile to MaterialTheme.colorScheme.secondary
    }
}