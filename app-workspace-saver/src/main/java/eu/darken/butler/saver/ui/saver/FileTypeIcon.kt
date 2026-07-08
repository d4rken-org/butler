package eu.darken.butler.saver.ui.saver

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.InsertDriveFile
import androidx.compose.material.icons.twotone.Android
import androidx.compose.material.icons.twotone.Audiotrack
import androidx.compose.material.icons.twotone.Description
import androidx.compose.material.icons.twotone.FolderZip
import androidx.compose.material.icons.twotone.Image
import androidx.compose.material.icons.twotone.Movie
import androidx.compose.material.icons.twotone.PictureAsPdf
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Type-specific placeholder icon for a shared item, chosen from its mime type (preferred) or file name.
 * Used for metadata rows and as the preview fallback when a real preview can't be generated.
 */
internal fun fileTypeIcon(mimeType: String?, name: String?): ImageVector {
    val mime = mimeType?.lowercase()
    val ext = name?.substringAfterLast('.', "")?.lowercase()
    return when {
        mime == "application/vnd.android.package-archive" || ext == "apk" -> Icons.TwoTone.Android
        mime == "application/pdf" || ext == "pdf" -> Icons.TwoTone.PictureAsPdf
        mime?.startsWith("image/") == true -> Icons.TwoTone.Image
        mime?.startsWith("video/") == true -> Icons.TwoTone.Movie
        mime?.startsWith("audio/") == true -> Icons.TwoTone.Audiotrack
        mime?.startsWith("text/") == true -> Icons.TwoTone.Description
        ext in ARCHIVE_EXTS -> Icons.TwoTone.FolderZip
        else -> Icons.AutoMirrored.TwoTone.InsertDriveFile
    }
}

private val ARCHIVE_EXTS = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz")
