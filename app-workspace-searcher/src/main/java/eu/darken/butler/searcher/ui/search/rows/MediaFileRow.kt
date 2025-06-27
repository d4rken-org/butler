package eu.darken.butler.searcher.ui.search.rows

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import java.time.Instant

@Composable
fun MediaFileRow(
    data: FileRowData,
    onClick: () -> Unit = {}
) {
    FileRowBase(data = data, onClick = onClick) { fileData ->
        MediaFileIcon(fileData)

        Spacer(modifier = Modifier.width(16.dp))

        FileInfo(
            data = fileData,
            showMetadata = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun MediaFileIcon(data: FileRowData) {
    val (iconVector, tint) = getFileIconAndTint(data)
    
    Icon(
        imageVector = iconVector,
        contentDescription = data.fileType.name,
        tint = tint,
        modifier = Modifier.size(32.dp)
    )
}

@Preview2
@Composable
private fun MediaFileRowPreview() {
    PreviewWrapper {
        Column {
            MediaFileRow(
                data = FileRowData(
                    name = "vacation_photo.jpg",
                    path = "/storage/emulated/0/Pictures/vacation_photo.jpg",
                    fileType = FileType.FILE,
                    size = 1024 * 1024 * 3,
                    modifiedAt = Instant.now().minusSeconds(7200),
                    metadata = mapOf(
                        "Resolution" to "1920x1080",
                        "Camera" to "Pixel 8"
                    )
                )
            )
            
            MediaFileRow(
                data = FileRowData(
                    name = "summer_video.mp4",
                    path = "/storage/emulated/0/Movies/summer_video.mp4",
                    fileType = FileType.FILE,
                    size = 1024 * 1024 * 25,
                    modifiedAt = Instant.now().minusSeconds(3600 * 5),
                    metadata = mapOf(
                        "Duration" to "2:34",
                        "Quality" to "1080p"
                    )
                )
            )
            
            MediaFileRow(
                data = FileRowData(
                    name = "favorite_song.mp3",
                    path = "/storage/emulated/0/Music/favorite_song.mp3",
                    fileType = FileType.FILE,
                    size = 1024 * 1024 * 4,
                    modifiedAt = Instant.now().minusSeconds(86400 * 2),
                    metadata = mapOf(
                        "Duration" to "3:45",
                        "Artist" to "Unknown"
                    )
                )
            )
        }
    }
}